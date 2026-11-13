package dobra.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One way of calling a model, whatever the provider. */
interface ModelClient {
    suspend fun complete(request: AiRequest): ModelAnswer

    /** Ids of the models the account can use. */
    suspend fun listModels(): List<String>
}

fun clientFor(config: ProviderConfig, key: String, transport: HttpTransport): ModelClient = when (config.kind) {
    ProviderKind.OPENAI_STYLE -> OpenAiStyleClient(transport, config.baseUrl, key)
    ProviderKind.ANTHROPIC -> AnthropicClient(transport, config.baseUrl, key)
    ProviderKind.CHATGPT_OAUTH -> throw AiException.NotConfigured("use the ChatGPT session client")
}

private val wire = Json { ignoreUnknownKeys = true }

private fun parseObject(body: String): JsonObject = try {
    wire.parseToJsonElement(body).jsonObject
} catch (e: Exception) {
    throw AiException.BadAnswer("not a JSON object")
}

/** Both providers answer errors as `{"error": {"message": ...}}`. */
private fun errorDetail(body: String): String = try {
    val error = wire.parseToJsonElement(body).jsonObject["error"]
    (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (error as? JsonPrimitive)?.contentOrNull ?: "no detail"
} catch (e: Exception) {
    "no detail"
}

internal fun HttpResponse.orThrow(): HttpResponse {
    if (status in 200..299) return this
    val detail = errorDetail(body)
    throw when (status) {
        401, 403 -> AiException.KeyRefused(detail)
        429 -> AiException.RateLimited(headers["retry-after"]?.trim()?.toIntOrNull(), detail)
        else -> AiException.ProviderError(status, detail)
    }
}

private fun JsonElement?.long(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L

private fun idsOf(body: String): List<String> {
    val data = parseObject(body)["data"] as? JsonArray ?: throw AiException.BadAnswer("no model list")
    return data.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
}

/** Chat completions: OpenAI, DeepSeek and compatible endpoints. [baseUrl] already holds the version path, if any. */
class OpenAiStyleClient(
    private val transport: HttpTransport,
    baseUrl: String,
    private val key: String,
) : ModelClient {
    private val base = baseUrl.trimEnd('/')
    private val headers get() = mapOf("authorization" to "Bearer $key", "content-type" to "application/json")

    override suspend fun complete(request: AiRequest): ModelAnswer {
        val body = buildJsonObject {
            put("model", request.model)
            put(
                "messages",
                buildJsonArray {
                    request.system?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
                    add(buildJsonObject { put("role", "user"); put("content", request.user) })
                },
            )
            request.effort?.let { put("reasoning_effort", it.name.lowercase()) }
            request.maxOutputTokens?.let { put("max_tokens", it) }
            // JSON mode: documented by OpenAI and by DeepSeek (its only structured option) as {"type": "json_object"}.
            if (request.jsonOutput) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val response = transport.send(HttpRequest("POST", "$base/chat/completions", headers, body.toString())).orThrow()
        val json = parseObject(response.body)
        val text = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull ?: throw AiException.BadAnswer("no message content")
        val usage = json["usage"] as? JsonObject
        return ModelAnswer(text, TokenUsage(usage?.get("prompt_tokens").long(), usage?.get("completion_tokens").long()))
    }

    override suspend fun listModels(): List<String> =
        idsOf(transport.send(HttpRequest("GET", "$base/models", headers)).orThrow().body)
}

/** Anthropic's Messages API. */
class AnthropicClient(
    private val transport: HttpTransport,
    baseUrl: String,
    private val key: String,
) : ModelClient {
    private val base = baseUrl.trimEnd('/')
    private val headers
        get() = mapOf("x-api-key" to key, "anthropic-version" to VERSION, "content-type" to "application/json")

    override suspend fun complete(request: AiRequest): ModelAnswer {
        // [AiRequest.jsonOutput] has no plain switch here: Anthropic's structured outputs take a full schema in a
        // subset (no oneOf, every object closed) that a magazine's format schema is not, so the prompt describes the
        // JSON and the caller validates the answer.
        val budget = request.effort?.let { THINKING_BUDGET.getValue(it) }
        val body = buildJsonObject {
            put("model", request.model)
            // The thinking budget must stay below max_tokens, so the answer's own room is added on top of it.
            put("max_tokens", (request.maxOutputTokens ?: DEFAULT_MAX) + (budget ?: 0))
            request.system?.let { put("system", it) }
            put(
                "messages",
                buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", request.user) }) },
            )
            if (budget != null) {
                put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) })
            }
        }
        val response = transport.send(HttpRequest("POST", "$base/messages", headers, body.toString())).orThrow()
        val json = parseObject(response.body)
        val blocks = json["content"] as? JsonArray ?: throw AiException.BadAnswer("no content")
        val text = blocks.mapNotNull { b ->
            val o = b as? JsonObject
            if (o?.get("type")?.jsonPrimitive?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("")
        if (text.isEmpty()) throw AiException.BadAnswer("no text block")
        val usage = json["usage"] as? JsonObject
        return ModelAnswer(text, TokenUsage(usage?.get("input_tokens").long(), usage?.get("output_tokens").long()))
    }

    override suspend fun listModels(): List<String> =
        idsOf(transport.send(HttpRequest("GET", "$base/models", headers)).orThrow().body)

    private companion object {
        const val VERSION = "2023-06-01"
        const val DEFAULT_MAX = 4096
        val THINKING_BUDGET = mapOf(Effort.LOW to 1024, Effort.MEDIUM to 4096, Effort.HIGH to 16000)
    }
}
