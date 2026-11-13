package dobra.ai.provider

import kotlinx.serialization.json.*

/** ChatGPT plan requests use the public Responses route, never an API-key or custom endpoint. */
class ChatGptClient(private val transport: HttpTransport, private val token: suspend () -> String) : ModelClient {
    private suspend fun headers() = mapOf("authorization" to "Bearer ${token()}", "content-type" to "application/json")

    override suspend fun listModels(): List<String> {
        val response = transport.send(HttpRequest("GET", "$BASE/models", headers())).orThrow()
        val catalog = try { Json.parseToJsonElement(response.body).jsonObject } catch (_: Exception) {
            throw AiException.BadAnswer("invalid ChatGPT model catalog")
        }
        val models = catalog["models"] as? JsonArray
            ?: throw AiException.BadAnswer("no ChatGPT model catalog")
        return models.mapNotNull {
            val model = it as? JsonObject ?: return@mapNotNull null
            if ((model["visibility"] as? JsonPrimitive)?.contentOrNull == "list")
                (model["slug"] as? JsonPrimitive)?.contentOrNull?.takeIf { slug -> slug.isNotBlank() } else null
        }
    }

    override suspend fun complete(request: AiRequest): ModelAnswer {
        val body = buildJsonObject {
            put("model", request.model)
            put("store", false)
            put("stream", true)
            request.system?.let { put("instructions", it) }
            put("input", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", request.user) })
            })
            request.effort?.let { put("reasoning", buildJsonObject { put("effort", it.name.lowercase()) }) }
            if (request.jsonOutput) put("text", buildJsonObject {
                put("format", buildJsonObject { put("type", "json_object") })
            })
        }
        val response = transport.send(HttpRequest("POST", "$BASE/responses", headers() + ("accept" to "text/event-stream"), body.toString())).orThrow()
        return readStream(response.body)
    }

    companion object {
        const val BASE = "https://api.openai.com/v1"

        /** Read SSE frames, including multiline data. An interrupted answer must never become an edition. */
        fun readStream(body: String): ModelAnswer = try {
            parseStream(body)
        } catch (e: AiException) {
            throw e
        } catch (_: Exception) {
            throw AiException.BadAnswer("invalid Responses event shape")
        }

        private fun parseStream(body: String): ModelAnswer {
            val frames = body.replace("\r\n", "\n").split("\n\n")
            for (frame in frames) {
                val data = frame.lineSequence().filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trimStart() }.joinToString("\n")
                if (data.isBlank() || data == "[DONE]") continue
                val event = try { Json.parseToJsonElement(data).jsonObject } catch (_: Exception) {
                    throw AiException.BadAnswer("invalid Responses event")
                }
                when (event["type"]?.jsonPrimitive?.content) {
                    "error", "response.failed", "response.incomplete" ->
                        throw AiException.BadAnswer("ChatGPT did not complete the response; check plan usage or sign in again")
                    "response.completed" -> {
                        val result = event["response"]?.jsonObject ?: throw AiException.BadAnswer("no completed response")
                        if (result["status"]?.jsonPrimitive?.content != "completed") throw AiException.BadAnswer("response not completed")
                        val text = (result["output"] as? JsonArray).orEmpty().flatMap {
                            (it.jsonObject["content"] as? JsonArray).orEmpty()
                        }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
                            .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
                        if (text.isEmpty()) throw AiException.BadAnswer("no response text")
                        val usage = result["usage"] as? JsonObject
                        return ModelAnswer(text, TokenUsage(
                            usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                            usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0,
                        ))
                    }
                }
            }
            throw AiException.BadAnswer("stream ended before response.completed")
        }
    }
}
