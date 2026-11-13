package dobra.ai.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderOptionsTest {
    @Test
    fun oldSettingsKeepTheirModelAndEffortAfterUpgrade() {
        val storage = MemoryBlobStorage()
        storage.write(ProviderRegistry.SETTINGS, """{
            "providers":[{"id":"chatgpt","name":"ChatGPT","kind":"CHATGPT_OAUTH",
                "baseUrl":"https://api.openai.com/v1","keyLast4":""}],
            "choices":{"ANSWER":{"providerId":"chatgpt","model":"saved-model","effort":"HIGH"}}
        }""")
        val registry = ProviderRegistry(storage, ReverseCipher())
        assertEquals(JobChoice("chatgpt", "saved-model", Effort.HIGH), registry.state.value.choices[AiJob.ANSWER])
        registry.choose(AiJob.ANSWER, registry.state.value.choices[AiJob.ANSWER]!!.copy(serviceTier = "priority"))
        assertEquals("priority", ProviderRegistry(storage, ReverseCipher()).state.value.choices[AiJob.ANSWER]?.serviceTier)
    }

    @Test
    fun providerAndModelChangesClearIncompatibleOptionsButReselectingPreservesThem() {
        val registry = ProviderRegistry(MemoryBlobStorage(), ReverseCipher())
        val plan = registry.addChatGpt()
        val api = registry.add("Claude", ProviderKind.ANTHROPIC, "https://example.test", "synthetic-key")
        val original = JobChoice(plan.id, "old-model", Effort.XHIGH, "fast")
        registry.choose(AiJob.REWRITE, original)
        registry.selectProvider(AiJob.REWRITE, plan.id)
        assertEquals(original, registry.state.value.choices[AiJob.REWRITE])
        registry.selectModel(AiJob.REWRITE, "old-model")
        assertEquals(original, registry.state.value.choices[AiJob.REWRITE])
        registry.selectModel(AiJob.REWRITE, "new-model")
        assertEquals(JobChoice(plan.id, "new-model", Effort.AUTO), registry.state.value.choices[AiJob.REWRITE])
        registry.selectProvider(AiJob.REWRITE, api.id)
        assertEquals(JobChoice(api.id, "", Effort.AUTO), registry.state.value.choices[AiJob.REWRITE])
        registry.selectProvider(AiJob.REWRITE, "removed")
        assertEquals(api.id, registry.state.value.choices[AiJob.REWRITE]?.providerId)
    }

    @Test
    fun openCodeUsesPublicConsoleRoutesAndHonorsAnEditedEndpoint() = runTest {
        for (base in listOf(OpenCodeClient.BASE, "https://custom.example.test/console/")) {
            val transport = ReplayTransport(mutableListOf(reply(200, Fixtures.OPENAI_MODELS), reply(200, Fixtures.OPENAI_OK)))
            val config = ProviderConfig("console", "OpenCode", ProviderKind.OPENCODE, base, "fake")
            val client = clientFor(config, "synthetic-key", transport)
            assertEquals(listOf("model-a", "model-b"), client.listModels())
            assertEquals("Olá, mundo", client.complete(AiRequest("model-a", "test", effort = Effort.AUTO)).text)
            assertEquals("${base.trimEnd('/')}/v1/models", transport.sent.first().url)
            assertEquals("${base.trimEnd('/')}/openai/v1/chat/completions", transport.sent.last().url)
            assertTrue(transport.sent.all { it.headers["authorization"] == "Bearer synthetic-key" })
            assertTrue(transport.sent.all { it.headers["user-agent"]!!.startsWith("primeira-dobra/") })
            assertFalse("reasoning_effort" in Json.parseToJsonElement(transport.sent.last().body!!).jsonObject)
        }
    }

    @Test
    fun automaticAnthropicEffortLetsTheApiChooseWithoutThinkingBudget() = runTest {
        val transport = ReplayTransport(mutableListOf(reply(200, Fixtures.ANTHROPIC_OK)))
        AnthropicClient(transport, "https://example.test/v1", "synthetic-key")
            .complete(AiRequest("claude-model", "test", effort = Effort.AUTO))
        assertFalse("thinking" in Json.parseToJsonElement(transport.sent.single().body!!).jsonObject)
    }

    @Test
    fun anthropicCatalogPaginatesAndSelectedCapabilitiesReachMessagesAfterRestart() = runTest {
        val transport = ReplayTransport(mutableListOf(
            reply(200, """{"data":[{"id":"modern","display_name":"Modern Claude","capabilities":{
                "effort":{"supported":true,"low":{"supported":true},"xhigh":{"supported":true},
                    "max":{"supported":false}}}}],"has_more":true,"last_id":"modern"}"""),
            reply(200, """{"data":[{"id":"old"},{"id":"retired","lifecycle":"retired"},
                {"id":"plain","capabilities":{"effort":{"supported":false}}}],"has_more":false}"""),
            reply(200, Fixtures.ANTHROPIC_OK),
        ))
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val provider = registry.add("Claude", ProviderKind.ANTHROPIC, "https://custom.test/v1", "synthetic-key")
        val gateway = AiGateway(registry, transport, UsageLedger(storage))
        val options = gateway.modelOptions(provider.id)
        assertEquals(listOf("modern", "old", "plain"), options.map { it.id })
        assertEquals(listOf(Effort.LOW, Effort.XHIGH), options.first().efforts)
        assertEquals("Modern Claude", options.first().name)
        assertNull(options[1].efforts)
        assertEquals(emptyList(), options[2].efforts)
        assertEquals("https://custom.test/v1/models?after_id=modern", transport.sent[1].url)
        registry.selectProvider(AiJob.ANSWER, provider.id)
        registry.selectModel(AiJob.ANSWER, options.first())
        registry.choose(AiJob.ANSWER, registry.state.value.choices.getValue(AiJob.ANSWER).copy(effort = Effort.XHIGH))
        registry.applyToAllJobs(AiJob.ANSWER)
        val restored = ProviderRegistry(storage, ReverseCipher())
        assertEquals(3, restored.state.value.choices.size)
        assertTrue(restored.state.value.choices.values.all { it.effortMode == EffortMode.OUTPUT_CONFIG })
        AiGateway(restored, transport, UsageLedger(storage)).call(AiJob.REWRITE, AiRequest("ignored", "test"))
        val sent = Json.parseToJsonElement(transport.sent.last().body!!).jsonObject
        assertEquals("xhigh", sent["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(4096, sent["max_tokens"]!!.jsonPrimitive.int)
        assertFalse("thinking" in sent)
        assertFalse("service_tier" in sent)
    }

    @Test
    fun invalidAnthropicPaginationFailsInsteadOfLooping() = runTest {
        val client = AnthropicClient(ReplayTransport(mutableListOf(
            reply(200, """{"data":[],"has_more":true,"last_id":"bad?cursor"}"""),
        )), "https://example.test/v1", "synthetic-key")
        assertFailsWith<AiException.BadAnswer> { client.modelOptions() }
    }

    @Test
    fun anthropicFastModeUsesItsOwnSpeedFieldOnlyOnDocumentedModels() = runTest {
        val transport = ReplayTransport(mutableListOf(
            reply(200, """{"data":[{"id":"claude-opus-5-5"},{"id":"claude-opus-4-6"},{"id":"unknown"}]}"""),
            reply(200, Fixtures.ANTHROPIC_OK),
        ))
        val client = AnthropicClient(transport, "https://example.test/v1", "synthetic-key")
        val models = client.modelOptions()
        assertEquals(listOf(ServiceTier("fast", "Fast")), models.first().serviceTiers)
        assertTrue(models.drop(1).all { it.serviceTiers.isEmpty() })
        client.complete(AiRequest("claude-opus-5-5", "test", effort = Effort.AUTO, serviceTier = "fast"))
        val body = Json.parseToJsonElement(transport.sent.last().body!!).jsonObject
        assertEquals("fast", body["speed"]!!.jsonPrimitive.content)
        assertFalse("service_tier" in body)
    }
}
