package dobra.app.library

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.ai.collector.CollectorWork
import dobra.ai.collector.SetupOutcome
import dobra.app.edition.EditionFixture
import dobra.collect.health.HealthRecord
import dobra.collect.health.StoreSourceBook
import dobra.make.MissingSetup
import dobra.model.CollectorInfo
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/**
 * The collectors on the magazine page: each source's state, the action that asks the AI for a collector, the keywords
 * it suggests and the reason a collector is not ready. The AI's work is a fake here (the real one, with recorded
 * answers and saved pages, is tested in `shared`).
 */
@RunWith(AndroidJUnit4::class)
class CollectorScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun collector(script: String = "async function collect() { return { records: [] }; }") =
        CollectorInfo(script, 1, "2026-11-01T07:00:00Z", "model-collectors")

    private val sources = listOf(
        Source("ok", "Funciona", "https://ok.example.org/", "notícias", collector(), "seed/ok/1", health = SourceHealth.WORKING),
        Source("fix", "Quebrou", "https://fix.example.org/", "notícias", collector(), "seed/fix/1", health = SourceHealth.REPAIRING),
        Source("no", "Recusa", "https://no.example.org/", "notícias", collector(), "seed/no/1", health = SourceHealth.UNAVAILABLE),
        Source("new", "Nova", "https://new.example.org/", "falhas de segurança"),
    )
    private val magazine: Magazine = EditionFixture.magazine.copy(sources = sources, keywords = emptyList())
    private val root = Files.createTempDirectory("dobra-collector-screen-test").toFile()
    private val store: Store = runBlocking { openStore(root.path) { SeedContent(magazine, EditionFixture.edition) } }
    private val id = magazine.id
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        scope.cancel()
        store.close()
        root.deleteRecursively()
    }

    /** Stands in for the AI's work; [outcome] decides what writing a collector comes to. */
    private inner class FakeWork(private val outcome: suspend (String) -> SetupOutcome) : CollectorWork {
        val book = StoreSourceBook { store.magazines }
        val written = mutableListOf<String>()
        val repaired = mutableListOf<String>()
        val records = mutableMapOf(
            "fix" to HealthRecord(badRuns = 3, hadRecords = true, note = "o coletor não trouxe nada onde antes havia notícias"),
            "no" to HealthRecord(refusals = 2, note = "o site recusou a leitura automática (HTTP 403)"),
        )

        override suspend fun write(magazineId: String, sourceId: String): SetupOutcome {
            synchronized(written) { written += sourceId }
            return outcome(sourceId)
        }

        override suspend fun repair(magazineId: String, sourceId: String, reason: String): SetupOutcome {
            synchronized(repaired) { repaired += sourceId }
            return SetupOutcome.NotReady(book.magazine(magazineId)!!.sources.first { it.id == sourceId }, reason)
        }

        override suspend fun keepKeywords(magazineId: String, offered: List<String>, chosen: List<String>) =
            book.addKeywords(magazineId, chosen.filter { it in offered })

        override suspend fun record(magazineId: String, sourceId: String) = records[sourceId] ?: HealthRecord()
    }

    private fun show(work: FakeWork, missing: MissingSetup? = null) {
        val desk = CollectorDesk(scope, { work }, { missing })
        compose.setContent { LibraryScreen(store, desk = desk) {} }
        click("mag-open-$id")
        tag("screen-magazine")
    }

    private fun tag(t: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(t)
    }

    private fun click(t: String) = tag(t).performScrollTo().performClick()

    private fun absent(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isEmpty() }

    private fun text(t: String, expected: String) = compose.waitUntil(5_000) {
        runCatching { compose.onNodeWithTag(t).assertTextEquals(expected) }.isSuccess
    }

    @Test
    fun eachSourceShowsItsStateAndOnlyASourceWithoutACollectorOffersToAskTheAi() {
        show(FakeWork { error("not asked") })
        text("source-state-ok", "funcionando")
        text("source-state-fix", "em reparo")
        text("source-state-no", "indisponível")
        text("source-state-new", "sem coletor")
        text("source-reason-fix", "Motivo: o coletor não trouxe nada onde antes havia notícias")
        text("source-reason-no", "Motivo: o site recusou a leitura automática (HTTP 403)")
        absent("source-reason-ok")
        tag("write-collector-new").assertTextEquals("Pedir à IA que escreva o coletor")
        absent("write-collector-ok")
        absent("write-collector-fix")
        absent("write-collector-no")
        tag("repair-collector-fix").assertTextEquals("Pedir à IA que adapte o coletor")
        absent("repair-collector-ok")
    }

    @Test
    fun theAiWritesTheCollectorAndOnlyTheChosenKeywordsEnterTheRecipe() {
        val gate = CompletableDeferred<Unit>()
        val work = FakeWork { sourceId ->
            gate.await()
            val accepted = StoreSourceBook { store.magazines }.update(id, sourceId) {
                it.copy(collector = collector("async function collect() {}"), fixture = "$id/$sourceId/1", health = SourceHealth.WORKING)
            }!!
            SetupOutcome.Ready(accepted, listOf("Kotlin", "firmware", "roteadores"))
        }
        show(work)
        click("write-collector-new")
        text("source-state-new", "a IA está escrevendo o coletor…")
        absent("write-collector-new")
        gate.complete(Unit)

        text("source-state-new", "funcionando")
        tag("collector-suggestions-new")
        tag("suggest-new-0").assertTextEquals("Kotlin")
        click("suggest-new-1")
        click("keep-keywords-new")
        absent("collector-suggestions-new")
        compose.waitUntil(5_000) { runBlocking { store.magazines.get(id) }!!.keywords.isNotEmpty() }
        assertEquals(listOf("firmware"), runBlocking { store.magazines.get(id) }!!.keywords)
        assertEquals(listOf("new"), work.written)
    }

    @Test
    fun aCollectorThatIsNotReadyIsShownWithTheReason() {
        val reason = "2 de 3 registros não têm o formato esperado: falta o link"
        lateinit var work: FakeWork
        work = FakeWork { sourceId ->
            work.records[sourceId] = HealthRecord(note = reason)
            SetupOutcome.NotReady(sources.last(), reason)
        }
        show(work)
        click("write-collector-new")
        text("source-state-new", "coletor não está pronto")
        text("source-reason-new", "Motivo: $reason")
        tag("write-collector-new").assertTextEquals("Pedir à IA de novo")
    }

    @Test
    fun withoutAProviderForTheJobThePageSaysWhatIsMissing() {
        val work = FakeWork { error("not asked") }
        show(work, missing = MissingSetup.NO_CHOICE)
        click("write-collector-new")
        tag("collector-missing-new")
        assertTrue(work.written.isEmpty())
    }

    @Test
    fun aRepairAskedFromThePageKeepsTheOldCollectorWhenItDoesNotPass() {
        val work = FakeWork { error("not asked") }
        show(work)
        click("repair-collector-fix")
        text("source-state-fix", "em reparo")
        compose.waitUntil(5_000) { synchronized(work.repaired) { work.repaired.isNotEmpty() } }
        text("source-reason-fix", "Motivo: o coletor não trouxe nada onde antes havia notícias")
        assertEquals(1, runBlocking { store.magazines.get(id) }!!.sources.first { it.id == "fix" }.collector?.version)
    }

    @Test
    fun addingASourceAsksTheAiForItsCollectorAtOnce() {
        val work = FakeWork { sourceId -> SetupOutcome.NotReady(sources.last().copy(id = sourceId), "a página não respondeu") }
        show(work)
        click("add-source")
        tag("source-field-url").performTextInput("https://blog.example.org/")
        tag("source-field-liked").performTextInput("lançamentos")
        click("source-save")
        tag("source-blog-example-org")
        compose.waitUntil(5_000) { synchronized(work.written) { work.written == listOf("blog-example-org") } }
        text("source-state-blog-example-org", "coletor não está pronto")
    }
}
