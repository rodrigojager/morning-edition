package dobra.collect.engine

import dobra.model.CollectionState
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-written collectors against pages saved in `shared/fixtures/collect` (written for the tests, nothing personal):
 * a feed and a web page of two pages. Each runs twice and must give the same well-formed records.
 */
class SavedPageCollectorsTest {
    // Gradle runs jvmTest with the module directory (shared/) as the working directory.
    private val fixtures = File("fixtures/collect")

    private fun fixture(name: String) = File(fixtures, name).readText()

    private fun assertWellFormed(records: List<RawRecord>) {
        for (r in records) {
            assertTrue(r.title.isNotBlank() && r.title == r.title.trim(), "title of $r")
            assertTrue(r.link.startsWith("https://"), "link of $r")
            assertTrue(r.media.all { it.startsWith("https://") }, "media of $r")
        }
    }

    @Test
    fun theFeedCollectorGivesTheSameRecordsTwice() = runTest {
        val feedUrl = "https://news.example.org/feed.xml"
        val engine = CollectorEngine(
            SavedPages(mapOf(feedUrl to FetchResponse(200, fixture("feed.xml"), "application/rss+xml"))),
        )
        val script = fixture("feed-collector.ts")
        val input = CollectorInput(feedUrl, listOf("Segurança", "Kotlin"), null)

        val first = engine.run(script, input)
        val second = engine.run(script, input)

        assertNull(first.failure, first.failure?.reason)
        assertEquals(first, second)
        assertEquals(CollectionState.ANSWERED, first.state)
        assertEquals(5, first.wellFormed.size)
        assertTrue(first.refused.isEmpty())
        assertWellFormed(first.wellFormed)
        assertEquals(
            RawRecord(
                title = "Falha de segurança em biblioteca de compressão",
                link = "https://news.example.org/2026/11/falha-compressao",
                date = "Thu, 12 Nov 2026 09:30:00 GMT",
                rawText = "Uma falha permite ler memória fora do limite. A correção já saiu na versão 2.4.",
                media = listOf("https://cdn.example.org/img/compressao.png"),
            ),
            first.wellFormed[0],
        )
        assertEquals("A nova versão & o compilador K2 reduzem o tempo de \"build\" em projetos grandes.", first.wellFormed[1].rawText)
        // "Segurança" in a title, "seguranca" (no accent) in a text, "Kotlin" in a title; bread and rain are left out.
        assertEquals(
            listOf("falha-compressao", "kotlin-2-4", "testes-android"),
            first.kept.map { it.link.substringAfterLast('/') },
        )
        assertEquals(mapOf("Segurança" to 2, "Kotlin" to 1), first.perKeyword)
        assertEquals("https://news.example.org/2026/11/falha-compressao", first.cursor)

        // The next run starts from the cursor: only what is newer than the third item.
        val later = engine.run(script, input.copy(cursor = "https://news.example.org/2026/11/pao"))
        assertEquals(2, later.wellFormed.size)
    }

    @Test
    fun theWebPageCollectorGivesTheSameRecordsTwice() = runTest {
        val pageUrl = "https://www.example.org/noticias"
        val engine = CollectorEngine(
            SavedPages(
                mapOf(
                    pageUrl to FetchResponse(200, fixture("page-1.html"), "text/html"),
                    "$pageUrl?pagina=2" to FetchResponse(200, fixture("page-2.html"), "text/html"),
                ),
            ),
        )
        val script = fixture("page-collector.ts")
        val input = CollectorInput(pageUrl, listOf("segurança", "senhas", "Kotlin"), null)

        val first = engine.run(script, input)
        val second = engine.run(script, input)

        assertNull(first.failure, first.failure?.reason)
        assertEquals(first, second)
        assertEquals(CollectionState.ANSWERED, first.state)
        assertEquals(listOf(pageUrl, "$pageUrl?pagina=2"), first.fetches.map { it.url })
        assertEquals(5, first.wellFormed.size)
        assertWellFormed(first.wellFormed)
        assertEquals(
            RawRecord(
                title = "Criptografia pós-quântica entra no navegador",
                link = "https://www.example.org/noticias/criptografia-pos-quantica",
                date = "2026-11-12T08:00:00Z",
                rawText = "Navegadores passam a negociar chaves híbridas por padrão, com impacto pequeno no desempenho.",
                signal = 128.0,
                media = listOf("https://img.example.org/cripto.jpg"),
            ),
            first.wellFormed[0],
        )
        assertNull(first.wellFormed[4].signal, "an article without points has no signal")
        assertEquals(
            listOf("vazamento-senhas", "kotlin-multiplataforma"),
            first.kept.map { it.link.substringAfterLast('/') },
        )
        assertEquals(mapOf("segurança" to 1, "senhas" to 1, "Kotlin" to 1), first.perKeyword)
    }
}
