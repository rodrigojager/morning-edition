package dobra.ai.collector

import dobra.collect.engine.CollectorEngine
import dobra.collect.engine.CollectorInput
import dobra.collect.engine.CollectorLimits
import dobra.collect.engine.CollectorRun
import dobra.collect.engine.FetchTransport
import dobra.collect.engine.RawRecord

/**
 * The result of an acceptance run. [runs] are the two runs over the saved copy; [faults] is empty when the collector
 * is accepted, and otherwise says what was wrong, in words meant to go back to the AI. [fixture] is the copy the runs
 * read (with any page the warm-up run added).
 */
data class AcceptanceResult(
    val fixture: Fixture,
    val runs: List<CollectorRun>,
    val faults: List<String>,
) {
    val passed: Boolean get() = faults.isEmpty()

    /** The well-formed records of the accepted runs (empty when refused). */
    val records: List<RawRecord> get() = if (passed) runs.first().wellFormed else emptyList()
}

/**
 * The acceptance test of a collector (PLAN-collectors, "Adding a source", step 4): the script runs in the collector
 * engine, with its sandbox and limits, against the saved copy of its page, twice. It is accepted only if both runs
 * return records with every required field and give the same result.
 */
class Acceptance(private val limits: CollectorLimits = CollectorLimits()) {

    /**
     * Checks [script] against [fixture]. With [live], one warm-up run first fetches the site's pages the copy lacks and
     * adds them to the copy; the two acceptance runs then read the copy only.
     */
    suspend fun check(
        script: String,
        fixture: Fixture,
        keywords: List<String>,
        live: FetchTransport? = null,
    ): AcceptanceResult {
        val input = CollectorInput(fixture.url, keywords, cursor = null)
        var copy = fixture
        if (live != null) {
            val filling = FillingPages(fixture, live)
            CollectorEngine(filling, limits).run(script, input)
            copy = filling.fixture
        }
        val engine = CollectorEngine(copy.transport(), limits)
        val first = engine.run(script, input)
        val firstFaults = faultsOf(first, copy)
        if (firstFaults.isNotEmpty()) return AcceptanceResult(copy, listOf(first), firstFaults)
        val second = engine.run(script, input)
        val secondFaults = faultsOf(second, copy)
        if (secondFaults.isNotEmpty()) return AcceptanceResult(copy, listOf(first, second), secondFaults)
        val same = first.wellFormed == second.wellFormed && first.cursor == second.cursor
        val faults = if (same) emptyList() else listOf(
            "two runs over the same saved copy gave different results; the result must depend only on the page and " +
                "the input (no clock, no randomness, no state between runs)",
        )
        return AcceptanceResult(copy, listOf(first, second), faults)
    }

    private fun faultsOf(run: CollectorRun, copy: Fixture): List<String> {
        run.failure?.let { return listOf("the run failed (${it.kind.name.lowercase()}): ${it.reason}") }
        val faults = ArrayList<String>()
        if (run.refused.isNotEmpty()) {
            faults += "${run.refused.size} of ${run.returned} records do not have the record shape: " +
                run.refused.take(MAX_LISTED).joinToString("; ") { "record ${it.index}: ${it.reason}" }
        }
        val missing = run.fetches.filter { it.status !in 200..399 }
        if (missing.isNotEmpty()) {
            faults += "these fetches did not answer from the saved copy: " +
                missing.take(MAX_LISTED).joinToString("; ") { "${it.url} (HTTP ${it.status})" } +
                ". The saved copy has only: " + copy.pages.joinToString(", ") { it.url }
        }
        if (run.wellFormed.isEmpty()) {
            faults += "collect(input) returned no well-formed records from the saved copy of the page; " +
                "the collector must find the page's items"
        }
        return faults
    }

    private companion object {
        const val MAX_LISTED = 5
    }
}
