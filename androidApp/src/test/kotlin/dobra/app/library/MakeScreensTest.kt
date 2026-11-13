package dobra.app.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.HttpRequest
import dobra.ai.provider.HttpResponse
import dobra.ai.provider.HttpTransport
import dobra.ai.provider.JobChoice
import dobra.ai.provider.MemoryBlobStorage
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.SecretCipher
import dobra.ai.provider.UsageLedger
import dobra.ai.rewrite.Verification
import dobra.app.ai.AiSetup
import dobra.app.edition.EditionFixture
import dobra.app.edition.EditionTab
import dobra.app.format.FormatScreen
import dobra.app.shell.AppTheme
import dobra.collect.engine.RawRecord
import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.SourceResult
import dobra.collect.worker.WorkerProgress
import dobra.collect.worker.WorkerResult
import dobra.format.ItemFormat
import dobra.model.CollectionState
import dobra.model.Edition
import dobra.model.Magazine
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.Theme
import dobra.store.openStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

private object PlainCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(sealed: ByteArray) = sealed
}

/** Recorded answers in the OpenAI chat completions format, replayed in order. Nothing reaches a network. */
private class Replay(answers: List<String>) : HttpTransport {
    private val queue = answers.toMutableList()
    val sent = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        synchronized(sent) { sent += request }
        val content = synchronized(queue) { queue.removeAt(0) }
        val body = buildJsonObject {
            put("id", "chatcmpl-test")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject { put("prompt_tokens", 1000); put("completion_tokens", 500) })
        }
        return HttpResponse(200, emptyMap(), body.toString())
    }
}

@RunWith(AndroidJUnit4::class)
class MakeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val root = Files.createTempDirectory("dobra-make-screen-test").toFile()
    private val store: Store = runBlocking {
        openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }
    private val id = EditionFixture.magazine.id
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        scope.cancel()
        store.close()
        root.deleteRecursively()
    }

    private fun ai(transport: Replay, provider: Boolean = true, choice: Boolean = true): AiSetup {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, PlainCipher)
        val ledger = UsageLedger(storage)
        if (provider) {
            val p = registry.add("Teste", ProviderKind.OPENAI_STYLE, "https://api.example.test/v1", "sk-fake-test-0000", 1.0, 4.0)
            if (choice) registry.choose(AiJob.REWRITE, JobChoice(p.id, "model-rewrite"))
        }
        return AiSetup(registry, ledger, AiGateway(registry, transport, ledger))
    }

    private val tool = RawRecord(
        "acme/agent-guard", "https://github.com/acme/agent-guard", "2026-11-13",
        "agent-guard bloqueia comandos perigosos que um agente de código tenta rodar. Licença MIT.",
    )
    private val bait = RawRecord("10 truques de IA", "https://clickbait.example.com/10", "", "Você não vai acreditar.")

    /** Reports the first source, waits for the test's gate, then answers two saved records. */
    private inner class Gated : MagazineCollector {
        val gate = CompletableDeferred<Unit>()
        var asked = false
        override suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit): WorkerResult {
            asked = true
            onProgress(WorkerProgress(0, 2, "GitHub Trending"))
            onProgress(WorkerProgress(1, 2, "Hacker News"))
            gate.await()
            onProgress(WorkerProgress(2, 2, null))
            val result = SourceResult(
                "github-trending", "GitHub Trending", CollectionState.ANSWERED, 24, 2,
                mapOf("agentes" to 2), listOf(tool, bait), emptyList(),
            )
            return WorkerResult(magazine.id, listOf(result))
        }
    }

    private val selection = """{"keep": [{"records": ["r1"], "kind": "story"}],
        "leftOut": [{"record": "r2", "reason": "Isca de cliques."}]}"""

    private fun answer(title: String, link: String) = buildJsonObject {
        put("id", "agent-guard")
        put("kind", "story")
        put("blocks", buildJsonArray {
            add(buildJsonObject { put("type", "title"); put("kicker", "Segurança"); put("text", title) })
            add(buildJsonObject { put("type", "paragraph"); put("text", "O que está no texto coletado.") })
            add(buildJsonObject {
                put("type", "sources")
                put("links", buildJsonArray { add(buildJsonObject { put("label", "Fonte"); put("url", link) }) })
            })
        })
    }.toString()

    private fun tag(t: String): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(t)
    }

    private fun click(t: String) = tag(t).performScrollTo().performClick()

    private fun textIs(t: String, text: String) = compose.waitUntil(10_000) {
        runCatching { compose.onNodeWithTag(t).assertTextEquals(text) }.isSuccess
    }

    private var openKey by mutableStateOf<String?>(null)

    private fun showLibrary(runner: MakeRunner) = compose.setContent {
        AppTheme(Theme.PAPER, 1f) {
            val key = openKey
            if (key == null) {
                LibraryScreen(store, maker = runner) { openKey = it }
            } else {
                val edition by produceState<Edition?>(null, key) { value = store.editions.get(key) }
                val magazine by produceState<Magazine?>(null) { value = store.magazines.get(id) }
                val e = edition
                val m = magazine
                if (e != null && m != null) EditionTab(m, e, onBackToLibrary = {})
            }
        }
    }

    @Test
    fun makeNowShowsProgressThenOpensTheNewEditionInTheReader() {
        val transport = Replay(listOf(selection, answer("Um guarda de permissões para agentes", tool.link)))
        val collector = Gated()
        val runner = MakeRunner(scope, { store }, ai(transport), collector, today = { "2026-11-13" })
        showLibrary(runner)
        click("mag-open-$id")
        click("make-now")

        textIs("make-progress", "Coletando… 1 de 2 fontes")
        tag("make-progress-current").assertTextEquals("Agora: Hacker News")
        collector.gate.complete(Unit)

        tag("make-result")
        tag("make-result-counts").assertTextEquals("1 matéria · 1 deixada de fora · custo de IA: US$ 0,0060 em 2 chamadas")
        tag("make-left-0").assertTextEquals("10 truques de IA: Isca de cliques.")
        assertEquals(2, transport.sent.size)
        val stored = runBlocking { store.editions.get("$id/2026-11-13") }!!
        assertEquals(2, stored.number)
        assertTrue(runBlocking { store.unread.count(id) } >= 2)

        click("make-open")
        assertEquals("$id/2026-11-13", openKey)
        tag("edition-scroll").performScrollToNode(hasTestTag("chapter-agent-guard"))
        tag("chapter-agent-guard")
    }

    @Test
    fun withoutAProviderTheAppSaysWhatIsMissingAndCallsNothing() {
        val transport = Replay(emptyList())
        val collector = Gated()
        showLibrary(MakeRunner(scope, { store }, ai(transport, provider = false), collector))
        click("mag-open-$id")
        click("make-now")
        tag("make-missing").assertTextEquals(
            "Para gerar uma edição falta um provedor de IA. Adicione um em Ajustes › Provedores de IA e escolha quem faz o trabalho “Reescrever”.",
        )
        assertTrue(!collector.asked)
        assertTrue(transport.sent.isEmpty())
        assertTrue(compose.onAllNodesWithTag("make-progress").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun withAProviderButNoChoiceForRewritingTheAppSaysSo() {
        showLibrary(MakeRunner(scope, { store }, ai(Replay(emptyList()), choice = false), Gated()))
        click("mag-open-$id")
        click("make-now")
        tag("make-missing").assertTextEquals("Falta escolher qual provedor faz o trabalho “Reescrever”. Escolha em Ajustes › Provedores de IA.")
    }

    @Test
    fun theFormatEditorAsksTheAiToRewriteTheEditionForTheCurrentFormat() {
        val edition = EditionFixture.edition
        val withQuote = edition.items.filter { i -> i.blocks.any { it.type == "quote" } }
        assertTrue(withQuote.size >= 2)
        val answers = withQuote.map { item -> answer("Reescrita sem citação", Verification.addressesOf(item).first.first()) }
        val transport = Replay(answers)
        val runner = MakeRunner(scope, { store }, ai(transport), Gated())
        compose.setContent { AppTheme { FormatScreen(store, id, onBack = {}, maker = runner) } }

        textIs("format-check-summary", "14 de 14 itens dentro do formato")
        assertTrue(compose.onAllNodesWithTag("format-rewrite").fetchSemanticsNodes().isEmpty())
        click("format-toggle-quote")
        textIs("format-check-summary", "${14 - withQuote.size} de 14 itens dentro do formato")

        click("format-rewrite")
        textIs(
            "format-rewrite-result",
            "${withQuote.size} de ${withQuote.size} matérias reescritas para o formato atual · custo de IA: US$ " +
                dollars(withQuote.size * 0.003),
        )
        textIs("format-check-summary", "14 de 14 itens dentro do formato")
        assertEquals(withQuote.size, transport.sent.size)

        val after = runBlocking { store.editions.get(edition.key) }!!
        val format = runBlocking { store.magazines.get(id) }!!.format
        after.items.forEach { assertTrue(it.id, ItemFormat.check(it, format).ok) }
        assertEquals(edition.items.map { it.id }, after.items.map { it.id })
        assertNull(compose.onAllNodesWithTag("format-rewrite").fetchSemanticsNodes().firstOrNull())
    }
}
