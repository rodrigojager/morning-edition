package dobra.app.library

import dobra.ai.provider.AiException
import dobra.app.ai.AiSetup
import dobra.app.sources.HttpFetchTransport
import dobra.collect.engine.FetchTransport
import dobra.collect.worker.MagazineCollector
import dobra.make.EditionMaker
import dobra.make.EditionPipeline
import dobra.make.EditionReformatter
import dobra.make.MakeProgress
import dobra.make.MakeResult
import dobra.make.MakeStage
import dobra.make.MissingSetup
import dobra.make.PageReader
import dobra.make.PageText
import dobra.make.ReformatResult
import dobra.make.RewriteSetup
import dobra.model.Magazine
import dobra.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Why a run stopped with no result. */
enum class FailReason { KEY_REFUSED, RATE_LIMITED, UNREACHABLE, OTHER }

/** Where one run of a magazine stands, as the screens draw it. */
sealed interface RunState<out R> {
    data object Idle : RunState<Nothing>
    data class Running(val progress: MakeProgress) : RunState<Nothing>
    data class Done<R>(val result: R) : RunState<R>
    data class Missing(val what: MissingSetup) : RunState<Nothing>
    data class Failed(val reason: FailReason, val detail: String) : RunState<Nothing>
}

/**
 * Runs "Gerar edição agora" and the format editor's rewrite in the app's own scope, so a run goes on while the reader
 * moves between tabs; the screens follow its state by magazine. Before any call it checks that the rewriting job has
 * a provider, a model and a key, and says what is missing otherwise.
 */
class MakeRunner(
    private val scope: CoroutineScope,
    private val store: suspend () -> Store,
    private val ai: AiSetup,
    private val collector: MagazineCollector,
    private val pages: PageReader? = null,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private val makes = MutableStateFlow<Map<String, RunState<MakeResult>>>(emptyMap())
    private val reformats = MutableStateFlow<Map<String, RunState<ReformatResult>>>(emptyMap())

    fun makeState(magazine: String): Flow<RunState<MakeResult>> =
        makes.map { it[magazine] ?: RunState.Idle }.distinctUntilChanged()

    fun reformatState(magazine: String): Flow<RunState<ReformatResult>> =
        reformats.map { it[magazine] ?: RunState.Idle }.distinctUntilChanged()

    /** Collects, then filters and rewrites, then stores the edition. Does nothing while a run of [magazine] is on. */
    fun make(magazine: Magazine) {
        if (!begin(magazine)) return
        scope.launch { execute(magazine) {} }
    }

    /**
     * The same run as [make], awaited: the background job of a magazine's cadence calls this, so an edition made on
     * schedule is made exactly as one asked for with "Gerar edição agora". Returns [RunState.Idle] when a run of the
     * magazine is already on.
     */
    suspend fun run(magazine: Magazine, onProgress: (MakeProgress) -> Unit = {}): RunState<MakeResult> {
        if (makes.value[magazine.id] is RunState.Running) return RunState.Idle
        if (!begin(magazine)) return makes.value[magazine.id] ?: RunState.Idle
        return execute(magazine, onProgress)
    }

    /** Says what is missing, or marks the run as started; false when nothing may run. */
    private fun begin(magazine: Magazine): Boolean {
        val id = magazine.id
        if (makes.value[id] is RunState.Running) return false
        RewriteSetup.missing(ai.registry)?.let { missing -> makes.update { it + (id to RunState.Missing(missing)) }; return false }
        val first = MakeProgress(MakeStage.COLLECTING, 0, magazine.sources.size, magazine.sources.firstOrNull()?.name)
        makes.update { it + (id to RunState.Running(first)) }
        return true
    }

    private suspend fun execute(magazine: Magazine, onProgress: (MakeProgress) -> Unit): RunState<MakeResult> {
        val id = magazine.id
        val next: RunState<MakeResult> = guarded {
            val s = store()
            val maker = EditionMaker(ai.gateway, ai.ledger, s.editions, s.marks, pages, today)
            RunState.Done(EditionPipeline(collector, maker).run(magazine) { p ->
                makes.update { it + (id to RunState.Running(p)) }
                onProgress(p)
            })
        }
        makes.update { it + (id to next) }
        return next
    }

    /** Asks the AI to rewrite the items of [editionKey] that are outside the current format of [magazine]. */
    fun reformat(magazine: Magazine, editionKey: String) {
        val id = magazine.id
        if (reformats.value[id] is RunState.Running) return
        RewriteSetup.missing(ai.registry)?.let { missing -> reformats.update { it + (id to RunState.Missing(missing)) }; return }
        reformats.update { it + (id to RunState.Running(MakeProgress(MakeStage.WRITING, 0, 0))) }
        scope.launch {
            val next: RunState<ReformatResult> = guarded {
                val reformatter = EditionReformatter(ai.gateway, ai.ledger, store().editions)
                RunState.Done(reformatter.reformat(magazine, editionKey) { p -> reformats.update { it + (id to RunState.Running(p)) } })
            }
            reformats.update { it + (id to next) }
        }
    }

    private suspend fun <R> guarded(block: suspend () -> RunState<R>): RunState<R> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiException.NotConfigured) {
        RunState.Missing(RewriteSetup.missing(ai.registry) ?: MissingSetup.NO_CHOICE)
    } catch (e: AiException.KeyRefused) {
        RunState.Failed(FailReason.KEY_REFUSED, e.message.orEmpty())
    } catch (e: AiException.RateLimited) {
        RunState.Failed(FailReason.RATE_LIMITED, e.message.orEmpty())
    } catch (e: AiException.Unreachable) {
        RunState.Failed(FailReason.UNREACHABLE, e.message.orEmpty())
    } catch (e: Exception) {
        RunState.Failed(FailReason.OTHER, e.message ?: e::class.simpleName.orEmpty())
    }
}

/** Reads a record's primary page over the network, as plain text, for the writing step. */
class HttpPageReader(private val transport: FetchTransport = HttpFetchTransport()) : PageReader {
    override suspend fun read(url: String): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val response = transport.get(url)
        if (response.status !in 200..299) return null
        val type = response.contentType.orEmpty().lowercase()
        if (type.isNotEmpty() && listOf("html", "text", "xml", "json").none { it in type }) return null
        return PageText.fromHtml(response.body).ifBlank { null }
    }
}
