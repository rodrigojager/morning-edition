package dobra.ai.collector

import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.AiRequest
import dobra.ai.rewrite.Attempt
import dobra.ai.rewrite.RewriteLoop
import dobra.collect.engine.FetchTransport

/** What writing (or repairing) a collector came to. */
sealed interface WriteOutcome {
    val attempts: List<Attempt>

    /** The script passed the acceptance run; [acceptance] holds the two runs and the copy they read. */
    data class Accepted(
        val script: String,
        val suggested: List<String>,
        val acceptance: AcceptanceResult,
        override val attempts: List<Attempt>,
    ) : WriteOutcome

    /** Every attempt was refused; [reason] is what was wrong with the last one. */
    data class NotReady(val reason: String, override val attempts: List<Attempt>) : WriteOutcome
}

/**
 * Job A of the AI (PLAN-ai): writes a collector from the saved copy of the page and the reader's words, and checks it
 * with the acceptance run. A refused script goes back to the AI with what was wrong, at most [maxAttempts] calls in
 * all. The AI's answer is only ever used as a script run inside the engine, never as instructions for the app.
 * Provider failures ([dobra.ai.provider.AiException]) are not caught here.
 */
class CollectorWriter(
    private val gateway: AiGateway,
    private val acceptance: Acceptance = Acceptance(),
    val maxAttempts: Int = RewriteLoop.DEFAULT_ATTEMPTS,
) {
    suspend fun write(brief: CollectorBrief, live: FetchTransport? = null): WriteOutcome {
        val attempts = ArrayList<Attempt>()
        var fixture = SourceDiscovery.enrich(brief.fixture, live)
        var prompt = CollectorPrompt.user(brief.copy(fixture = fixture))
        var user = prompt
        repeat(maxAttempts) {
            val answer = gateway.call(
                AiJob.WRITE_COLLECTORS,
                AiRequest(model = "", user = user, system = CollectorPrompt.system, jsonOutput = true),
            )
            val read = CollectorPrompt.read(answer.text)
            val faults = if (read == null) {
                listOf("the answer is not a JSON object with a non-empty \"script\" string")
            } else {
                val checked = acceptance.check(read.script, fixture, brief.keywords, live)
                fixture = checked.fixture
                prompt = CollectorPrompt.user(brief.copy(fixture = fixture))
                if (checked.passed) {
                    attempts += Attempt(user, answer.text, emptyList())
                    return WriteOutcome.Accepted(read.script, read.keywords, checked, attempts)
                }
                checked.faults
            }
            attempts += Attempt(user, answer.text, faults)
            user = RewriteLoop.retry(prompt, answer.text, faults)
        }
        return WriteOutcome.NotReady(attempts.last().faults.joinToString("; "), attempts)
    }
}
