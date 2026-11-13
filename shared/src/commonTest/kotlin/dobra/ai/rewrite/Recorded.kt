package dobra.ai.rewrite

import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.HttpRequest
import dobra.ai.provider.HttpResponse
import dobra.ai.provider.JobChoice
import dobra.ai.provider.MemoryBlobStorage
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.ReplayTransport
import dobra.ai.provider.SecretCipher
import dobra.ai.provider.UsageLedger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A cipher that does nothing: tests never hold a real key. */
object PlainCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(sealed: ByteArray) = sealed
}

/**
 * Recorded answers of an OpenAI-style provider (the chat completions format of its documentation), replayed in order
 * through a fake transport. Nothing reaches a network and the key is a fake.
 */
class Recorded(answers: List<String>, priced: Boolean = true) {
    val transport = ReplayTransport(answers.map { completion(it) }.toMutableList())
    val storage = MemoryBlobStorage()
    val registry = ProviderRegistry(storage, PlainCipher)
    val ledger = UsageLedger(storage)
    val gateway = AiGateway(registry, transport, ledger)

    init {
        val p = registry.add(
            "Teste", ProviderKind.OPENAI_STYLE, "https://api.example.test/v1", "sk-fake-test-0000",
            inputPricePerMillion = if (priced) 1.0 else 0.0, outputPricePerMillion = if (priced) 4.0 else 0.0,
        )
        registry.choose(AiJob.REWRITE, JobChoice(p.id, "model-rewrite"))
    }

    /** The user message of each request sent, in order. */
    val prompts: List<String> get() = transport.sent.map { userOf(it) }

    companion object {
        fun completion(content: String): HttpResponse = HttpResponse(
            200, emptyMap(),
            buildJsonObject {
                put("id", "chatcmpl-test")
                put("object", "chat.completion")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                        put("finish_reason", "stop")
                    })
                })
                put("usage", buildJsonObject { put("prompt_tokens", 1000); put("completion_tokens", 500) })
            }.toString(),
        )

        fun bodyOf(request: HttpRequest): JsonObject = Json.parseToJsonElement(request.body!!).jsonObject

        fun userOf(request: HttpRequest): String = (bodyOf(request)["messages"] as JsonArray)
            .map { it.jsonObject }.last { (it["role"] as JsonPrimitive).content == "user" }
            .let { (it["content"] as JsonPrimitive).content }
    }
}
