package dobra.app.library

import dobra.ai.collector.CollectorWork
import dobra.ai.collector.SetupOutcome
import dobra.ai.provider.AiException
import dobra.collect.health.HealthRecord
import dobra.make.MissingSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the AI's work on one source's collector stands, as the magazine page draws it. */
sealed interface DeskState {
    data object Idle : DeskState

    /** The page is being copied and the AI is writing ([repair] false) or adapting ([repair] true) the collector. */
    data class Working(val repair: Boolean) : DeskState

    /** The collector was accepted; [suggested] are the AI's keywords the reader may add to the recipe. */
    data class Ready(val suggested: List<String>) : DeskState

    data class NotReady(val reason: String) : DeskState
    data class Unavailable(val reason: String) : DeskState
    data class Missing(val what: MissingSetup) : DeskState
    data class Failed(val reason: FailReason, val detail: String) : DeskState
}

/**
 * Asks the AI to write or repair collectors, in the app's own scope so the work goes on while the reader moves between
 * tabs. Before any call it checks that the collector-writing job has a provider, a model and a key ([missing]).
 */
class CollectorDesk(
    private val scope: CoroutineScope,
    private val work: suspend () -> CollectorWork,
    private val missing: () -> MissingSetup? = { null },
) {
    private val states = MutableStateFlow<Map<String, DeskState>>(emptyMap())

    private fun key(magazine: String, source: String) = "$magazine/$source"

    fun state(magazine: String, source: String): Flow<DeskState> =
        states.map { it[key(magazine, source)] ?: DeskState.Idle }.distinctUntilChanged()

    /** Copies the page and asks for a collector. Does nothing while this source is already being worked on. */
    fun write(magazine: String, source: String) = start(magazine, source, null)

    /** Copies the page again and asks the AI to adapt the collector, for the [reason] it is being repaired. */
    fun repair(magazine: String, source: String, reason: String) = start(magazine, source, reason)

    /** Adds only the [chosen] suggestions to the recipe, then forgets the suggestions. */
    fun keep(magazine: String, source: String, offered: List<String>, chosen: List<String>) {
        set(magazine, source, DeskState.Idle)
        if (chosen.isEmpty()) return
        scope.launch { runCatching { work().keepKeywords(magazine, offered, chosen) } }
    }

    fun dismiss(magazine: String, source: String) = set(magazine, source, DeskState.Idle)

    suspend fun record(magazine: String, source: String): HealthRecord =
        runCatching { work().record(magazine, source) }.getOrDefault(HealthRecord())

    private fun start(magazine: String, source: String, repairReason: String?) {
        val k = key(magazine, source)
        if (states.value[k] is DeskState.Working) return
        missing()?.let { set(magazine, source, DeskState.Missing(it)); return }
        set(magazine, source, DeskState.Working(repairReason != null))
        scope.launch {
            val next = try {
                val w = work()
                val outcome = if (repairReason == null) w.write(magazine, source) else w.repair(magazine, source, repairReason)
                when (outcome) {
                    is SetupOutcome.Ready -> DeskState.Ready(outcome.suggested)
                    is SetupOutcome.NotReady -> DeskState.NotReady(outcome.reason)
                    is SetupOutcome.Unavailable -> DeskState.Unavailable(outcome.reason)
                    SetupOutcome.Gone -> DeskState.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException.NotConfigured) {
                DeskState.Missing(missing() ?: MissingSetup.NO_CHOICE)
            } catch (e: AiException.KeyRefused) {
                DeskState.Failed(FailReason.KEY_REFUSED, e.message.orEmpty())
            } catch (e: AiException.RateLimited) {
                DeskState.Failed(FailReason.RATE_LIMITED, e.message.orEmpty())
            } catch (e: AiException.Unreachable) {
                DeskState.Failed(FailReason.UNREACHABLE, e.message.orEmpty())
            } catch (e: Exception) {
                DeskState.Failed(FailReason.OTHER, e.message ?: e::class.simpleName.orEmpty())
            }
            set(magazine, source, next)
        }
    }

    private fun set(magazine: String, source: String, state: DeskState) =
        states.update { it + (key(magazine, source) to state) }
}
