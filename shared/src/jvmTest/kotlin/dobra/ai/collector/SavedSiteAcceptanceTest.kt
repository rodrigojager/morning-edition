package dobra.ai.collector

import dobra.collect.engine.CollectorLimits
import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import dobra.collect.health.MemoryHealthStore
import dobra.collect.health.MemorySourceBook
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adding a source whose collector reads two pages, with the saved pages of `shared/fixtures/collect` standing in for
 * the site and the hand-written `page-collector.ts` as the AI's recorded answer. The warm-up run adds the second page
 * to the saved copy; the two acceptance runs then read the copy only.
 */
class SavedSiteAcceptanceTest {
    private val fixtures = File("fixtures/collect")
    private fun fixture(name: String) = File(fixtures, name).readText()

    private val pageUrl = "https://www.example.org/noticias"

    /** The "site": two saved pages; every request is counted. */
    private class Site(private val pages: Map<String, String>) : FetchTransport {
        val asked = ArrayList<String>()
        override suspend fun get(url: String): FetchResponse {
            asked += url
            return pages[url]?.let { FetchResponse(200, it, "text/html", url) } ?: FetchResponse(404, "", null, url)
        }
    }

    @Test
    fun aCollectorThatReadsASecondPageIsAcceptedAgainstACopyThatHasIt() = runTest {
        val site = Site(mapOf(pageUrl to fixture("page-1.html"), "$pageUrl?pagina=2" to fixture("page-2.html")))
        val script = fixture("page-collector.ts")
        val ai = CollectorKit.ai(CollectorKit.answer(script, listOf("criptografia", "senhas")))
        val book = MemorySourceBook(
            Magazine(id = "tec", name = "Tecnologia", keywords = listOf("segurança"), sources = listOf(Source("ex", "Exemplo", pageUrl))),
        )
        val store = MemoryFixtureStore()
        val setup = SourceSetup(
            CollectorWriter(ai.gateway, Acceptance(CollectorLimits(timeMillis = 10_000))),
            book, store, MemoryHealthStore(), site, now = { "2026-11-13T07:00:00Z" },
        )

        val ready = assertIs<SetupOutcome.Ready>(setup.write("tec", "ex"))

        assertEquals(SourceHealth.WORKING, ready.source.health)
        assertEquals(script, ready.source.collector?.script)
        assertEquals(listOf("criptografia", "senhas"), ready.suggested)
        val copy = store.get("tec/ex/1")!!
        assertEquals(listOf(pageUrl, "$pageUrl?pagina=2"), copy.pages.map { it.url })
        assertEquals(fixture("page-2.html"), copy.page("$pageUrl?pagina=2")?.body)
        // The site was asked once for the page (the copy) and once for the second page (the warm-up run), never again.
        assertEquals(listOf(pageUrl, "$pageUrl?pagina=2"), site.asked)
        // Only the first page went to the AI.
        assertTrue(fixture("page-1.html") in ai.prompts.single())
    }
}
