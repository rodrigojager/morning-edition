package dobra.seed

import dobra.collect.engine.CollectorEngine
import dobra.collect.engine.CollectorInput
import dobra.collect.engine.FetchResponse
import dobra.collect.engine.SavedPages
import dobra.collect.worker.CollectionWorker
import dobra.collect.worker.MemoryCursorStore
import dobra.collect.worker.PublishedLinks
import dobra.model.CollectionState
import dobra.model.SourceHealth
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bundled scripts run in QuickJS over bounded public responses saved on 2026-11-13. */
class StarterCollectorsTest {
    private val magazine = Seed.magazine(File("seed/magazine.json").readText())
    private fun page(name: String) = FetchResponse(200, File("fixtures/starter/$name").readText())
    private val pages = mapOf(
        "https://github.com/trending" to page("github-trending.html"),
        "https://news.ycombinator.com/rss" to page("hacker-news.xml"),
        "https://lobste.rs/hottest.json" to page("lobsters.json"),
        "https://dev.to/api/articles?top=7&per_page=40" to page("dev.json"),
        "https://www.reddit.com/r/programming/.rss" to page("reddit-rss.xml"),
        "https://www.reddit.com/r/selfhosted/.rss" to FetchResponse(429, "Too Many Requests"),
        "https://www.reddit.com/r/LocalLLaMA/.rss" to FetchResponse(429, "Too Many Requests"),
        "https://api.hackertab.dev/engine/feeds?source=reddit" to page("reddit-via-hackertab.json"),
        "https://www.producthunt.com/feed" to page("product-hunt.xml"),
        "https://discuss.python.org/top.rss?period=weekly" to page("foruns.xml"),
        "https://devblogs.microsoft.com/dotnet/feed/" to page("net-blog.xml"),
        "https://apiiro.com/blog" to FetchResponse(200, "<html><body><div id=\"root\"></div></body></html>"),
    )

    @Test
    fun everyStarterSourceHasAScriptAndNineProduceValidRecords() = runBlocking {
        val engine = CollectorEngine(SavedPages(pages))
        for (source in magazine.sources) {
            val script = assertNotNull(source.collector, source.id).script
            val input = CollectorInput(source.url, emptyList())
            val run = engine.run(script, input)
            if (source.id == "blog-da-apiiro") {
                assertEquals(SourceHealth.REPAIRING, source.health)
                assertEquals(CollectionState.FAILED, run.state)
                assertTrue(run.failure!!.reason.contains("no readable article links"))
            } else {
                assertEquals(SourceHealth.WORKING, source.health)
                assertTrue(run.state != CollectionState.FAILED, "${source.id}: ${run.failure}")
                assertEquals(2, run.wellFormed.size, "${source.id}: ${run.refused}")
                assertEquals(run.wellFormed, run.kept, "${source.id}: an empty keyword list must allow AI curation")
                assertTrue(run.refused.isEmpty(), source.id)
                assertEquals(run.wellFormed, engine.run(script, input).wellFormed, source.id)
            }
        }
    }

    @Test
    fun theStarterRecipeDeliversRecordsToCurationWithoutRequiringKeywords() = runBlocking {
        assertTrue(magazine.keywords.isEmpty())
        val worker = CollectionWorker(CollectorEngine(SavedPages(pages)), MemoryCursorStore(), PublishedLinks { emptySet() })
        val result = worker.collect(magazine)
        assertEquals(18, result.records.size)
        assertEquals(9, result.records.map { it.sourceId }.distinct().size)
        assertEquals(CollectionState.FAILED, result.results.single { it.sourceId == "blog-da-apiiro" }.state)
    }

    @Test
    fun feedsRefuseAnHtmlErrorPageAndKeepPrimaryRedditArticleLinks() = runBlocking {
        val hn = magazine.sources.first { it.id == "hacker-news" }
        val bad = CollectorEngine(SavedPages(pages + ("https://news.ycombinator.com/rss" to FetchResponse(200, "<html>Unavailable</html>"))))
        assertEquals(CollectionState.FAILED, bad.run(hn.collector!!.script, CollectorInput(hn.url, emptyList())).state)
        val reddit = magazine.sources.first { it.id == "reddit-rss" }
        val run = CollectorEngine(SavedPages(pages)).run(reddit.collector!!.script, CollectorInput(reddit.url, emptyList()))
        assertTrue(run.wellFormed.first().link.startsWith("https://stripe.dev/"))
        assertEquals(3, run.fetches.size)
        assertEquals(2, run.fetches.count { it.status == 429 })
    }
}
