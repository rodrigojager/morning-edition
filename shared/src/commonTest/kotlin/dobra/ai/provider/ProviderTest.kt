package dobra.ai.provider

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replays hand-written answers in each provider's documented format; remembers what was asked. Never touches a network. */
class ReplayTransport(private val answers: MutableList<HttpResponse>) : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        sent += request
        return answers.removeAt(0)
    }
}

fun reply(status: Int, body: String, vararg headers: Pair<String, String>) = HttpResponse(status, mapOf(*headers), body)

object Fixtures {
    const val OPENAI_OK = """{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-x",
        "choices":[{"index":0,"message":{"role":"assistant","content":"Olá, mundo"},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}"""
    const val OPENAI_401 = """{"error":{"message":"Incorrect API key provided.","type":"invalid_request_error","code":"invalid_api_key"}}"""
    const val OPENAI_429 = """{"error":{"message":"Rate limit reached.","type":"requests","code":"rate_limit_exceeded"}}"""
    const val OPENAI_MODELS = """{"object":"list","data":[{"id":"model-a","object":"model"},{"id":"model-b","object":"model"}]}"""
    const val ANTHROPIC_OK = """{"id":"msg_1","type":"message","role":"assistant","model":"m",
        "content":[{"type":"thinking","thinking":"hmm","signature":"s"},{"type":"text","text":"Bom dia"}],
        "stop_reason":"end_turn","usage":{"input_tokens":200,"output_tokens":50}}"""
    const val ANTHROPIC_401 = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
    const val ANTHROPIC_429 = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests exceeded"}}"""
}

class ProviderClientsTest {
    private val fakeKey = "sk-fake-0000-AAAA"

    @Test
    fun openAiStyleGivesTextAndTokens() = runTest {
        val transport = ReplayTransport(mutableListOf(reply(200, Fixtures.OPENAI_OK)))
        val client = OpenAiStyleClient(transport, "https://api.example.test/v1/", fakeKey)
        val answer = client.complete(AiRequest("m", "oi", system = "seja breve", effort = Effort.HIGH))
        assertEquals("Olá, mundo", answer.text)
        assertEquals(TokenUsage(120, 30), answer.usage)
        val sent = transport.sent.single()
        assertEquals("https://api.example.test/v1/chat/completions", sent.url)
        assertEquals("Bearer $fakeKey", sent.headers["authorization"])
        assertTrue("\"reasoning_effort\":\"high\"" in sent.body!!)
        assertTrue("\"role\":\"system\"" in sent.body!!)
    }

    @Test
    fun openAiStyleRefusedKeyAndRateLimit() = runTest {
        val refused = OpenAiStyleClient(ReplayTransport(mutableListOf(reply(401, Fixtures.OPENAI_401))), "https://x.test", fakeKey)
        val e1 = assertFailsWith<AiException.KeyRefused> { refused.complete(AiRequest("m", "oi")) }
        assertTrue("Incorrect API key" in e1.message!!)
        assertFalse(fakeKey in e1.message!!)

        val limited = OpenAiStyleClient(
            ReplayTransport(mutableListOf(reply(429, Fixtures.OPENAI_429, "retry-after" to "20"))), "https://x.test", fakeKey,
        )
        assertEquals(20, assertFailsWith<AiException.RateLimited> { limited.complete(AiRequest("m", "oi")) }.retryAfterSeconds)
    }

    @Test
    fun openAiStyleListsModels() = runTest {
        val client = OpenAiStyleClient(ReplayTransport(mutableListOf(reply(200, Fixtures.OPENAI_MODELS))), "https://x.test", fakeKey)
        assertEquals(listOf("model-a", "model-b"), client.listModels())
    }

    @Test
    fun anthropicGivesTextOnlyAndTokens() = runTest {
        val transport = ReplayTransport(mutableListOf(reply(200, Fixtures.ANTHROPIC_OK)))
        val client = AnthropicClient(transport, "https://api.example.test/v1", fakeKey)
        val answer = client.complete(AiRequest("m", "oi", system = "seja breve", effort = Effort.LOW, maxOutputTokens = 500))
        assertEquals("Bom dia", answer.text)
        assertEquals(TokenUsage(200, 50), answer.usage)
        val sent = transport.sent.single()
        assertEquals("https://api.example.test/v1/messages", sent.url)
        assertEquals(fakeKey, sent.headers["x-api-key"])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertTrue("\"max_tokens\":1524" in sent.body!!)
        assertTrue("\"budget_tokens\":1024" in sent.body!!)
    }

    @Test
    fun anthropicRefusedKeyAndRateLimit() = runTest {
        val refused = AnthropicClient(ReplayTransport(mutableListOf(reply(401, Fixtures.ANTHROPIC_401))), "https://x.test", fakeKey)
        val e = assertFailsWith<AiException.KeyRefused> { refused.complete(AiRequest("m", "oi")) }
        assertTrue("invalid x-api-key" in e.message!!)
        val limited = AnthropicClient(ReplayTransport(mutableListOf(reply(429, Fixtures.ANTHROPIC_429))), "https://x.test", fakeKey)
        val limit = assertFailsWith<AiException.RateLimited> { limited.complete(AiRequest("m", "oi")) }
        assertNull(limit.retryAfterSeconds)
    }

    @Test
    fun otherStatusAndGarbageAreClearErrors() = runTest {
        val server = OpenAiStyleClient(ReplayTransport(mutableListOf(reply(500, "oops"))), "https://x.test", fakeKey)
        assertEquals(500, assertFailsWith<AiException.ProviderError> { server.complete(AiRequest("m", "oi")) }.status)
        val garbage = AnthropicClient(ReplayTransport(mutableListOf(reply(200, "<html>"))), "https://x.test", fakeKey)
        assertFailsWith<AiException.BadAnswer> { garbage.complete(AiRequest("m", "oi")) }
    }
}

/** Reverses the bytes: enough to tell ciphertext from plain text in tests. */
class ReverseCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()
    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
}

class RegistryAndLedgerTest {
    private val key = "sk-fake-1234-WXYZ"

    @Test
    fun keyIsKeptOnlyAsCiphertextAndOnlyLastFourShow() {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val p = registry.add("OpenAI", ProviderKind.OPENAI_STYLE, "https://x.test", key)
        assertEquals("WXYZ", p.keyLast4)
        assertEquals(key, registry.key(p.id))
        assertTrue(storage.blobs.values.none { key in it })
        // A new registry over the same storage sees the same providers.
        assertEquals(listOf(p), ProviderRegistry(storage, ReverseCipher()).state.value.providers)
        registry.choose(AiJob.REWRITE, JobChoice(p.id, "m"))
        registry.remove(p.id)
        assertTrue(registry.state.value.providers.isEmpty())
        assertTrue(registry.state.value.choices.isEmpty())
        assertNull(registry.key(p.id))
        assertTrue(storage.blobs.keys.none { it.startsWith("key-") })
    }

    @Test
    fun callsAreRecordedAndSummedPerEdition() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val p = registry.add("OpenAI", ProviderKind.OPENAI_STYLE, "https://x.test", key, 2.0, 10.0)
        registry.choose(AiJob.REWRITE, JobChoice(p.id, "model-a", Effort.LOW))
        val transport = ReplayTransport(
            mutableListOf(reply(200, Fixtures.OPENAI_OK), reply(200, Fixtures.OPENAI_OK), reply(200, Fixtures.OPENAI_OK)),
        )
        val ledger = UsageLedger(storage)
        val gateway = AiGateway(registry, transport, ledger)
        gateway.call(AiJob.REWRITE, AiRequest("ignored", "a"), "mag/2026-11-13")
        gateway.call(AiJob.REWRITE, AiRequest("ignored", "b"), "mag/2026-11-13")
        gateway.call(AiJob.REWRITE, AiRequest("ignored", "c"), "mag/2026-11-14")
        assertTrue("\"model\":\"model-a\"" in transport.sent[0].body!!)

        val total = ledger.totalFor("mag/2026-11-13")
        assertEquals(2, total.calls)
        assertEquals(240, total.inputTokens)
        assertEquals(60, total.outputTokens)
        // 240 * 2 / 1e6 + 60 * 10 / 1e6
        assertEquals(0.00108, total.costUsd, 1e-9)
        assertEquals(1, ledger.totalFor("mag/2026-11-14").calls)
        // It survives a restart.
        assertEquals(2, UsageLedger(storage).totalFor("mag/2026-11-13").calls)
        assertTrue(storage.blobs.values.none { key in it })
    }

    @Test
    fun failedCallRecordsNothingAndUnchosenJobFails() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val ledger = UsageLedger(storage)
        val gateway = AiGateway(registry, ReplayTransport(mutableListOf(reply(429, Fixtures.OPENAI_429))), ledger)
        assertFailsWith<AiException.NotConfigured> { gateway.call(AiJob.ANSWER, AiRequest("m", "x")) }
        val p = registry.add("OpenAI", ProviderKind.OPENAI_STYLE, "https://x.test", key)
        registry.choose(AiJob.ANSWER, JobChoice(p.id, "m"))
        assertFailsWith<AiException.RateLimited> { gateway.call(AiJob.ANSWER, AiRequest("m", "x")) }
        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun loggingTransportNeverWritesTheKey() = runTest {
        val lines = mutableListOf<String>()
        val transport = LoggingTransport(ReplayTransport(mutableListOf(reply(401, Fixtures.OPENAI_401))), lines::add)
        val client = OpenAiStyleClient(transport, "https://x.test", key)
        assertFailsWith<AiException.KeyRefused> { client.complete(AiRequest("m", "oi")) }
        assertEquals(1, lines.size)
        assertTrue(lines.none { key in it })
    }
}
