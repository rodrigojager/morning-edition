package dobra.app.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import dobra.ai.provider.ModelClient
import dobra.ai.provider.ModelAnswer
import dobra.ai.provider.ModelOption
import dobra.ai.provider.ServiceTier
import dobra.ai.provider.EffortMode
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
    fun oauthEntryAndApiKeyEntryAreBothVisible() {
        val transport = OneAnswerTransport(200, "{}")
        val auth = ChatGptAuth(storage, AesGcmCipher { secret }, transport, registry)
        val setup = AiSetup(registry, ledger, AiGateway(registry, transport, ledger), chatGpt = auth)
        compose.setContent {
            AppShell(null, DeviceSettings(), onSettings = {}, initialTab = Routes.SETTINGS, store = store, ai = setup)
        }
        openRoute()
        compose.onNodeWithTag("ai-chatgpt-login").performScrollTo().assertIsDisplayed()
        tap("ai-api-expand")
        compose.onNodeWithTag("ai-preset-openai").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai-key").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun addChooseAndRemoveAProvider() {
        val setup = ai()
        compose.setContent {
            AppShell(null, DeviceSettings(), onSettings = {}, initialTab = Routes.SETTINGS, store = store, ai = setup)
        }
        // The settings route opens the screen.
        openRoute()
        compose.onNodeWithTag("screen-ai").assertIsDisplayed()

        tap("ai-api-expand")
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
        tap("ai-api-expand")
        compose.onNodeWithTag("ai-name").performScrollTo().performTextReplacement("Meu provedor")
        compose.onNodeWithTag("ai-url").performScrollTo().performTextReplacement("https://api.example.test/v1")
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

    @Test
    fun connectedChatGptHidesLoginAndSavesCatalogModelEffortAndSpeedForAllTasks() {
        registry.addChatGpt()
        val transport = OneAnswerTransport(200, "{}")
        val auth = ChatGptAuth(storage, AesGcmCipher { secret }, transport, registry)
        val catalog = object : ModelClient {
            override suspend fun complete(request: AiRequest): ModelAnswer = error("no inference in UI test")
            override suspend fun listModels() = listOf("model-one", "model-two")
            override suspend fun modelOptions() = listOf(
                ModelOption("model-one", "First model", listOf(Effort.LOW)),
                ModelOption("model-two", "Second model", listOf(Effort.HIGH, Effort.XHIGH), listOf(ServiceTier("fast", "Fast"))),
            )
        }
        val setup = AiSetup(registry, ledger, AiGateway(registry, transport, ledger, oauthClient = { catalog }), chatGpt = auth)
        compose.setContent { AiProvidersScreen(setup) {} }
        compose.onNodeWithTag("ai-chatgpt-login").assertDoesNotExist()
        tap("ai-chatgpt-models")
        compose.onNodeWithTag("ai-model-settings").assertIsDisplayed()
        tap("ai-job-ANSWER-provider-chatgpt")
        tap("ai-job-ANSWER-model-picker")
        compose.onNodeWithTag("ai-job-ANSWER-model-search").performTextInput("Second")
        compose.onNodeWithTag("ai-job-ANSWER-pick-model-one").assertDoesNotExist()
        compose.onNodeWithTag("ai-job-ANSWER-pick-model-two").performClick()
        tap("ai-job-ANSWER-effort-XHIGH")
        tap("ai-job-ANSWER-speed-fast")
        tap("ai-job-ANSWER-apply-all")
        val restored = ProviderRegistry(storage, AesGcmCipher { secret })
        assertEquals(3, restored.state.value.choices.size)
        assertTrue(restored.state.value.choices.values.all {
            it.providerId == "chatgpt" && it.model == "model-two" && it.effort == Effort.XHIGH && it.serviceTier == "fast"
        })
        tap("ai-model-settings-back")
        compose.onNodeWithTag("ai-chatgpt-login").assertDoesNotExist()
    }

    @Test
    fun claudeAndOpenCodeHaveEditableDefaultsAndSharedModelSettings() {
        val transport = OneAnswerTransport(200, """{"data":[{"id":"model-api","display_name":"API model",
            "capabilities":{"effort":{"supported":true,"max":{"supported":true}}}}]}""")
        compose.setContent { AiProvidersScreen(ai(transport)) {} }
        tap("ai-claude-expand")
        compose.onNodeWithTag("ai-claude-url").performScrollTo().assertTextContains("https://api.anthropic.com/v1")
        compose.onNodeWithTag("ai-claude-url").performTextReplacement("https://custom.test/v1")
        type("ai-claude-key", fakeKey)
        tap("ai-claude-add")
        val claude = registry.state.value.providers.single()
        assertEquals("https://custom.test/v1", claude.baseUrl)
        tap("ai-claude-models")
        tap("ai-job-ANSWER-provider-${claude.id}")
        tap("ai-job-ANSWER-model-picker")
        compose.onNodeWithTag("ai-job-ANSWER-pick-model-api").performClick()
        tap("ai-job-ANSWER-effort-MAX")
        assertEquals(EffortMode.OUTPUT_CONFIG, registry.state.value.choices[AiJob.ANSWER]?.effortMode)
        tap("ai-model-settings-back")
        tap("ai-opencode-expand")
        compose.onNodeWithTag("ai-opencode-url").performScrollTo().assertTextContains("https://opencode.ai/inference")
        type("ai-opencode-key", fakeKey)
        tap("ai-opencode-add")
        val console = registry.state.value.providers.last()
        assertEquals(ProviderKind.OPENCODE, console.kind)
        tap("ai-opencode-models")
        tap("ai-job-ANSWER-provider-${console.id}")
        assertEquals("", registry.state.value.choices[AiJob.ANSWER]?.model)
        tap("ai-job-ANSWER-model-picker")
        compose.onNodeWithTag("ai-job-ANSWER-pick-model-api").performClick()
        tap("ai-job-ANSWER-effort-HIGH")
        tap("ai-job-ANSWER-apply-all")
        assertTrue(registry.state.value.choices.values.all { it.providerId == console.id && it.effort == Effort.HIGH })
    }

    @Test
    fun changingApiFormatUpdatesOnlyAnUneditedOfficialUrl() {
        compose.setContent { AiProvidersScreen(ai()) {} }
        tap("ai-api-expand")
        tap("ai-kind-anthropic")
        compose.onNodeWithTag("ai-url").performScrollTo().assertTextContains("https://api.anthropic.com/v1")
        compose.onNodeWithTag("ai-url").performTextReplacement("https://custom.test/v1")
        tap("ai-kind-openai")
        compose.onNodeWithTag("ai-url").performScrollTo().assertTextContains("https://custom.test/v1")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun connectedServiceCardsRenderAtPhoneWidth() {
        registry.addChatGpt()
        registry.add("Claude", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1", fakeKey)
        registry.add("OpenCode Console", ProviderKind.OPENCODE, "https://opencode.ai/inference", fakeKey)
        registry.choose(AiJob.ANSWER, dobra.ai.provider.JobChoice("chatgpt", "example-model", Effort.AUTO))
        val transport = OneAnswerTransport(200, """{"models":[{"slug":"example-model","display_name":"Example model",
            "visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},
            {"effort":"high"},{"effort":"xhigh"}],"service_tiers":[{"id":"fast","name":"Fast"}]}],"data":[]}""")
        val auth = ChatGptAuth(storage, AesGcmCipher { secret }, transport, registry)
        val setup = AiSetup(registry, ledger, AiGateway(registry, transport, ledger,
            oauthClient = { dobra.ai.provider.ChatGptClient(transport) { "synthetic-session" } }), chatGpt = auth)
        var previewView: android.view.View? = null
        compose.setContent {
            previewView = androidx.compose.ui.platform.LocalView.current
            AppShell(null, DeviceSettings(), onSettings = {}, initialTab = Routes.SETTINGS, store = store, ai = setup)
        }
        openRoute()
        compose.onNodeWithTag("ai-chatgpt-login").assertDoesNotExist()
        fun preview(name: String) {
            val target = System.getenv("DOBRA_AI_PREVIEWS") ?: return
            compose.runOnIdle {
                val view = checkNotNull(previewView)
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                val file = File(target, "$name.png")
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        preview("ai-accounts")
        compose.onNodeWithTag("ai-claude-models").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai-opencode-models").performScrollTo().assertIsDisplayed()
        preview("ai-api-accounts")
        tap("ai-chatgpt-models")
        tap("ai-job-ANSWER-provider-chatgpt")
        compose.onNodeWithTag("ai-job-ANSWER-model-picker").performScrollTo().assertTextContains("Example model")
        tap("ai-job-ANSWER-effort-XHIGH")
        tap("ai-job-ANSWER-speed-fast")
        compose.onNodeWithTag("ai-job-ANSWER-apply-all").performScrollTo().assertIsDisplayed()
        preview("ai-model-settings")
    }
}
