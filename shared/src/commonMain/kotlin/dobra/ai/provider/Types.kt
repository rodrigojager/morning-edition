package dobra.ai.provider

import kotlinx.serialization.Serializable

/** API-key formats plus the separate ChatGPT plan Responses protocol. */
@Serializable
enum class ProviderKind { OPENAI_STYLE, ANTHROPIC, CHATGPT_OAUTH, OPENCODE }

/** The three jobs of the AI; each has its own provider and model. */
@Serializable
enum class AiJob { WRITE_COLLECTORS, REWRITE, ANSWER }

/** Generic thinking effort, mapped by each client to what its provider accepts. */
@Serializable
enum class Effort { LOW, MEDIUM, HIGH, AUTO, NONE, MINIMAL, XHIGH, MAX, ULTRA }

@Serializable
enum class EffortMode { THINKING_BUDGET, OUTPUT_CONFIG }

/** Account catalog metadata is optional: null means the server did not advertise that capability. */
data class ModelOption(
    val id: String,
    val name: String = id,
    val efforts: List<Effort>? = null,
    val serviceTiers: List<ServiceTier> = emptyList(),
    val effortMode: EffortMode = EffortMode.THINKING_BUDGET,
)

data class ServiceTier(val id: String, val name: String)

/** A provider the reader added. The key itself is never part of this record: only its last four characters. */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val keyLast4: String,
    /** Optional price in US dollars per million tokens, typed by the reader; zero means "not informed". */
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
)

/** Which provider, model and effort a job uses. */
@Serializable
data class JobChoice(
    val providerId: String,
    val model: String,
    val effort: Effort = Effort.MEDIUM,
    val serviceTier: String? = null,
    val effortMode: EffortMode = EffortMode.THINKING_BUDGET,
)

/** Starting points offered when adding a provider. The reader may edit the address. */
data class ProviderPreset(val name: String, val kind: ProviderKind, val baseUrl: String)

object ProviderPresets {
    val claude = ProviderPreset("Claude", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1")
    val openCode = ProviderPreset("OpenCode Console", ProviderKind.OPENCODE, OpenCodeClient.BASE)
    val all = listOf(
        ProviderPreset("OpenAI", ProviderKind.OPENAI_STYLE, "https://api.openai.com/v1"),
        claude,
        openCode,
        ProviderPreset("DeepSeek", ProviderKind.OPENAI_STYLE, "https://api.deepseek.com"),
    )
}

/**
 * One request to a model. [maxOutputTokens] null lets the provider decide (Anthropic requires one: 4096 then).
 * [jsonOutput] asks for a JSON object as the answer where the provider has a switch for it (the OpenAI-style JSON mode,
 * which DeepSeek and compatible endpoints share); the prompt must still describe the JSON and the answer is validated.
 */
data class AiRequest(
    val model: String,
    val user: String,
    val system: String? = null,
    val effort: Effort? = null,
    val maxOutputTokens: Int? = null,
    val jsonOutput: Boolean = false,
    val serviceTier: String? = null,
    val effortMode: EffortMode = EffortMode.THINKING_BUDGET,
)

data class TokenUsage(val inputTokens: Long, val outputTokens: Long)

data class ModelAnswer(val text: String, val usage: TokenUsage)

/** What can go wrong with a call. Messages never contain the key. */
sealed class AiException(message: String) : Exception(message) {
    /** The provider refused the key (HTTP 401 or 403). */
    class KeyRefused(detail: String) : AiException("key refused: $detail")

    /** The provider asks to slow down (HTTP 429). */
    class RateLimited(val retryAfterSeconds: Int?, detail: String) : AiException("rate limited: $detail")

    /** Any other answer that is not a success. */
    class ProviderError(val status: Int, detail: String) : AiException("provider error $status: $detail")

    /** The answer could not be read. */
    class BadAnswer(detail: String) : AiException("unreadable answer: $detail")

    /** The provider could not be reached. */
    class Unreachable(detail: String) : AiException("unreachable: $detail")

    /** The job has no provider chosen, or the provider was removed. */
    class NotConfigured(detail: String) : AiException("not configured: $detail")
}
