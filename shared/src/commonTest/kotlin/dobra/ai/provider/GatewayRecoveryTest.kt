package dobra.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewayRecoveryTest {
    private val request = AiRequest("ignored", "A tool article")

    private fun gateway(client: ModelClient): Pair<AiGateway, UsageLedger> {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val provider = registry.addChatGpt()
        registry.choose(AiJob.REWRITE, JobChoice(provider.id, "chosen-model", Effort.MEDIUM))
        val ledger = UsageLedger(storage)
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse = error("OAuth uses its own client")
        }
        return AiGateway(registry, transport, ledger, oauthClient = { client }) to ledger
    }

    private fun client(call: suspend (AiRequest) -> ModelAnswer) = object : ModelClient {
        override suspend fun listModels() = emptyList<String>()
        override suspend fun complete(request: AiRequest) = call(request)
    }

    @Test
    fun lostConnectionsRecoverWithTheSameModelAndOnlySuccessfulUsageIsRecorded() = runTest {
        val sent = mutableListOf<AiRequest>()
        val (gateway, ledger) = gateway(client {
            sent += it
            if (sent.size < 3) throw AiException.Unreachable("UnknownHostException")
            ModelAnswer("Recovered article", TokenUsage(10, 20))
        })
        assertEquals("Recovered article", gateway.call(AiJob.REWRITE, request, "mag/day").text)
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.model == "chosen-model" && it.effort == Effort.MEDIUM })
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(1, ledger.totalFor("mag/day").calls)
    }

    @Test
    fun persistentNetworkFailureStopsAfterThreeCallsWithoutInventingUsage() = runTest {
        var calls = 0
        val (gateway, ledger) = gateway(client { calls++; throw AiException.Unreachable("SocketException") })
        assertFailsWith<AiException.Unreachable> { gateway.call(AiJob.REWRITE, request) }
        assertEquals(3, calls)
        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun authRateLimitsBadAnswersAndCancellationAreNeverRetried() = runTest {
        for (failure in listOf(AiException.KeyRefused("refused"), AiException.RateLimited(10, "slow down"),
            AiException.BadAnswer("invalid"), AiException.ProviderError(503, "unavailable"), CancellationException("cancelled"))) {
            var calls = 0
            val (gateway, _) = gateway(client { calls++; throw failure })
            try { gateway.call(AiJob.REWRITE, request); error("Expected failure") } catch (e: Exception) {
                assertTrue(e === failure)
            }
            assertEquals(1, calls)
        }
    }
}
