package dobra.collect.health

import dobra.collect.worker.SourceResult
import dobra.model.SourceHealth
import kotlinx.serialization.Serializable

/**
 * When a source changes state (PLAN-collectors, "Health"). [badRunsBeforeRepair] runs in a row that bring nothing where
 * there used to be records, or bring records with missing fields, send the source to "being repaired";
 * [refusalsBeforeUnavailable] refusals in a row (HTTP 403 or 429) make it "unavailable".
 */
data class HealthPolicy(val badRunsBeforeRepair: Int = 3, val refusalsBeforeUnavailable: Int = 2)

/**
 * What the app remembers of a source's recent runs, apart from the recipe (so counting runs never changes the
 * recipe's version). [note] is the last reason the reader is shown: why the collector is not ready, why the source
 * is being repaired or why it is unavailable.
 */
@Serializable
data class HealthRecord(
    val badRuns: Int = 0,
    val refusals: Int = 0,
    /** True once a run (or the acceptance run) brought well-formed records. */
    val hadRecords: Boolean = false,
    val note: String? = null,
)

/** Where [HealthRecord]s are kept, by magazine and source. */
interface HealthStore {
    suspend fun get(magazine: String, source: String): HealthRecord
    suspend fun put(magazine: String, source: String, record: HealthRecord)
}

class MemoryHealthStore : HealthStore {
    private val records = HashMap<String, HealthRecord>()
    override suspend fun get(magazine: String, source: String) = records["$magazine/$source"] ?: HealthRecord()
    override suspend fun put(magazine: String, source: String, record: HealthRecord) {
        records["$magazine/$source"] = record
    }
}

/** The state a run leads to: the source's health, what to remember and, when the state changed, why. */
data class HealthStep(val health: SourceHealth, val record: HealthRecord, val reason: String?)

/** The rules of "Health", as a pure function of the current state and one run. */
object HealthRules {
    /**
     * The step after [result], or null when the run says nothing about the collector: the source was not run (no
     * collector, unavailable) or the phone could not reach the network at all.
     */
    fun next(health: SourceHealth, record: HealthRecord, result: SourceResult, policy: HealthPolicy): HealthStep? {
        if (!result.ran || result.unreachable) return null
        val refusal = result.refusedStatus
        if (refusal != null) {
            val refusals = record.refusals + 1
            val reason = "the site refused automated reading (HTTP $refusal) in $refusals run(s) in a row"
            val next = if (refusals >= policy.refusalsBeforeUnavailable) SourceHealth.UNAVAILABLE else health
            return HealthStep(next, record.copy(refusals = refusals, note = reason), reason)
        }
        val wellFormed = (result.returned - result.malformed).coerceAtLeast(0)
        val bad = when {
            result.malformed > 0 -> "${result.malformed} record(s) came with missing or malformed fields" +
                (result.reason?.let { ": $it" } ?: "")
            record.hadRecords && wellFormed == 0 -> "the collector brought nothing where there used to be records" +
                (result.reason?.let { ": $it" } ?: "")
            else -> null
        }
        if (bad != null) {
            val badRuns = record.badRuns + 1
            val repair = badRuns >= policy.badRunsBeforeRepair && health == SourceHealth.WORKING
            val reason = if (repair) "$bad ($badRuns runs in a row)" else bad
            val next = if (repair) SourceHealth.REPAIRING else health
            return HealthStep(next, record.copy(badRuns = badRuns, refusals = 0, note = reason), reason)
        }
        if (wellFormed == 0) return HealthStep(health, record.copy(refusals = 0), null)
        // A good run: the collector works (again), whatever state it was in.
        return HealthStep(SourceHealth.WORKING, HealthRecord(hadRecords = true), null)
    }
}
