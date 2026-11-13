package dobra.app.turn

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.edition.paged.ReaderScreen
import dobra.app.shell.AppTheme
import dobra.store.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The book's page turns over the spine where the device draws in 3D, and slides where it does not. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w820dp-h1000dp")
class BookTurnScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var mode by mutableStateOf(ReadingMode.BOOK)

    private fun show(turn3d: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalPageTurn3d provides turn3d) {
                AppTheme {
                    ReaderScreen(EditionFixture.magazine, EditionFixture.edition, mode, onMode = { mode = it }, onBackToLibrary = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tagCount(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** The first page of the spread shown by the pager, from `págs. 3–4 de 40`. */
    private fun firstPage(): Int {
        val text = compose.onNodeWithTag("pager-count").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
        return Regex("""págs\. (\d+)–\d+ de \d+""").matchEntire(text)?.groupValues?.get(1)?.toInt() ?: error("counter was '$text'")
    }

    private fun turnKind() = compose.onNodeWithTag("pages").fetchSemanticsNode().config.getOrNull(PageTurnKindKey)

    /** Runs [change] with the clock held and checks the spread slides from screen [from] to [to]: when the new screen
     *  comes in, the old one is still there, a frame later too; at the end only the new one is left. */
    private fun assertSlides(from: Int, to: Int, change: () -> Unit) {
        compose.mainClock.autoAdvance = false
        change()
        var frames = 0
        while (tagCount("screen-$to") == 0 && frames < 30) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("screen $from mid-slide", 1, tagCount("screen-$from"))
        assertEquals("screen $to mid-slide", 1, tagCount("screen-$to"))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, tagCount("screen-$from"))
        assertEquals(1, tagCount("screen-$to"))
        assertEquals(to * 2 + 1, firstPage())
    }

    @Test
    fun withThe3dTurnSwitchedOffTheBookStillChangesPagesAsASlide() {
        show(turn3d = false)
        assertNull("no 3D turn", turnKind())
        assertEquals(1, tagCount("spine"))
        assertSlides(0, 1) { compose.onNodeWithTag("page-next").performClick() }
        assertSlides(1, 2) { compose.onNodeWithTag("pages").performTouchInput { swipeLeft() } }
        assertSlides(2, 1) { compose.onNodeWithTag("pages").performTouchInput { swipeRight() } }
        assertSlides(1, 0) { compose.onNodeWithTag("page-prev").performClick() }
    }

    @Test
    fun whereThe3dTurnCannotBeDrawnTheBookSlides() {
        // the reader allows the turn; Robolectric has no OpenGL, so the app finds it unavailable and slides
        show(turn3d = true)
        assertNull(turnKind())
        assertSlides(0, 1) { compose.onNodeWithTag("page-next").performClick() }
        assertSlides(1, 0) { compose.onNodeWithTag("pages").performTouchInput { swipeRight() } }
    }

    @Test
    fun whereThe3dTurnCanBeDrawnTheBookUsesTheHingedPageAndTheSheetTheRolledOne() {
        Turn3d.assumeForTests = true
        try {
            show(turn3d = true)
            assertEquals(TurnKind.BOOK, turnKind())
            mode = ReadingMode.SHEET
            compose.waitForIdle()
            assertEquals(TurnKind.SHEET, turnKind())
            mode = ReadingMode.BOOK
            compose.waitForIdle()
            assertEquals(TurnKind.BOOK, turnKind())
        } finally {
            Turn3d.assumeForTests = null
        }
    }

    @Test
    fun turningForwardTheNextSpreadIsOnTheBackOfTheTurningPage() {
        // the book's page shows on its back the left page of the screen reached (BookStrip.backUv), and lies over the
        // screen being left: forward, that is the next spread; back, the spread being left
        assertEquals(3 to 4, turnFaces(from = 3, to = 4, dir = 1))
        assertEquals(3 to 4, turnFaces(from = 4, to = 3, dir = -1))
    }
}
