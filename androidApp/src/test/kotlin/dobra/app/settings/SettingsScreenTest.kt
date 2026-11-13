package dobra.app.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.shell.AppShell
import dobra.app.shell.Routes
import dobra.app.shell.ShellData
import dobra.store.Controls
import dobra.store.ReadingMode
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.Theme
import dobra.store.openStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val root: File = Files.createTempDirectory("dobra-settings-test").toFile()
    private var store: Store = open()
    private var generation by mutableStateOf(0)

    private fun open(): Store = runBlocking {
        openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }

    @After
    fun close() {
        store.close()
        root.deleteRecursively()
    }

    /** The app on the Ajustes tab, reading and writing the real store; a new [generation] is a recreated app. */
    private fun show() {
        compose.setContent {
            key(generation) {
                val settings by store.settings.observe().collectAsState(initial = null)
                val magazines by store.magazines.observeAll().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val current = settings
                val magazine = magazines.firstOrNull()
                if (current != null && magazine != null) {
                    AppShell(
                        ShellData(magazine, EditionFixture.edition), current,
                        onSettings = { scope.launch { store.settings.set(it) } },
                        initialTab = Routes.SETTINGS, store = store,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun recreate() {
        store.close()
        store = open()
        generation++
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun showsEveryChoiceAndKeepsThemAfterTheAppIsRecreated() {
        show()
        listOf("Papel", "Sépia", "Noite", "Livro", "Folha inteira", "Rolagem", "Fixos", "Ocultar sozinhos").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        listOf("Pequeno", "Padrão", "Grande", "Muito grande").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        EditionFixture.magazine.topics.forEach { compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed() }

        tap("theme-night")
        tap("mode-scroll")
        tap("controls-hidden")
        tap("text-size-3")
        tap("interest-0")
        val first = EditionFixture.magazine.topics.first().name
        compose.onNodeWithTag("interest-0").assertIsNotSelected()
        recreate()

        val kept = runBlocking { store.settings.get() }
        assertEquals(Theme.NIGHT, kept.theme)
        assertEquals(ReadingMode.SCROLL, kept.readingMode)
        assertEquals(Controls.HIDDEN, kept.controls)
        assertEquals(1.3, kept.textSize, 0.001)
        compose.onNodeWithTag("theme-night").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("mode-scroll").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("controls-hidden").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("text-size-3").performScrollTo().assertIsSelected()
        val topic = runBlocking { store.magazines.all().first().topics.first { it.name == first } }
        assertEquals(0.2, topic.weight, 0.001)
    }

    @Test
    fun theRowsOpenTheirOwnScreens() {
        show()
        tap("settings-generation")
        compose.onNodeWithTag("screen-generation").assertIsDisplayed()
        compose.onNodeWithText("A geração de edições aparece aqui.").assertIsDisplayed()
    }

    @Test
    fun theProvidersRowOpensItsRoute() {
        show()
        tap("settings-ai")
        compose.onNodeWithTag("screen-ai-placeholder").assertIsDisplayed()
    }
}
