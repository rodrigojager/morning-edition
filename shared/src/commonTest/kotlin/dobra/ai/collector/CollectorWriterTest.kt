package dobra.ai.collector

import dobra.ai.collector.CollectorKit.ARTICLES
import dobra.ai.collector.CollectorKit.NO_LINKS
import dobra.ai.collector.CollectorKit.PAGE
import dobra.ai.collector.CollectorKit.URL
import dobra.ai.collector.CollectorKit.WRONG_PAGE
import dobra.ai.collector.CollectorKit.answer
import dobra.collect.engine.CollectorLimits
import dobra.collect.health.MemoryHealthStore
import dobra.collect.health.MemorySourceBook
import dobra.make.MissingSetup
import dobra.model.CollectionState
import dobra.model.SourceHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Job A of the AI against a saved page and recorded answers: write, accept, refuse, suggest keywords. */
class CollectorWriterTest {
    private val limits = CollectorLimits(timeMillis = 10_000)
    private fun fixture(page: String = PAGE) = Fixture(URL, "2026-11-13T07:00:00Z", listOf(SavedPage(URL, 200, page, "text/html")))

    private class Setup(vararg answers: String, attempts: Int = 3) {
        val ai = CollectorKit.ai(*answers)
        val book = MemorySourceBook(CollectorKit.magazine())
        val fixtures = MemoryFixtureStore()
        val health = MemoryHealthStore()
        val setup = SourceSetup(
            CollectorWriter(ai.gateway, Acceptance(CollectorLimits(timeMillis = 10_000)), attempts),
            book, fixtures, health, CollectorKit.site(), now = { "2026-11-13T07:00:00Z" },
            model = { SourceSetup.modelOf(ai.registry) }, warmUp = false,
        )
    }

    @Test
    fun aCollectorIsAcceptedAfterTwoEqualRunsOverTheSavedPage() = runTest {
        val ai = CollectorKit.ai(answer(ARTICLES, listOf("segurança", "Kotlin")))
        val outcome = CollectorWriter(ai.gateway, Acceptance(limits)).write(CollectorBrief(fixture(), "segurança", listOf("segurança")))

        val accepted = assertIs<WriteOutcome.Accepted>(outcome)
        assertEquals(ARTICLES, accepted.script)
        assertEquals(2, accepted.acceptance.runs.size)
        val (first, second) = accepted.acceptance.runs
        assertEquals(first.wellFormed, second.wellFormed)
        assertEquals(CollectionState.ANSWERED, first.state)
        assertEquals(3, first.wellFormed.size)
        assertEquals("https://www.example.org/a/1", first.wellFormed[0].link)
        assertEquals(1, accepted.attempts.size)
        // The page and the reader's words went to the model of the collector-writing job, asking for JSON.
        val sent = ai.prompts.single()
        assertTrue(PAGE in sent)
        assertTrue("segurança" in sent)
        assertTrue(ai.transport.sent.single().body!!.contains("model-collectors"))
    }

    @Test
    fun addingASourceSavesThePageWritesTheCollectorAndTheSourceStartsWorking() = runTest {
        val s = Setup(answer(ARTICLES, listOf("segurança", "Kotlin", "roteadores")))
        val outcome = s.setup.write("tec", "noticias")

        val ready = assertIs<SetupOutcome.Ready>(outcome)
        val source = s.book.source("tec", "noticias")
        assertEquals(SourceHealth.WORKING, source.health)
        assertEquals(ARTICLES, source.collector?.script)
        assertEquals(1, source.collector?.version)
        assertEquals("model-collectors", source.collector?.model)
        assertEquals("2026-11-13T07:00:00Z", source.collector?.writtenAt)
        assertEquals("tec/noticias/1", source.fixture)
        assertEquals(PAGE, s.fixtures.get("tec/noticias/1")?.main?.body)
        assertEquals(ready.source, source)
        // "segurança" is in the recipe already: only the new suggestions are offered.
        assertEquals(listOf("Kotlin", "roteadores"), ready.suggested)
        assertTrue(s.health.get("tec", "noticias").hadRecords)
    }

    @Test
    fun aRefusedCollectorGoesBackWithTheErrorAndAfterTheLimitTheSourceIsNotReady() = runTest {
        val s = Setup(answer(NO_LINKS), answer(WRONG_PAGE), "não sei fazer isso", attempts = 3)
        val outcome = s.setup.write("tec", "noticias")

        val notReady = assertIs<SetupOutcome.NotReady>(outcome)
        val prompts = s.ai.prompts
        assertEquals(3, prompts.size)
        // The first refusal (records without a link) and its error go back with the second request...
        assertTrue("# The validator refused it" in prompts[1], prompts[1])
        assertTrue("do not have the record shape" in prompts[1] && "a record needs a link" in prompts[1], prompts[1])
        assertTrue(NO_LINKS.lines().first() in prompts[1])
        // ...the second (a page the copy does not have) with the third.
        assertTrue("the feed answered 404" in prompts[2], prompts[2])
        // The last answer was not even a script: that is the reason the reader sees.
        assertTrue("not a JSON object" in notReady.reason, notReady.reason)
        val source = s.book.source("tec", "noticias")
        assertNull(source.collector)
        assertEquals(notReady.reason, s.setup.record("tec", "noticias").note)
        assertNotNull(s.fixtures.get("tec/noticias/1"), "the copy of the page stays saved")
    }

    @Test
    fun onlyTheChosenSuggestionsAreAddedToTheRecipe() = runTest {
        val s = Setup()
        val m = s.setup.keepKeywords("tec", offered = listOf("Kotlin", "roteadores", "firmware"), chosen = listOf("Kotlin", "firmware", "inventada"))
        assertEquals(listOf("segurança", "Kotlin", "firmware"), m?.keywords)
        assertEquals(listOf("segurança", "Kotlin", "firmware"), s.book.magazine("tec")?.keywords)
    }

    @Test
    fun aSiteThatRefusesAutomatedReadingBecomesUnavailableWithoutAskingTheAi() = runTest {
        val s = Setup()
        val refusing = SourceSetup(
            CollectorWriter(s.ai.gateway), s.book, s.fixtures, s.health, CollectorKit.site(status = 403), now = { "t" },
        )
        val outcome = refusing.write("tec", "noticias")
        assertIs<SetupOutcome.Unavailable>(outcome)
        assertEquals(SourceHealth.UNAVAILABLE, s.book.source("tec", "noticias").health)
        assertTrue(s.ai.transport.sent.isEmpty())
        assertTrue("403" in refusing.record("tec", "noticias").note!!)
    }

    @Test
    fun theAnswerIsReadFromJsonEvenWithAFenceAroundIt() {
        val read = CollectorPrompt.read("```json\n" + answer("```ts\nasync function collect() {}\n```", listOf("a", "A", " b ", "")) + "\n```")
        assertEquals("async function collect() {}", read?.script)
        assertEquals(listOf("a", "b"), read?.keywords)
        assertNull(CollectorPrompt.read("{\"keywords\": []}"))
    }

    @Test
    fun theJobNeedsItsOwnProviderAndModel() {
        val ai = dobra.ai.rewrite.Recorded(emptyList())
        assertEquals(MissingSetup.NO_CHOICE, SourceSetup.missing(ai.registry))
        assertNull(SourceSetup.missing(CollectorKit.ai().registry))
    }
}
