package dobra.ai.provider

import dobra.ai.collector.SourceSetup
import dobra.make.MissingSetup
import dobra.make.RewriteSetup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatGptClientTest {
    @Test
    fun restoredChatGptChoicesPassBothGenerationChecksWithoutAnApiKey() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val provider = registry.addChatGpt()
        val choice = JobChoice(provider.id, "account-model", Effort.MEDIUM)
        registry.choose(AiJob.WRITE_COLLECTORS, choice)
        registry.choose(AiJob.REWRITE, choice)
        val restored = ProviderRegistry(storage, ReverseCipher())
        assertNull(restored.key(provider.id))
        assertNull(SourceSetup.missing(restored))
        assertNull(RewriteSetup.missing(restored))
        val transport = ReplayTransport(mutableListOf(reply(200, completed), reply(200, completed)))
        val gateway = AiGateway(restored, transport, UsageLedger(storage),
            oauthClient = { ChatGptClient(transport) { "synthetic-session" } })
        for (job in listOf(AiJob.WRITE_COLLECTORS, AiJob.REWRITE)) {
            gateway.call(job, AiRequest("ignored", "test"))
            val body = Json.parseToJsonElement(transport.sent.last().body!!).jsonObject
            assertEquals(choice.model, body.getValue("model").jsonPrimitive.content)
            assertEquals("medium", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        }
    }

    @Test
    fun generationChecksStillRequireEachJobsModelAndApiProvidersKeys() {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val checks = listOf(AiJob.WRITE_COLLECTORS to SourceSetup::missing, AiJob.REWRITE to RewriteSetup::missing)
        for ((_, check) in checks) assertEquals(MissingSetup.NO_PROVIDER, check(registry))
        val plan = registry.addChatGpt()
        registry.choose(AiJob.ANSWER, JobChoice(plan.id, "answer-model"))
        for ((job, check) in checks) {
            assertEquals(MissingSetup.NO_CHOICE, check(registry))
            registry.selectProvider(job, plan.id)
            assertEquals(MissingSetup.NO_MODEL, check(registry))
            registry.selectModel(job, "account-model")
            assertNull(check(registry))
        }
        for (kind in listOf(ProviderKind.OPENAI_STYLE, ProviderKind.ANTHROPIC, ProviderKind.OPENCODE)) {
            val api = registry.add("API", kind, "https://example.test", "synthetic-key")
            for ((job, check) in checks) {
                registry.choose(job, JobChoice(api.id, "api-model"))
                assertNull(check(registry))
            }
            storage.blobs.keys.filter { it.endsWith(".enc") }.toList().forEach { storage.delete(it) }
            for ((_, check) in checks) assertEquals(MissingSetup.NO_KEY, check(registry))
            registry.remove(api.id)
            for ((_, check) in checks) assertEquals(MissingSetup.NO_CHOICE, check(registry))
        }
    }

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
    fun accountCatalogKeepsNamesAndOnlyRecognizedCapabilities() = runTest {
        val catalog = """{"models":[
            {"slug":"first","display_name":"Account model","visibility":"list",
             "supported_reasoning_levels":[{"effort":"low"},{"effort":"xhigh"},{"effort":"future"}],
             "service_tiers":[{"id":"fast","name":"Fast"},{"id":""},null]},
            {"slug":"hidden","visibility":"hide"},
            {"slug":"fallback","visibility":"list"},
            {"slug":"first","visibility":"list"},
            {"slug":"","visibility":"list"}]}"""
        val client = ChatGptClient(ReplayTransport(mutableListOf(reply(200, catalog)))) { "synthetic" }
        assertEquals(listOf(
            ModelOption("first", "Account model", listOf(Effort.LOW, Effort.XHIGH), listOf(ServiceTier("fast", "Fast"))),
            ModelOption("fallback"),
        ), client.modelOptions())
    }

    @Test
    fun jobOptionsSurviveRestartAndReachResponsesWhileAutomaticOmitsThem() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val provider = registry.addChatGpt()
        registry.choose(AiJob.ANSWER, JobChoice(provider.id, "chosen-model", Effort.XHIGH, "fast"))
        val restored = ProviderRegistry(storage, ReverseCipher())
        val transport = ReplayTransport(mutableListOf(reply(200, completed), reply(200, completed)))
        val gateway = AiGateway(restored, transport, UsageLedger(storage),
            oauthClient = { ChatGptClient(transport) { "synthetic-session" } })
        gateway.call(AiJob.ANSWER, AiRequest("ignored", "test", effort = Effort.LOW, serviceTier = "ignored"))
        val sent = Json.parseToJsonElement(transport.sent.last().body!!).jsonObject
        assertEquals("chosen-model", sent.getValue("model").jsonPrimitive.content)
        assertEquals("xhigh", sent.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("fast", sent.getValue("service_tier").jsonPrimitive.content)

        restored.choose(AiJob.ANSWER, JobChoice(provider.id, "chosen-model", Effort.AUTO))
        gateway.call(AiJob.ANSWER, AiRequest("ignored", "test", effort = Effort.HIGH, serviceTier = "fast"))
        val automatic = Json.parseToJsonElement(transport.sent.last().body!!).jsonObject
        assertFalse("reasoning" in automatic)
        assertFalse("service_tier" in automatic)
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
