package dobra.app.ask

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.ai.provider.AiGateway
import dobra.ai.provider.HttpRequest
import dobra.ai.provider.HttpResponse
import dobra.ai.provider.HttpTransport
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.UsageLedger
import dobra.app.ai.AesGcmCipher
import dobra.app.ai.AiSetup
import dobra.app.ai.FileBlobStorage
import dobra.app.edition.EditionFixture
import dobra.app.edition.EditionTab
import dobra.app.shell.AppTheme
import dobra.model.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

/** Replays one recorded OpenAI-style answer and remembers what was sent. Never touches a network. */
private class RecordedTransport : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        sent += request
        return HttpResponse(
            200, emptyMap(),
            """{"id":"c1","object":"chat.completion","created":1,"model":"m",
            "choices":[{"index":0,"message":{"role":"assistant","content":"Resposta gravada sobre a matéria."},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":2000,"completion_tokens":1000,"total_tokens":3000}}""",
        )
    }
}

@RunWith(AndroidJUnit4::class)
class AskPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val root: File = Files.createTempDirectory("dobra-ask-test").toFile()
    private val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val storage = FileBlobStorage(File(root, "ai"))
    private val registry = ProviderRegistry(storage, AesGcmCipher { secret })
    private val ledger = UsageLedger(storage)
    private val transport = RecordedTransport()
    private val setup = AiSetup(
        registry, ledger, AiGateway(registry, transport, ledger), dobra.ai.ask.ConversationStore(storage),
    )
    private val story = EditionFixture.edition.items.first { it.kind == ItemKind.STORY }

    @After
    fun close() {
        root.deleteRecursively()
    }

    private fun show() = compose.setContent {
        AppTheme {
            AskHost(setup) { EditionTab(EditionFixture.magazine, EditionFixture.edition, onBackToLibrary = {}, onPreviousEdition = null) }
        }
    }

    private fun openPanel() {
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag("chapter-${story.id}"))
        compose.onNodeWithTag("ask-${story.id}").performScrollTo().performClick()
        compose.onNodeWithTag("ask-panel").assertIsDisplayed()
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun askPicksProviderModelEffortAndKeepsTheConversation() {
        val p = registry.add("OpenAI", ProviderKind.OPENAI_STYLE, "https://api.example.test/v1", "sk-fake-0000-ABCD", 2.0, 4.0)
        show()
        openPanel()

        tap("ask-provider-${p.id}")
        compose.onNodeWithTag("ask-model").performScrollTo().performTextInput("modelo-x")
        tap("ask-effort-HIGH")
        compose.onNodeWithTag("ask-input").performScrollTo().performTextInput("Quanto isso importa?")
        tap("ask-send")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("ask-msg-1").fetchSemanticsNodes().isNotEmpty() }

        // The request carried the question and the story's text, and the job's choice.
        val body = transport.sent.single().body!!
        assertTrue("Quanto isso importa?" in body)
        assertTrue("modelo-x" in body)
        assertTrue("\"high\"" in body)
        // "O que foi enviado": the size of the reference and the cost of the question.
        compose.onNodeWithTag("ask-sent-reference").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ask-sent-cost").performScrollTo().assertIsDisplayed()
        // 2000 in * 2.0 + 1000 out * 4.0 per million tokens = US$ 0.0080
        val cost = compose.onNodeWithTag("ask-sent-cost").fetchSemanticsNode().config
            .first { it.key.name == "Text" }.value.toString()
        assertTrue(cost, "0,0080" in cost)

        // Reopening the story shows the conversation again.
        tap("ask-close")
        assertEquals(0, compose.onAllNodesWithTag("ask-panel").fetchSemanticsNodes().size)
        openPanel()
        compose.onNodeWithTag("ask-msg-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ask-msg-1").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withoutAProviderThePanelSaysWhatIsMissingAndLinksToTheProvidersScreen() {
        show()
        openPanel()
        compose.onNodeWithTag("ask-missing").assertIsDisplayed()
        tap("ask-open-providers")
        compose.onNodeWithTag("screen-ai").assertIsDisplayed()
        assertTrue(transport.sent.isEmpty())
    }
}
