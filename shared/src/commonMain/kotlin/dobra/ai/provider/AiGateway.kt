package dobra.ai.provider

/**
 * The one way the app calls a model: a job names its provider and model in the settings, the call goes through the
 * matching client, and every successful call is recorded with its tokens and cost.
 */
class AiGateway(
    private val registry: ProviderRegistry,
    private val transport: HttpTransport,
    private val ledger: UsageLedger,
    private val clock: () -> Long = { 0L },
    private val oauthClient: (suspend () -> ModelClient)? = null,
) {
    /** Calls the model chosen for [job]. [model] and [effort] of [request] are replaced by the job's choice. */
    suspend fun call(job: AiJob, request: AiRequest, editionKey: String? = null): ModelAnswer {
        val choice = registry.state.value.choices[job] ?: throw AiException.NotConfigured("no provider chosen for $job")
        val config = registry.state.value.providers.firstOrNull { it.id == choice.providerId }
            ?: throw AiException.NotConfigured("provider ${choice.providerId} was removed")
        if (choice.model.isBlank()) throw AiException.NotConfigured("no model chosen for $job")
        val client = clientOf(config)
        val answer = client.complete(request.copy(model = choice.model, effort = choice.effort))
        ledger.record(
            UsageRecord(
                editionKey = editionKey,
                job = job,
                providerId = config.id,
                model = choice.model,
                inputTokens = answer.usage.inputTokens,
                outputTokens = answer.usage.outputTokens,
                costUsd = answer.usage.inputTokens / 1_000_000.0 * config.inputPricePerMillion +
                    answer.usage.outputTokens / 1_000_000.0 * config.outputPricePerMillion,
                at = clock(),
            ),
        )
        return answer
    }

    /** The models a provider offers to its account. */
    suspend fun models(providerId: String): List<String> {
        val config = registry.state.value.providers.firstOrNull { it.id == providerId }
            ?: throw AiException.NotConfigured("unknown provider")
        return clientOf(config).listModels()
    }

    private suspend fun clientOf(config: ProviderConfig): ModelClient {
        if (config.kind == ProviderKind.CHATGPT_OAUTH) {
            return oauthClient?.invoke() ?: throw AiException.NotConfigured("ChatGPT sign-in required")
        }
        val key = registry.key(config.id) ?: throw AiException.NotConfigured("no key for ${config.name}")
        return clientFor(config, key, transport)
    }
}
