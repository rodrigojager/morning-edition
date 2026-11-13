package dobra.app.sources

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.library.LibraryScreen
import dobra.app.shell.AppTheme
import dobra.collect.engine.RawRecord
import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.SourceResult
import dobra.collect.worker.WorkerProgress
import dobra.collect.worker.WorkerResult
import dobra.model.CollectionState
import dobra.model.Magazine
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.Theme
import dobra.store.openStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class CollectNowTest {
    @get:Rule
    val compose = createComposeRule()

    private val root = Files.createTempDirectory("dobra-collect-test").toFile()
    private val store: Store = runBlocking {
        openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }
    private val id = EditionFixture.magazine.id

    @After
    fun close() {
        store.close()
        root.deleteRecursively()
    }

    private fun record(n: Int) = RawRecord("Título $n", "https://a.example.org/$n", "", "texto")

    /** Reports one source done, waits for the test's gate, then answers three sources: two good, one refused. */
    private class Gated(val gate: CompletableDeferred<Unit>) : MagazineCollector {
        var asked: Magazine? = null
        override suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit): WorkerResult {
            asked = magazine
            onProgress(WorkerProgress(0, 3, "GitHub Trending"))
            onProgress(WorkerProgress(1, 3, "Hacker News"))
            gate.await()
            onProgress(WorkerProgress(3, 3, null))
            val a = SourceResult(
                "github-trending", "GitHub Trending", CollectionState.ANSWERED, 24, 2,
                linkedMapOf("segurança" to 2, "agentes" to 0), listOf(), listOf(),
            )
            val b = SourceResult(
                "hacker-news", "Hacker News", CollectionState.ANSWERED, 130, 3,
                linkedMapOf("segurança" to 1, "agentes" to 2), listOf(), listOf(),
            )
            val c = SourceResult(
                "apiiro", "Blog da Apiiro", CollectionState.FAILED, 0, 0, emptyMap(), emptyList(), emptyList(),
                reason = "refused by the site (HTTP 403)", refusedStatus = 403,
            )
            return WorkerResult(magazine.id, listOf(a, b, c))
        }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t).also {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(t: String) = tag(t).performScrollTo().performClick()

    @Test
    fun collectNowShowsProgressThenEachSourcesResultAndTheCountPerKeyword() {
        val collector = Gated(CompletableDeferred())
        compose.setContent { AppTheme(Theme.PAPER, 1f) { LibraryScreen(store, collector = collector) } }
        click("mag-open-$id")
        click("collect-now")

        tag("collect-progress").assertTextEquals("Coletando… 1 de 3 fontes")
        tag("collect-progress-source").assertTextEquals("Consultando Hacker News")
        assertEquals(id, collector.asked?.id)

        collector.gate.complete(Unit)
        tag("collect-summary")
        compose.onAllNodesWithTag("collect-progress").assertCountEquals(0)

        tag("result-state-github-trending").assertTextEquals("Respondeu · 24 devolvidos · 2 com palavras-chave")
        tag("result-keywords-github-trending").assertTextEquals("segurança: 2 · agentes: 0")
        tag("result-keywords-hacker-news").assertTextEquals("segurança: 1 · agentes: 2")
        tag("result-state-apiiro").assertTextEquals("Falhou · 0 devolvidos · 0 com palavras-chave")
        tag("result-refused-apiiro").assertTextEquals("O site recusou a leitura automática (HTTP 403). Não foi tentado de outro jeito.")
        tag("result-reason-apiiro").assertTextEquals("Motivo: refused by the site (HTTP 403)")
    }
}
