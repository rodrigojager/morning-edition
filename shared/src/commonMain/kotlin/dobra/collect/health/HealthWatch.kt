package dobra.collect.health

import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.WorkerProgress
import dobra.collect.worker.WorkerResult
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth
import dobra.store.MagazineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads and changes the sources of the stored recipes, one source at a time. */
interface SourceBook {
    suspend fun magazine(id: String): Magazine?

    /** Replaces one source with [change] of it, in the latest stored recipe. Null when it is gone. */
    suspend fun update(magazineId: String, sourceId: String, change: (Source) -> Source): Source?

    /** Adds [words] to the recipe's keywords, leaving out those already there. Returns the stored recipe. */
    suspend fun addKeywords(magazineId: String, words: List<String>): Magazine?
}

/** The [SourceBook] of the store. One instance per store, so its changes never overlap. */
class StoreSourceBook(private val magazines: suspend () -> MagazineRepository) : SourceBook {
    private val mutex = Mutex()

    override suspend fun magazine(id: String): Magazine? = magazines().get(id)

    override suspend fun update(magazineId: String, sourceId: String, change: (Source) -> Source): Source? =
        mutex.withLock {
            val repo = magazines()
            val m = repo.get(magazineId) ?: return@withLock null
            val old = m.sources.firstOrNull { it.id == sourceId } ?: return@withLock null
            val next = change(old)
            if (next != old) repo.save(m.copy(sources = m.sources.map { if (it.id == sourceId) next else it }))
            next
        }

    override suspend fun addKeywords(magazineId: String, words: List<String>): Magazine? = mutex.withLock {
        val repo = magazines()
        val m = repo.get(magazineId) ?: return@withLock null
        val merged = mergeKeywords(m.keywords, words)
        if (merged == m.keywords) m else repo.save(m.copy(keywords = merged))
    }
}

/** [old] followed by those of [words] not already in it (ignoring case and surrounding spaces). */
fun mergeKeywords(old: List<String>, words: List<String>): List<String> {
    val seen = old.map { it.trim().lowercase() }.toMutableSet()
    val out = old.toMutableList()
    words.map { it.trim() }.filter { it.isNotEmpty() }.forEach { w -> if (seen.add(w.lowercase())) out += w }
    return out
}

/** A source whose health changed after a run. */
data class HealthChange(val sourceId: String, val from: SourceHealth, val to: SourceHealth, val reason: String?)

/** Applies [HealthRules] to every source of a worker's run and stores the result. */
class HealthMonitor(
    private val book: SourceBook,
    private val records: HealthStore,
    private val policy: HealthPolicy = HealthPolicy(),
) {
    suspend fun observe(magazine: Magazine, run: WorkerResult): List<HealthChange> {
        val changes = ArrayList<HealthChange>()
        for (result in run.results) {
            val source = magazine.sources.firstOrNull { it.id == result.sourceId } ?: continue
            if (source.collector == null) continue
            val record = records.get(magazine.id, source.id)
            val step = HealthRules.next(source.health, record, result, policy) ?: continue
            if (step.record != record) records.put(magazine.id, source.id, step.record)
            if (step.health != source.health) {
                book.update(magazine.id, source.id) { it.copy(health = step.health) }
                changes += HealthChange(source.id, source.health, step.health, step.reason)
            }
        }
        return changes
    }
}

/**
 * The worker with its health watched: after each run, every source's health is brought up to date and each source
 * that has just become "being repaired" is handed to [onRepair] (the app asks the AI to adapt its collector there).
 * Keeping the books never costs the reader the run: a failure there is left out of the result.
 */
class HealthWatch(
    private val inner: MagazineCollector,
    private val monitor: HealthMonitor,
    private val onRepair: suspend (magazineId: String, sourceId: String, reason: String) -> Unit = { _, _, _ -> },
) : MagazineCollector {
    override suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit): WorkerResult {
        val result = inner.collect(magazine, onProgress)
        val changes = try {
            monitor.observe(magazine, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        changes.filter { it.to == SourceHealth.REPAIRING }.forEach { onRepair(magazine.id, it.sourceId, it.reason.orEmpty()) }
        return result
    }
}
