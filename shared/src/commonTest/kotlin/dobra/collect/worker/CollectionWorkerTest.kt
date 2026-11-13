package dobra.collect.worker

import dobra.collect.engine.CollectorEngine
import dobra.model.CollectorInfo
import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import dobra.collect.engine.SavedPages
import dobra.model.CollectionState
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The worker over saved pages only; no test touches the network. */
class CollectionWorkerTest {
    private val aUrl = "https://a.example.org/items.json"
    private val bUrl = "https://b.example.org/items.json"
    private val cUrl = "https://c.example.org/items.json"

    private val aPage = FetchResponse(
        200,
        """[{"t":"Falha de segurança","u":"https://a.example.org/1","x":"Atualize."},
            {"t":"Receita de bolo","u":"https://a.example.org/2","x":"Farinha."},
            {"t":"Kotlin 2.4","u":"https://a.example.org/3","x":"Mais SEGURANCA."}]""",
        "application/json",
    )
    private val bPage = FetchResponse(
        200,
        """[{"t":"Kotlin multiplataforma","u":"https://b.example.org/1","x":"Compartilhe código."}]""",
        "application/json",
    )

    /** Fetches its source, remembers the last link as the cursor, and skips what the cursor already passed. */
    private val script = """
        async function collect(input: { url: string; keywords: string[]; cursor: string | null }) {
          const response = await fetch(input.url);
          if (!response.ok) throw new Error("the site answered " + response.status);
          const items = await response.json();
          let from = 0;
          if (input.cursor) {
            const seen = items.findIndex((i: any) => i.u === input.cursor);
            from = seen < 0 ? 0 : seen + 1;
          }
          const records = items.slice(from).map((i: any) => ({ title: i.t, link: i.u, date: "", rawText: i.x }));
          return { records, cursor: items.length ? items[items.length - 1].u : input.cursor };
        }
    """.trimIndent()

    private fun source(id: String, url: String, collector: String? = script, health: SourceHealth = SourceHealth.WORKING) =
        Source(
            id = id,
            name = id.uppercase(),
            url = url,
            collector = collector?.let { CollectorInfo(it, 1, "2026-11-13", "test") },
            health = health,
        )

    private val magazine = Magazine(
        id = "m",
        name = "M",
        keywords = listOf("segurança", "kotlin"),
        sources = listOf(source("a", aUrl), source("b", bUrl), source("c", cUrl)),
    )

    private class Counting(private val inner: FetchTransport) : FetchTransport {
        val asked = ArrayList<String>()
        override suspend fun get(url: String): FetchResponse {
            asked += url
            return inner.get(url)
        }
    }

    private fun worker(
        pages: Map<String, FetchResponse>,
        cursors: CursorStore = MemoryCursorStore(),
        published: Set<String> = emptySet(),
        transport: Counting = Counting(SavedPages(pages)),
    ) = CollectionWorker(CollectorEngine(transport), cursors, { published })

    @Test
    fun oneFailingSourceDoesNotStopTheOthers() = runTest {
        val progress = ArrayList<WorkerProgress>()
        val result = worker(mapOf(aUrl to aPage, bUrl to bPage, cUrl to FetchResponse(500, "oops")))
            .collect(magazine) { progress += it }

        assertEquals(listOf("a", "b", "c"), result.results.map { it.sourceId })
        val (a, b, c) = result.results
        assertEquals(CollectionState.ANSWERED, a.state)
        assertEquals(3, a.returned)
        assertEquals(2, a.kept)
        assertEquals(mapOf("segurança" to 2, "kotlin" to 1), a.perKeyword)
        assertNull(a.reason)
        assertEquals(CollectionState.ANSWERED, b.state)
        assertEquals(1, b.kept)
        assertEquals(CollectionState.FAILED, c.state)
        assertEquals(0, c.kept)
        assertTrue(c.reason!!.contains("500"), c.reason)
        // The raw records of the working sources are handed on; the failed one adds none.
        assertEquals(3, result.records.size)
        assertEquals(listOf("A", "A", "B"), result.records.map { it.sourceName })
        assertEquals(listOf(CollectionState.ANSWERED, CollectionState.ANSWERED, CollectionState.FAILED), result.collection.map { it.state })
        assertEquals(WorkerProgress(0, 3, "A"), progress.first())
        assertEquals(WorkerProgress(3, 3, null), progress.last())
    }

    @Test
    fun linksAlreadyPublishedAreSetAsideAsUpdateCandidates() = runTest {
        val result = worker(
            mapOf(aUrl to aPage, bUrl to bPage, cUrl to bPage),
            published = setOf("https://a.example.org/1"),
        ).collect(magazine)

        val a = result.results.first()
        assertEquals(2, a.kept)
        assertEquals(listOf("https://a.example.org/3"), a.fresh.map { it.link })
        assertEquals(listOf("https://a.example.org/1"), a.candidates.map { it.link })
        assertEquals(listOf("https://a.example.org/1"), result.candidates.map { it.record.link })
        assertTrue(result.records.none { it.record.link == "https://a.example.org/1" })
    }

    @Test
    fun theCursorIsStoredAndUsedByTheNextRun() = runTest {
        val cursors = MemoryCursorStore()
        val pages = mapOf(aUrl to aPage, bUrl to bPage, cUrl to bPage)
        val first = worker(pages, cursors).collect(magazine)
        assertEquals("https://a.example.org/3", cursors.cursor("m", "a"))
        assertEquals(3, first.results.first().returned)

        val second = worker(pages, cursors).collect(magazine)
        assertEquals(0, second.results.first().returned)
        assertEquals("https://a.example.org/3", second.results.first().cursor)
    }

    @Test
    fun aRefusalIsRecordedWithItsReasonAndAskedOnlyOnce() = runTest {
        val transport = Counting(SavedPages(mapOf(aUrl to aPage, bUrl to FetchResponse(429, "slow down"), cUrl to FetchResponse(403, "no"))))
        val cursors = MemoryCursorStore()
        cursors.setCursor("m", "c", "kept")
        val result = worker(emptyMap(), cursors, transport = transport).collect(magazine)

        val (_, b, c) = result.results
        assertEquals(429, b.refusedStatus)
        assertEquals(CollectionState.FAILED, b.state)
        assertTrue(b.reason!!.contains("refused by the site (HTTP 429)"), b.reason)
        assertEquals(403, c.refusedStatus)
        assertEquals(CollectionState.FAILED, c.state)
        assertTrue(c.reason!!.contains("HTTP 403"), c.reason)
        // One request per source, the same address, no second try and no disguise.
        assertEquals(listOf(aUrl, bUrl, cUrl), transport.asked)
        // A refused run leaves the cursor where it was.
        assertEquals("kept", cursors.cursor("m", "c"))
    }

    @Test
    fun sourcesWithoutAWorkingCollectorAreListedAsFailedAndNotAsked() = runTest {
        val transport = Counting(SavedPages(mapOf(aUrl to aPage)))
        val m = magazine.copy(
            sources = listOf(
                source("a", aUrl),
                source("n", "https://n.example.org/", collector = null),
                source("u", "https://u.example.org/", health = SourceHealth.UNAVAILABLE),
            ),
        )
        val result = worker(emptyMap(), transport = transport).collect(m)
        assertEquals(listOf(aUrl), transport.asked)
        assertEquals(CollectionState.ANSWERED, result.results[0].state)
        assertEquals(CollectionState.FAILED, result.results[1].state)
        assertTrue(result.results[1].reason!!.contains("no collector"))
        assertEquals(CollectionState.FAILED, result.results[2].state)
    }
}
