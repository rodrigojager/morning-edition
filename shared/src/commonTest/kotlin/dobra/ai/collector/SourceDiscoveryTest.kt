package dobra.ai.collector

import dobra.collect.engine.CollectorLimits
import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceDiscoveryTest {
    private val url = "https://www.example.org/news"
    private val feed = "https://www.example.org/feed.json"
    private val json = """{"items":[{"title":"A real tool","url":"https://example.org/tool","description":"Useful SDK"}]}"""
    private fun fixture(html: String) = Fixture(url, "2026-11-13", listOf(SavedPage(url, 200, html, "text/html")))
    private val script = """
        async function collect(input) {
          const data = await (await fetch('/feed.json')).json();
          return {records:data.items.map(r => ({title:r.title,link:r.url,date:'',rawText:r.description}))};
        }
    """.trimIndent()

    @Test
    fun announcedDataIsGivenToTheAiBeforeGenerationAndAcceptedWithoutAnotherLiveRead() = runTest {
        val html = """<link rel="alternate" type="application/feed+json" href="/feed.json">"""
        val asked = mutableListOf<String>()
        val live = FetchTransport { address ->
            asked += address
            assertEquals(feed, address)
            FetchResponse(200, json, "application/feed+json")
        }
        val ai = CollectorKit.ai(CollectorKit.answer(script))
        val outcome = CollectorWriter(ai.gateway, Acceptance(CollectorLimits(timeMillis = 10_000)))
            .write(CollectorBrief(fixture(html), "SDKs", emptyList()), live)
        val accepted = assertIs<WriteOutcome.Accepted>(outcome)
        assertEquals(listOf(feed), asked)
        assertTrue(json in ai.prompts.single())
        assertEquals("A real tool", accepted.acceptance.records.single().title)
        assertEquals(2, accepted.acceptance.runs.size)
        assertEquals(json, accepted.acceptance.fixture.page(feed)?.body)
    }

    @Test
    fun discoveryUsesOnlyAnnouncedAllowedAddressesAndStopsAtARefusal() = runTest {
        val html = """
            <link rel="alternate" type="application/json" href="https://example.org.evil.test/feed">
            <link rel="alternate" type="application/rss+xml" href="javascript:alert(1)">
            <link rel="alternate" type="application/feed+json" href="/feed.json">
            <link rel="alternate" type="application/feed+json" href="/feed.json">
            <link rel="alternate" type="application/atom+xml" href="https://api.example.org/atom">
            <link rel="alternate" type="application/rss+xml" href="/third.xml">
            <link rel="stylesheet" type="application/json" href="/not-a-feed">
        """.trimIndent()
        val copy = fixture(html)
        assertEquals(listOf(feed, "https://api.example.org/atom"), SourceDiscovery.announced(copy))
        val asked = mutableListOf<String>()
        val result = SourceDiscovery.enrich(copy, FetchTransport { address ->
            asked += address
            FetchResponse(429, "Rate limited")
        })
        assertEquals(listOf(feed), asked)
        assertEquals(429, result.page(feed)?.status)
        assertEquals(copy.main, result.main)
        val nothing = SourceDiscovery.enrich(fixture("<div id='root'></div>"), FetchTransport { error("Do not guess endpoints") })
        assertEquals(1, nothing.pages.size)
    }

    @Test
    fun embeddedJsonBeyondTheHtmlPrefixIsSurfacedWithABoundedBudget() {
        val embedded = """<script nonce="" id="__NEXT_DATA__" type="application/json">$json</script>"""
        val copy = fixture("<html>" + " ".repeat(CollectorPrompt.PAGE_CHARS + 100) + embedded + "</html>")
        val prompt = CollectorPrompt.user(CollectorBrief(copy, "tools", emptyList()))
        assertTrue(json in prompt)
        assertTrue("Embedded JSON (id=__NEXT_DATA__" in prompt)
        assertTrue(SourceDiscovery.evidence(copy).length <= SourceDiscovery.EVIDENCE_CHARS)
        val large = fixture("<script type='application/ld+json'>" + """{"text":"${"x".repeat(30_000)}"}""" + "</script>")
        assertTrue("sample truncated" in SourceDiscovery.evidence(large))
        assertTrue(SourceDiscovery.evidence(large).length <= SourceDiscovery.EVIDENCE_CHARS)
        assertTrue(SourceDiscovery.evidence(fixture("<script type='application/json'>not json</script>")).isEmpty())
    }

    @Test
    fun aFailedAttemptFeedsItsNewResponseEvidenceIntoTheRepairPrompt() = runTest {
        val bad = script.replace("link:r.url,", "")
        val ai = CollectorKit.ai(CollectorKit.answer(bad), CollectorKit.answer(script))
        val asked = mutableListOf<String>()
        val live = FetchTransport { address -> asked += address; FetchResponse(200, json, "application/json") }
        val outcome = CollectorWriter(ai.gateway, Acceptance(CollectorLimits(timeMillis = 10_000)))
            .write(CollectorBrief(fixture("<div id='root'></div>"), "tools", emptyList()), live)
        assertIs<WriteOutcome.Accepted>(outcome)
        assertEquals(listOf(feed), asked)
        assertTrue(json !in ai.prompts[0])
        assertTrue(json in ai.prompts[1])
        assertTrue("a record needs a link" in ai.prompts[1])
    }

    @Test
    fun discoveryDoesNotSaveRedirectedEvidenceAndPropagatesCancellation() = runTest {
        val copy = fixture("""<link rel='alternate' type='application/json' href='/feed.json'>""")
        val redirected = SourceDiscovery.enrich(copy, FetchTransport { FetchResponse(200, json, finalUrl = "https://other.test/data") })
        assertEquals(copy, redirected)
        assertFailsWith<CancellationException> {
            SourceDiscovery.enrich(copy, FetchTransport { throw CancellationException("cancelled") })
        }
    }
}
