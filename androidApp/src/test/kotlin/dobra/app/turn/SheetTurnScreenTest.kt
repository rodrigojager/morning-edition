package dobra.app.turn

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The single sheet's page changes where the 3D turn is off or cannot be drawn: the same changes, as a slide. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w384dp-h800dp")
class SheetTurnScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(turn3d: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalPageTurn3d provides turn3d) {
                AppTheme {
                    ReaderScreen(EditionFixture.magazine, EditionFixture.edition, ReadingMode.SHEET, onMode = {}, onBackToLibrary = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tagCount(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** The page number shown by the pager, from `n/total`. */
    private fun pageNumber(): Int {
        val text = compose.onNodeWithTag("pager-count").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
        return Regex("""(\d+)/\d+""").matchEntire(text)?.groupValues?.get(1)?.toInt() ?: error("counter was '$text'")
    }

    /** Runs [change] with the clock held and checks the page slides from screen [from] to [to]: when the new screen
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
        assertEquals(to + 1, pageNumber())
    }

    @Test
    fun withThe3dTurnSwitchedOffButtonsAndDragsSlide() {
        show(turn3d = false)
        assertSlides(0, 1) { compose.onNodeWithTag("page-next").performClick() }
        assertSlides(1, 2) { compose.onNodeWithTag("pages").performTouchInput { swipeLeft() } }
        assertSlides(2, 1) { compose.onNodeWithTag("pages").performTouchInput { swipeRight() } }
        assertSlides(1, 0) { compose.onNodeWithTag("page-prev").performClick() }
    }

    @Test
    fun whereThe3dTurnCannotBeDrawnThePagesSlide() {
        // the reader allows the turn; Robolectric has no OpenGL, so the app finds it unavailable and slides
        show(turn3d = true)
        assertSlides(0, 1) { compose.onNodeWithTag("page-next").performClick() }
        assertSlides(1, 2) { compose.onNodeWithTag("pages").performTouchInput { swipeLeft() } }
        assertSlides(2, 1) { compose.onNodeWithTag("pages").performTouchInput { swipeRight() } }
        assertSlides(1, 0) { compose.onNodeWithTag("page-prev").performClick() }
    }

    @Test
    fun aShortDragDoesNotChangeThePage() {
        show(turn3d = false)
        compose.onNodeWithTag("pages").performTouchInput {
            down(center)
            moveBy(Offset(-20f, 0f))
            moveBy(Offset(-20f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, pageNumber())
    }

    @Test
    fun theFirstPageHasNoPreviousToDragTo() {
        show(turn3d = false)
        compose.onNodeWithTag("pages").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, pageNumber())
        assertEquals(1, tagCount("screen-0"))
    }
}
