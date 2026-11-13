package dobra.app.shell

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.R
import dobra.app.edition.EditionFixture
import dobra.app.edition.title
import dobra.store.DeviceSettings
import dobra.store.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val compose = createComposeRule()

    private val data = ShellData(EditionFixture.magazine, EditionFixture.edition)

    @Test
    fun coverShowsTheMagazineAndTheFirstStoryAndTheFiveTabs() {
        compose.setContent { AppShell(data, DeviceSettings(), onSettings = {}) }
        compose.onNode(hasText("Primeira Dobra") and hasAnyAncestor(hasTestTag("cover"))).assertExists()
        val first = EditionFixture.edition.items.first().title()
        compose.onNode(hasText(first) and hasAnyAncestor(hasTestTag("cover"))).assertExists()
        listOf("Edição", "Biblioteca", "Salvos", "Fontes", "Ajustes").forEach {
            compose.onNode(hasText(it) and hasTestTag("tab-${tabRoute(it)}")).assertExists()
        }
    }

    private fun tabRoute(label: String) = when (label) {
        "Edição" -> Routes.EDITION
        "Biblioteca" -> Routes.LIBRARY
        "Salvos" -> Routes.SAVED
        "Fontes" -> Routes.SOURCES
        else -> Routes.SETTINGS
    }

    @Test
    fun tabsSwitchScreens() {
        compose.setContent { AppShell(data, DeviceSettings(), onSettings = {}) }
        compose.onNodeWithTag("tab-library").performClick()
        compose.onNodeWithTag("screen-library").assertExists()
        compose.onNodeWithTag("tab-saved").performClick()
        compose.onNodeWithTag("screen-saved").assertExists()
        compose.onNodeWithTag("tab-sources").performClick()
        compose.onNodeWithTag("screen-sources").assertExists()
        compose.onNodeWithTag("tab-edition").performClick()
        compose.onNodeWithTag("cover").assertExists()
    }

    @Test
    fun themesChangeThePageBackground() {
        var settings by mutableStateOf(DeviceSettings())
        compose.setContent {
            AppShell(data, settings, onSettings = { settings = it }, initialTab = Routes.SETTINGS)
        }
        fun background() = compose.onNodeWithTag("app-root").fetchSemanticsNode().config[PageBackground]

        compose.onNodeWithTag("theme-paper").performClick()
        assertEquals(ReaderPalettes.paper.bg, background())
        compose.onNodeWithTag("theme-sepia").performClick()
        assertEquals(Theme.SEPIA, settings.theme)
        assertEquals(ReaderPalettes.sepia.bg, background())
        compose.onNodeWithTag("theme-night").performClick()
        assertEquals(ReaderPalettes.night.bg, background())
        assertTrue(setOf(ReaderPalettes.paper.bg, ReaderPalettes.sepia.bg, ReaderPalettes.night.bg).size == 3)
    }

    @Test
    fun typefacesAreBundledAndNotFetched() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        listOf(R.font.fraunces, R.font.newsreader, R.font.jetbrains_mono).forEach { id ->
            val bytes = resources.openRawResource(id).use { it.readBytes() }
            assertTrue("font file too small", bytes.size > 100_000)
            // TrueType signature 00 01 00 00
            assertEquals(listOf<Byte>(0, 1, 0, 0), bytes.take(4))
        }
        val sources = File("src/main").walkTopDown().filter {
            it.isFile && (it.extension == "kt" || it.extension == "xml")
        }
        sources.forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} fetches fonts", "fonts.googleapis" in text || "GoogleFont" in text || "gms.fonts" in text)
        }
    }
}
