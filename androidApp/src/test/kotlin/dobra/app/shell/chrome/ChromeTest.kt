package dobra.app.shell.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.edition.paged.ReaderScreen
import dobra.app.shell.AppShell
import dobra.app.shell.AppTheme
import dobra.app.shell.ShellData
import dobra.model.ItemKind
import dobra.store.Controls
import dobra.store.DeviceSettings
import dobra.store.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private val data = ShellData(EditionFixture.magazine, EditionFixture.edition)
    private var settings by mutableStateOf(DeviceSettings(controls = Controls.HIDDEN, readingMode = ReadingMode.SHEET))

    private fun showShell() {
        compose.setContent { AppShell(data, settings, onSettings = { settings = it }) }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun pagesHeight() = compose.onNodeWithTag("pages").getUnclippedBoundsInRoot().height.value

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()

    private fun waitToHide() {
        compose.mainClock.advanceTimeBy(4_200)
        compose.waitForIdle()
    }

    @Test
    fun fixedControlsNeverLeave() {
        settings = settings.copy(controls = Controls.PINNED)
        showShell()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertTrue(exists("pager") && exists("tab-edition"))
        assertTrue(!exists("top-bar") && !exists("chrome-handle"))
    }

    @Test
    fun hidingControlsLeaveAfterFourSecondsAndGiveThePageTheFullHeight() {
        settings = settings.copy(controls = Controls.PINNED)
        showShell()
        val pinnedHeight = pagesHeight()
        settings = settings.copy(controls = Controls.HIDDEN)
        compose.waitForIdle()
        assertTrue(exists("top-bar") && exists("pager") && exists("tab-edition"))
        val withBars = pagesHeight()
        assertTrue("the page does not shrink for the bars", withBars > pinnedHeight)

        compose.mainClock.advanceTimeBy(3_500)
        compose.waitForIdle()
        assertTrue("still there before four seconds", exists("pager"))
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertTrue(!exists("top-bar") && !exists("pager") && !exists("tab-edition"))
        assertEquals("hiding never changes the layout", withBars, pagesHeight(), 0.5f)
        assertTrue(exists("chrome-handle"))
        assertTrue(text("chrome-handle-text").isNotBlank())
    }

    @Test
    fun aPageTurnRestartsTheCountdown() {
        showShell()
        waitToHide()
        compose.onNodeWithTag("chrome-handle").performClick()
        assertTrue(exists("pager"))
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue("three seconds after the turn they are still there", exists("pager"))
        compose.mainClock.advanceTimeBy(1_300)
        compose.waitForIdle()
        assertTrue(!exists("pager") && !exists("tab-edition"))
        assertTrue(exists("chrome-handle"))
    }

    @Test
    fun aTapOnThePageBringsTheControlsBackButATapOnALinkDoesNot() {
        showShell()
        compose.onNodeWithTag("page-next").performClick() // the contents page, with links
        compose.waitForIdle()
        waitToHide()
        assertTrue(!exists("pager"))
        val id = EditionFixture.edition.items.first { it.kind == ItemKind.STORY }.id
        compose.onNodeWithTag("toc-$id").performClick()
        compose.waitForIdle()
        assertTrue("a link does not bring the controls back", !exists("pager") && !exists("tab-edition"))

        compose.onNodeWithTag("pages").performTouchInput { click(Offset(4f, 4f)) }
        compose.waitForIdle()
        assertTrue("a tap on the page does", exists("pager") && exists("tab-edition") && exists("top-bar"))
        compose.onNodeWithTag("pages").performTouchInput { click(Offset(4f, 4f)) }
        compose.waitForIdle()
        assertTrue("and another one sends them away", !exists("pager"))
    }

    @Test
    fun inScrollModeScrollingDownHidesAndScrollingUpShows() {
        settings = settings.copy(readingMode = ReadingMode.SCROLL)
        showShell()
        assertTrue(exists("pager"))
        compose.onNodeWithTag("edition-scroll").performScrollToIndex(3)
        compose.waitForIdle()
        assertTrue(!exists("pager") && !exists("tab-edition") && exists("chrome-handle"))
        compose.onNodeWithTag("edition-scroll").performScrollToIndex(0)
        compose.waitForIdle()
        assertTrue(exists("pager") && exists("tab-edition"))
    }

    @Test
    fun theCountdownDoesNotRunWithFixedControlsNorOnOtherTabs() {
        settings = settings.copy(controls = Controls.PINNED)
        showShell()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertTrue(exists("tab-edition"))
    }

    @Test
    fun thePinButtonSwitchesBetweenTheTwoModes() {
        var controls by mutableStateOf(Controls.PINNED)
        compose.setContent {
            AppTheme {
                ReaderScreen(
                    EditionFixture.magazine, EditionFixture.edition, ReadingMode.SHEET, onMode = {}, onBackToLibrary = {},
                    controls = controls, onControls = { controls = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pin-button").performClick()
        compose.waitForIdle()
        assertEquals(Controls.HIDDEN, controls)
        assertTrue(exists("top-bar"))
        compose.onNodeWithTag("pin-button").performClick()
        compose.waitForIdle()
        assertEquals(Controls.PINNED, controls)
        assertTrue(!exists("top-bar") && exists("pager"))
    }
}
