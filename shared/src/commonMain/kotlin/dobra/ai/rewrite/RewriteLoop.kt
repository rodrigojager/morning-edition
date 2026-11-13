package dobra.ai.rewrite

import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.AiRequest

/** One call of a loop: what was sent, what came back and what was wrong with it (empty when it was accepted). */
data class Attempt(val prompt: String, val answer: String, val faults: List<String>)

/** What the caller's check says of one answer. */
sealed interface Verdict<out T> {
    data class Ok<T>(val value: T) : Verdict<T>

    /** The answer is refused; the messages go back to the model. */
    data class Faults(val messages: List<String>) : Verdict<Nothing>

    /** The model chose to leave the thing out, with the reason. */
    data class Skip(val reason: String) : Verdict<Nothing>
}

sealed interface LoopResult<out T> {
    val attempts: List<Attempt>

    data class Done<T>(val value: T, override val attempts: List<Attempt>) : LoopResult<T>
    data class Skipped(val reason: String, override val attempts: List<Attempt>) : LoopResult<Nothing>

    /** Every attempt was refused; [faults] are those of the last one. */
    data class Failed(val faults: List<String>, override val attempts: List<Attempt>) : LoopResult<Nothing>
}

/**
 * Calls the rewriting model and checks its answer; a refused answer goes back with the checker's messages, at most
 * [maxAttempts] calls in all. Every call goes through the gateway, so its tokens and cost land on [editionKey].
 * Provider failures ([dobra.ai.provider.AiException]) are not caught here.
 */
class RewriteLoop(private val gateway: AiGateway, val maxAttempts: Int = DEFAULT_ATTEMPTS) {
    suspend fun <T> run(system: String, prompt: String, editionKey: String?, judge: (String) -> Verdict<T>): LoopResult<T> {
        val attempts = ArrayList<Attempt>()
        var user = prompt
        repeat(maxAttempts) {
            val answer = gateway.call(AiJob.REWRITE, AiRequest(model = "", user = user, system = system, jsonOutput = true), editionKey)
            when (val verdict = judge(answer.text)) {
                is Verdict.Ok -> {
                    attempts += Attempt(user, answer.text, emptyList())
                    return LoopResult.Done(verdict.value, attempts)
                }
                is Verdict.Skip -> {
                    attempts += Attempt(user, answer.text, emptyList())
                    return LoopResult.Skipped(verdict.reason, attempts)
                }
                is Verdict.Faults -> {
                    attempts += Attempt(user, answer.text, verdict.messages)
                    user = retry(prompt, answer.text, verdict.messages)
                }
            }
        }
        return LoopResult.Failed(attempts.last().faults, attempts)
    }

    companion object {
        const val DEFAULT_ATTEMPTS = 3
        private const val ECHO_CHARS = 12_000

        /** The first request again, followed by the refused answer and the validator's messages. */
        fun retry(prompt: String, answer: String, faults: List<String>): String = buildString {
            append(prompt)
            append("\n\n# Your previous answer\n")
            append(answer.take(ECHO_CHARS))
            append("\n\n# The validator refused it for these reasons\n")
            faults.forEach { append("- ").append(it).append('\n') }
            append("\nAnswer again with the whole JSON object corrected. JSON only, no explanation.")
        }
    }
}
