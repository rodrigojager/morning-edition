package dobra.collect.health

import dobra.ai.collector.Acceptance
import dobra.ai.collector.CollectorKit
import dobra.ai.collector.CollectorKit.ARTICLES
import dobra.ai.collector.CollectorKit.LIST_ITEMS
import dobra.ai.collector.CollectorKit.NO_LINKS
import dobra.ai.collector.CollectorKit.PAGE
import dobra.ai.collector.CollectorKit.REDESIGNED
import dobra.ai.collector.CollectorKit.URL
import dobra.ai.collector.CollectorKit.answer
import dobra.ai.collector.CollectorWriter
import dobra.ai.collector.MemoryFixtureStore
import dobra.ai.collector.SetupOutcome
import dobra.ai.collector.SourceSetup
import dobra.collect.engine.CollectorEngine
import dobra.collect.engine.CollectorLimits
import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import dobra.collect.worker.CollectionWorker
import dobra.collect.worker.MemoryCursorStore
import dobra.collect.worker.PublishedLinks
import dobra.collect.worker.SourceResult
import dobra.collect.worker.WorkerResult
import dobra.model.CollectionState
import dobra.model.CollectorInfo
import dobra.model.Source
import dobra.model.SourceHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The health of a source (PLAN-collectors, "Health"): working, being repaired, unavailable. */
class HealthTest {
    private val working = Source(
        "noticias", "Notícias", URL,
        collector = CollectorInfo(ARTICLES, 1, "2026-11-01T07:00:00Z", "model-collectors"),
        fixture = "tec/noticias/1",
        health = SourceHealth.WORKING,
    )

    private fun result(
        returned: Int,
        malformed: Int = 0,
        refused: Int? = null,
        unreachable: Boolean = false,
        state: CollectionState = CollectionState.ANSWERED,
    ) = SourceResult(
        "noticias", "Notícias", state, returned, returned - malformed, emptyMap(), emptyList(), emptyList(),
        reason = if (refused != null) "refused by the site (HTTP $refused)" else null,
        refusedStatus = refused, malformed = malformed, unreachable = unreachable,
    )

    private class Books(source: Source, private var seed: HealthRecord? = HealthRecord(hadRecords = true)) {
        val book = MemorySourceBook(CollectorKit.magazine(source))
        val records = MemoryHealthStore()
        val monitor = HealthMonitor(book, records, HealthPolicy(badRunsBeforeRepair = 3, refusalsBeforeUnavailable = 2))

        suspend fun run(r: SourceResult): List<HealthChange> {
            seed?.let { records.put("tec", "noticias", it); seed = null }
            return monitor.observe(book.magazine("tec")!!, WorkerResult("tec", listOf(r)))
        }

        val health: SourceHealth get() = book.source("tec", "noticias").health
    }

    @Test
    fun runsWithNothingWhereThereUsedToBeRecordsSendTheSourceToRepairAtTheConfiguredCount() = runTest {
        val b = Books(working)
        assertTrue(b.run(result(0)).isEmpty())
        assertTrue(b.run(result(0, state = CollectionState.FAILED)).isEmpty())
        assertEquals(SourceHealth.WORKING, b.health)
        val change = b.run(result(0)).single()
        assertEquals(SourceHealth.WORKING, change.from)
        assertEquals(SourceHealth.REPAIRING, change.to)
        assertTrue("nothing where there used to be records" in change.reason!!, change.reason)
        assertEquals(SourceHealth.REPAIRING, b.health)
        assertEquals(1, b.book.saves, "only the change of state touches the recipe")
    }

    @Test
    fun runsWithMalformedRecordsSendTheSourceToRepairToo() = runTest {
        val b = Books(working)
        repeat(2) { assertTrue(b.run(result(5, malformed = 2, state = CollectionState.PARTIAL)).isEmpty()) }
        val change = b.run(result(5, malformed = 5, state = CollectionState.PARTIAL)).single()
        assertEquals(SourceHealth.REPAIRING, change.to)
        assertTrue("missing or malformed fields" in change.reason!!)
    }

    @Test
    fun aGoodRunInBetweenStartsTheCountAgain() = runTest {
        val b = Books(working)
        b.run(result(0))
        b.run(result(0))
        b.run(result(4))
        b.run(result(0))
        b.run(result(0))
        assertEquals(SourceHealth.WORKING, b.health)
        assertEquals(2, b.records.get("tec", "noticias").badRuns)
    }

    @Test
    fun aNewSourceThatNeverHadRecordsIsNotRepairedForBeingEmpty() = runTest {
        val b = Books(working, HealthRecord(hadRecords = false))
        repeat(5) { b.run(result(0)) }
        assertEquals(SourceHealth.WORKING, b.health)
    }

    @Test
    fun aSiteThatRefusesAutomatedReadingBecomesUnavailableAndIsNotAskedAgain() = runTest {
        val b = Books(working)
        assertTrue(b.run(result(0, refused = 429, state = CollectionState.FAILED)).isEmpty())
        val change = b.run(result(0, refused = 403, state = CollectionState.FAILED)).single()
        assertEquals(SourceHealth.UNAVAILABLE, change.to)
        assertTrue("HTTP 403" in change.reason!!)
        assertEquals(SourceHealth.UNAVAILABLE, b.health)

        // The real worker no longer asks it: nothing is fetched and the state stays.
        var fetched = 0
        val worker = CollectionWorker(
            CollectorEngine(FetchTransport { fetched++; FetchResponse(200, PAGE) }), MemoryCursorStore(), PublishedLinks { emptySet() },
        )
        val watch = HealthWatch(worker, b.monitor)
        val run = watch.collect(b.book.magazine("tec")!!)
        assertEquals(0, fetched)
        assertTrue("not asked" in run.results.single().reason!!)
        assertEquals(SourceHealth.UNAVAILABLE, b.health)
    }

    @Test
    fun withoutNetworkNothingChanges() = runTest {
        val b = Books(working)
        repeat(5) { assertTrue(b.run(result(0, unreachable = true, state = CollectionState.FAILED)).isEmpty()) }
        assertEquals(SourceHealth.WORKING, b.health)
        assertEquals(0, b.records.get("tec", "noticias").badRuns)
    }

    /** One site whose page the test changes, served to the worker and to the page copy alike. */
    private class Site(var page: String) : FetchTransport {
        override suspend fun get(url: String) =
            if (url == URL) FetchResponse(200, page, "text/html", URL) else FetchResponse(404, "", null, url)
    }

    private class Lifecycle(vararg answers: String, attempts: Int) {
        val site = Site(PAGE)
        val ai = CollectorKit.ai(*answers)
        val book = MemorySourceBook(CollectorKit.magazine())
        val records = MemoryHealthStore()
        val fixtures = MemoryFixtureStore()
        val setup = SourceSetup(
            CollectorWriter(ai.gateway, Acceptance(CollectorLimits(timeMillis = 10_000)), attempts),
            book, fixtures, records, site, now = { "2026-11-20T07:00:00Z" }, model = { "model-collectors" }, warmUp = false,
        )
        val repairs = ArrayList<SetupOutcome>()
        val watch = HealthWatch(
            CollectionWorker(CollectorEngine(site, CollectorLimits(timeMillis = 10_000)), MemoryCursorStore(), PublishedLinks { emptySet() }),
            HealthMonitor(book, records, HealthPolicy(badRunsBeforeRepair = 3)),
            onRepair = { m, s, reason -> repairs += setup.repair(m, s, reason) },
        )

        suspend fun collect() = watch.collect(book.magazine("tec")!!)
        val source: Source get() = book.source("tec", "noticias")
    }

    @Test
    fun aBrokenSourceIsRepairedWithAFreshCopyAndTheAdaptedCollectorReplacesTheOldOneOnlyAfterPassing() = runTest {
        val l = Lifecycle(answer(ARTICLES), answer(NO_LINKS), answer(LIST_ITEMS), attempts = 2)
        assertIs<SetupOutcome.Ready>(l.setup.write("tec", "noticias"))
        assertEquals(3, l.collect().results.single().returned)

        // The site is redesigned: the old collector brings nothing.
        l.site.page = REDESIGNED
        l.collect()
        l.collect()
        assertEquals(SourceHealth.WORKING, l.source.health)
        assertTrue(l.repairs.isEmpty())
        l.collect()

        // The third run sent it to repair; the repair saved a fresh copy and asked the AI with the old script and why.
        val repaired = assertIs<SetupOutcome.Ready>(l.repairs.single())
        val prompts = l.ai.prompts
        assertEquals(3, prompts.size)
        assertTrue(REDESIGNED in prompts[1] && ARTICLES in prompts[1], "fresh copy and old script")
        assertTrue("nothing where there used to be records" in prompts[1])
        // The first adaptation failed the acceptance run and went back with the error; the second passed.
        assertTrue("# The validator refused it" in prompts[2] && "returned no well-formed records" in prompts[2], prompts[2])
        assertEquals(LIST_ITEMS, l.source.collector?.script)
        assertEquals(2, l.source.collector?.version)
        assertEquals(SourceHealth.WORKING, l.source.health)
        assertEquals("tec/noticias/2", l.source.fixture)
        assertEquals(REDESIGNED, l.fixtures.get("tec/noticias/2")?.main?.body)
        assertNull(l.fixtures.get("tec/noticias/1"), "the copy of the replaced collector is gone")
        assertEquals(repaired.source, l.source)

        // The adapted collector reads the new page.
        assertEquals(2, l.collect().results.single().returned)
        assertEquals(SourceHealth.WORKING, l.source.health)
    }

    @Test
    fun anAdaptationThatNeverPassesLeavesTheOldCollectorInPlace() = runTest {
        val l = Lifecycle(answer(ARTICLES), answer(NO_LINKS), answer(NO_LINKS), attempts = 2)
        l.setup.write("tec", "noticias")
        l.collect()
        l.site.page = REDESIGNED
        repeat(3) { l.collect() }

        val notReady = assertIs<SetupOutcome.NotReady>(l.repairs.single())
        assertEquals(SourceHealth.REPAIRING, l.source.health)
        assertEquals(ARTICLES, l.source.collector?.script)
        assertEquals(1, l.source.collector?.version)
        assertEquals("tec/noticias/1", l.source.fixture)
        assertNotNull(l.fixtures.get("tec/noticias/1"))
        assertEquals(REDESIGNED, l.fixtures.get("tec/noticias/2")?.main?.body, "the fresh copy is saved")
        assertEquals(notReady.reason, l.records.get("tec", "noticias").note)

        // Still being repaired: further empty runs do not start another repair on their own.
        l.collect()
        assertEquals(1, l.repairs.size)
    }
}
