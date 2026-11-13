package dobra.make

import dobra.ai.provider.AiJob
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.UsageLedger
import dobra.ai.provider.UsageTotal

/** What is missing before the AI can rewrite, so the screen can say it instead of failing. */
enum class MissingSetup {
    /** No provider was added. */
    NO_PROVIDER,

    /** Providers exist, but none was chosen for the rewriting job (or the chosen one was removed). */
    NO_CHOICE,

    /** The rewriting job has a provider but no model. */
    NO_MODEL,

    /** The chosen provider's key is not on the phone. */
    NO_KEY,
}

/** What the calls on [key] cost from now on: the returned function gives the total since this call. */
internal fun UsageLedger.spentSince(key: String): () -> UsageTotal {
    val before = totalFor(key)
    return {
        val now = totalFor(key)
        UsageTotal(
            now.calls - before.calls,
            now.inputTokens - before.inputTokens,
            now.outputTokens - before.outputTokens,
            now.costUsd - before.costUsd,
        )
    }
}

object RewriteSetup {
    /** Null when the rewriting job can call its model. */
    fun missing(registry: ProviderRegistry): MissingSetup? {
        val settings = registry.state.value
        if (settings.providers.isEmpty()) return MissingSetup.NO_PROVIDER
        val choice = settings.choices[AiJob.REWRITE] ?: return MissingSetup.NO_CHOICE
        if (settings.providers.none { it.id == choice.providerId }) return MissingSetup.NO_CHOICE
        if (choice.model.isBlank()) return MissingSetup.NO_MODEL
        if (runCatching { registry.key(choice.providerId) }.getOrNull() == null) return MissingSetup.NO_KEY
        return null
    }
}
