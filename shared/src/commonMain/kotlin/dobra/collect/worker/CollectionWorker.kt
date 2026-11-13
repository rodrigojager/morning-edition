package dobra.collect.worker

import dobra.collect.engine.CollectorEngine
import dobra.collect.engine.CollectorInput
import dobra.collect.engine.CollectorRun
import dobra.collect.engine.RawRecord
import dobra.model.CollectionLine
import dobra.model.CollectionState
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What the worker needs to remember between runs: where each source's collector stopped. */
interface CursorStore {
    suspend fun cursor(magazine: String, source: String): String?
    suspend fun setCursor(magazine: String, source: String, cursor: String?)
}

/** Keeps cursors in memory; tests and previews use it. */
class MemoryCursorStore : CursorStore {
    private val values = HashMap<String, String?>()
    override suspend fun cursor(magazine: String, source: String): String? = values["$magazine/$source"]
    override suspend fun setCursor(magazine: String, source: String, cursor: String?) {
        values["$magazine/$source"] = cursor
    }
}

/** The links already published in a magazine (the `sources` blocks of its editions). */
fun interface PublishedLinks {
    suspend fun of(magazine: String): Set<String>
}

/** A record that was already published in this magazine: kept apart as a candidate for an update. */
data class UpdateCandidate(val record: RawRecord, val source: String)

/**
 * What one source answered in one run (PLAN-collectors, "The worker's run").
 *
 * [returned] is what the script returned, [kept] the records that matched a keyword, [fresh] those of them that were
 * not published before; [candidates] are the others. [refusedStatus] is 403 or 429 when the site refused automated
 * reading; [reason] says why a source failed or only partly answered.
 */
data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val state: CollectionState,
    val returned: Int,
    val kept: Int,
    val perKeyword: Map<String, Int>,
    val fresh: List<RawRecord>,
    val candidates: List<RawRecord>,
    val reason: String? = null,
    val refusedStatus: Int? = null,
    val cursor: String? = null,
    /** Records the script returned that failed the record shape (missing or malformed fields). */
    val malformed: Int = 0,
    /** True when every fetch of the run failed in the transport itself (no network): the site said nothing. */
    val unreachable: Boolean = false,
    /** False when the source was not run at all (no collector yet, or unavailable). */
    val ran: Boolean = true,
) {
    fun toLine(): CollectionLine = CollectionLine(sourceName, kept, state, reason)
}

/** The whole run of a magazine: one result per source, in the order of the recipe. */
data class WorkerResult(val magazine: String, val results: List<SourceResult>) {
    /** The new records of every source, ready for the filter and rewrite job. */
    val records: List<CollectedRecord>
        get() = results.flatMap { r -> r.fresh.map { CollectedRecord(r.sourceId, r.sourceName, it) } }

    val candidates: List<UpdateCandidate>
        get() = results.flatMap { r -> r.candidates.map { UpdateCandidate(it, r.sourceName) } }

    val collection: List<CollectionLine> get() = results.map { it.toLine() }
}

data class CollectedRecord(val sourceId: String, val sourceName: String, val record: RawRecord)

/** Progress of a run: [done] of [total] sources finished; [current] is the one now running (null at the end). */
data class WorkerProgress(val done: Int, val total: Int, val current: String?)

/** The entry the screens use; [CollectionWorker] is the real one. */
interface MagazineCollector {
    suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit = {}): WorkerResult
}

/**
 * Runs every working collector of a magazine, one source after the other, and records what each answered. A failing
 * source never stops the others. A source is asked once: a refusal (403, 429) is recorded as it is, without a retry.
 * No AI is called here.
 */
class CollectionWorker(
    private val engine: CollectorEngine,
    private val cursors: CursorStore,
    private val published: PublishedLinks,
) : MagazineCollector {

    override suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit): WorkerResult {
        val already = published.of(magazine.id)
        val results = ArrayList<SourceResult>()
        val total = magazine.sources.size
        onProgress(WorkerProgress(0, total, magazine.sources.firstOrNull()?.name))
        magazine.sources.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            results += collectSource(magazine, source, already)
            onProgress(WorkerProgress(index + 1, total, magazine.sources.getOrNull(index + 1)?.name))
        }
        return WorkerResult(magazine.id, results)
    }

    private suspend fun collectSource(magazine: Magazine, source: Source, already: Set<String>): SourceResult {
        val script = source.collector?.script
        if (script == null) return skipped(source, "this source has no collector yet")
        if (source.health == SourceHealth.UNAVAILABLE) return skipped(source, "the site refuses automated reading; not asked")
        val cursor = cursors.cursor(magazine.id, source.id) ?: source.cursor
        val run = try {
            engine.run(script, CollectorInput(source.url, magazine.keywords, cursor))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return skipped(source, "the collector could not run: ${e.message ?: e::class.simpleName}").copy(ran = true)
        }
        val refusal = run.fetches.firstOrNull { it.status == 403 || it.status == 429 }?.status
        val (fresh, old) = run.kept.partition { it.link !in already }
        val answeredNothing = run.state != CollectionState.FAILED && run.kept.isEmpty() && refusal != null
        val state = if (answeredNothing) CollectionState.FAILED else run.state
        // A failed run keeps the cursor it had, so the next run starts where this one did.
        if (run.state != CollectionState.FAILED) cursors.setCursor(magazine.id, source.id, run.cursor)
        return SourceResult(
            sourceId = source.id,
            sourceName = source.name,
            state = state,
            returned = run.returned,
            kept = run.kept.size,
            perKeyword = run.perKeyword,
            fresh = fresh,
            candidates = old,
            reason = reasonOf(run, refusal),
            refusedStatus = refusal,
            cursor = run.cursor,
            malformed = run.refused.size,
            unreachable = run.fetches.isNotEmpty() && run.fetches.all { it.status == 0 && it.note?.startsWith("refused") != true },
        )
    }

    private fun reasonOf(run: CollectorRun, refusal: Int?): String? {
        val parts = ArrayList<String>()
        if (refusal != null) parts += "refused by the site (HTTP $refusal)"
        run.failure?.let { parts += it.reason }
        if (run.failure == null && run.refused.isNotEmpty()) {
            parts += "${run.refused.size} records did not have the expected shape: ${run.refused.first().reason}"
        }
        val other = run.fetches.filter { it.status !in 200..399 && it.status != 403 && it.status != 429 }
        if (run.failure == null && other.isNotEmpty()) parts += "HTTP ${other.first().status} for ${other.first().url}"
        return parts.joinToString("; ").ifEmpty { null }
    }

    private fun skipped(source: Source, reason: String) = SourceResult(
        sourceId = source.id,
        sourceName = source.name,
        state = CollectionState.FAILED,
        returned = 0,
        kept = 0,
        perKeyword = emptyMap(),
        fresh = emptyList(),
        candidates = emptyList(),
        reason = reason,
        ran = false,
    )
}
