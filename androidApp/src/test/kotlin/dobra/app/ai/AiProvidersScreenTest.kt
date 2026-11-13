package dobra.app.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.ai.provider.AiException
import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.AiRequest
import dobra.ai.provider.Effort
import dobra.ai.provider.HttpRequest
import dobra.ai.provider.HttpResponse
import dobra.ai.provider.HttpTransport
import dobra.ai.provider.LoggingTransport
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.UsageLedger
import dobra.app.edition.EditionFixture
import dobra.app.shell.AppShell
import dobra.app.shell.Routes
import dobra.store.DeviceSettings
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

private class OneAnswerTransport(private val status: Int, private val body: String) : HttpTransport {
    override suspend fun send(request: HttpRequest) = HttpResponse(status, emptyMap(), body)
}

@RunWith(AndroidJUnit4::class)
class AiProvidersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val fakeKey = "sk-fake-9876-QRST"
    private val root: File = Files.createTempDirectory("dobra-ai-test").toFile()
    private val storeDir = File(root, "store")
    private val aiDir = File(root, "ai")
    private val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store: Store = runBlocking {
        openStore(storeDir.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }
    private val storage = FileBlobStorage(aiDir)
    private val registry = ProviderRegistry(storage, AesGcmCipher { secret })
    private val ledger = UsageLedger(storage)
    private val logs = mutableListOf<String>()

    private fun ai(transport: HttpTransport = OneAnswerTransport(200, "{}")) =
        AiSetup(registry, ledger, AiGateway(registry, LoggingTransport(transport, logs::add), ledger))

    @After
    fun close() {
        store.close()
        root.deleteRecursively()
    }

    private fun openRoute() = compose.onNodeWithTag("settings-ai").performScrollTo().performClick()
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun type(tag: String, text: String) = compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)

    @Test
    fun addChooseAndRemoveAProvider() {
        val setup = ai()
        compose.setContent {
            AppShell(null, DeviceSettings(), onSettings = {}, initialTab = Routes.SETTINGS, store = store, ai = setup)
        }
        // The settings route opens the screen.
        openRoute()
        compose.onNodeWithTag("screen-ai").assertIsDisplayed()

        tap("ai-preset-openai")
        type("ai-key", fakeKey)
        tap("ai-add")
        val id = registry.state.value.providers.single().id
        compose.onNodeWithTag("ai-last4-$id").performScrollTo().assertTextContains("QRST", substring = true)
        // Only the last four characters are ever shown.
        assertEquals(0, compose.onAllNodesWithText("sk-fake", substring = true).fetchSemanticsNodes().size)

        for (job in AiJob.entries) {
            tap("ai-job-${job.name}-provider-$id")
            type("ai-job-${job.name}-model", "model-${job.name}")
            tap("ai-job-${job.name}-effort-HIGH")
        }
        for (job in AiJob.entries) {
            val choice = registry.state.value.choices.getValue(job)
            assertEquals(id, choice.providerId)
            assertEquals("model-${job.name}", choice.model)
            assertEquals(Effort.HIGH, choice.effort)
        }

        tap("ai-remove-$id")
        assertTrue(registry.state.value.providers.isEmpty())
        assertTrue(registry.state.value.choices.isEmpty())
        assertEquals(0, compose.onAllNodesWithTag("ai-provider-$id").fetchSemanticsNodes().size)
    }

    @Test
    fun keyIsSealedAndAbsentFromStoreEditionFilesAndLogs() {
        val setup = ai(OneAnswerTransport(401, """{"error":{"message":"Incorrect API key provided."}}"""))
        compose.setContent {
            AppShell(null, DeviceSettings(), onSettings = {}, initialTab = Routes.SETTINGS, store = store, ai = setup)
        }
        openRoute()
        type("ai-name", "Meu provedor")
        type("ai-url", "https://api.example.test/v1")
        type("ai-key", fakeKey)
        tap("ai-add")
        val id = registry.state.value.providers.single().id
        registry.choose(AiJob.ANSWER, dobra.ai.provider.JobChoice(id, "m"))
        assertEquals(ProviderKind.OPENAI_STYLE, registry.state.value.providers.single().kind)

        // The key can be read back for a call...
        assertEquals(fakeKey, registry.key(id))
        // ...a refused call goes through the logging transport...
        try {
            runBlocking { setup.gateway.call(AiJob.ANSWER, AiRequest("m", "oi")) }
        } catch (e: AiException.KeyRefused) {
            assertFalse(fakeKey in e.message.orEmpty())
        }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { fakeKey in it })

        // ...and it appears in no file of the store (database, editions) nor of the AI folder.
        val needle = fakeKey.encodeToByteArray()
        fun hasNeedle(bytes: ByteArray): Boolean =
            (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.name == "dobra.db" })
        assertTrue(files.any { it.extension == "json" && it.parentFile?.name != "ai" })
        assertTrue(files.any { it.name.startsWith("key-") })
        files.forEach { assertFalse("${it.name} holds the key", hasNeedle(it.readBytes())) }
    }
}
