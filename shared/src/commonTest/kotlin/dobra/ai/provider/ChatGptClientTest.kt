package dobra.ai.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatGptClientTest {
    private val completed = """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"An edition"}]}],"usage":{"input_tokens":21,"output_tokens":8}}}

"""

    @Test
    fun responsesUsesPlanProtocolAndCountsCompletedOutput() = runTest {
        val transport = ReplayTransport(mutableListOf(reply(200, completed.replace("\n", "\r\n"))))
        val answer = ChatGptClient(transport) { "synthetic-session" }.complete(
            AiRequest("account-model", "Write JSON", "Source material", Effort.HIGH, 123, true),
        )
        assertEquals(ModelAnswer("An edition", TokenUsage(21, 8)), answer)
        val sent = transport.sent.single()
        assertEquals("https://api.openai.com/v1/responses", sent.url)
        assertEquals("Bearer synthetic-session", sent.headers["authorization"])
        val body = Json.parseToJsonElement(sent.body!!).jsonObject
        assertEquals(false, body.getValue("store").jsonPrimitive.boolean)
        assertEquals(true, body.getValue("stream").jsonPrimitive.boolean)
        assertEquals("Source material", body.getValue("instructions").jsonPrimitive.content)
        assertTrue(body["input"] is JsonArray)
        assertFalse("max_output_tokens" in body)
        assertFalse("messages" in body)
    }

    @Test
    fun rejectsInterruptedFailedAndIncompleteResponses() {
        for (body in listOf("data: [DONE]\n\n", "data: {\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n",
            "data: {\"type\":\"response.failed\"}\n\n",
            "data: {\"type\":\"response.incomplete\"}\n\n",
            "data: {\"type\":\"error\"}\n\n",
            "data: {\"type\":\"response.completed\",\"response\":[]}\n\n",
            completed.replace("\"status\":\"completed\"", "\"status\":\"incomplete\""))) {
            assertFailsWith<AiException.BadAnswer> { ChatGptClient.readStream(body) }
        }
    }

    @Test
    fun usesOnlyVisibleAccountModelsAndRejectsApiKeyCatalog() = runTest {
        val transport = ReplayTransport(mutableListOf(
            reply(200, """{"models":[{"slug":"first","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"last","visibility":"list"}]}"""),
            reply(200, Fixtures.OPENAI_MODELS),
        ))
        val client = ChatGptClient(transport) { "synthetic-session" }
        assertEquals(listOf("first", "last"), client.listModels())
        assertFailsWith<AiException.BadAnswer> { client.listModels() }
    }

    @Test
    fun gatewayRoutesOauthWithoutAKeyAndPreservesOtherProviders() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val keyProvider = registry.add("Existing", ProviderKind.OPENAI_STYLE, "https://example.test", "synthetic-key")
        val oauth = registry.addChatGpt()
        assertEquals(oauth, registry.addChatGpt())
        assertNull(registry.key(oauth.id))
        val transport = ReplayTransport(mutableListOf(reply(200, completed), reply(200, Fixtures.OPENAI_OK)))
        val ledger = UsageLedger(storage)
        val gateway = AiGateway(registry, transport, ledger, oauthClient = { ChatGptClient(transport) { "synthetic-session" } })
        registry.choose(AiJob.REWRITE, JobChoice(oauth.id, "plan-model"))
        assertEquals("An edition", gateway.call(AiJob.REWRITE, AiRequest("ignored", "test")).text)
        registry.choose(AiJob.ANSWER, JobChoice(keyProvider.id, "key-model"))
        assertEquals("Olá, mundo", gateway.call(AiJob.ANSWER, AiRequest("ignored", "test")).text)
        assertEquals("https://example.test/chat/completions", transport.sent.last().url)
        registry.remove(oauth.id)
        assertEquals(keyProvider, registry.state.value.providers.single())
        assertFalse(AiJob.REWRITE in registry.state.value.choices)
        assertEquals(2, ledger.all().size)
    }
}
