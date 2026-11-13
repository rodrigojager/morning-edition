package dobra.app.edition.paged

import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.shell.AppTheme
import dobra.model.ItemKind
import dobra.store.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val magazine = EditionFixture.magazine
    private val edition = EditionFixture.edition
    private val stories = edition.items.filter { it.kind == ItemKind.STORY }
    private var mode by mutableStateOf(ReadingMode.BOOK)

    private fun show(start: ReadingMode = ReadingMode.BOOK) {
        mode = start
        compose.setContent {
            AppTheme { ReaderScreen(magazine, edition, mode, onMode = { mode = it }, onBackToLibrary = {}) }
        }
        compose.waitForIdle()
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()

    private fun count() = text("pager-count")

    private fun chip() = text("mode-chip")

    private fun tagCount(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun shortCount(): Pair<Int, Int> {
        val m = Regex("""(\d+)/(\d+)""").matchEntire(count()) ?: error("counter was '${count()}'")
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** The chapter of the story is on screen: one of its page nodes (paged) or its chapter node (scroll) is displayed. */
    private fun assertStoryOnScreen(id: String) {
        val shown = compose.onAllNodesWithTag("chapter-$id").fetchSemanticsNodes().indices.any { i ->
            runCatching { compose.onAllNodesWithTag("chapter-$id")[i].assertIsDisplayed() }.isSuccess
        }
        assertTrue("story $id should be on screen in ${chip()}", shown)
    }

    private fun openFromContents(id: String) {
        // The contents are the page after the cover.
        if (tagCount("toc-$id") == 0) compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("toc-$id").performClick()
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w384dp-h800dp")
    fun narrowShowsOnePagePerScreenAndTheShortCounter() {
        show()
        assertEquals("Folha", chip())
        assertEquals(1, tagCount("page"))
        assertEquals(0, tagCount("page-left"))
        assertEquals(0, tagCount("spine"))
        val (n, total) = shortCount()
        assertEquals(1, n)
        assertTrue("a whole edition takes many pages, got $total", total > 20)
        compose.onNodeWithTag("cover").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w820dp-h1000dp")
    fun wideShowsTwoPagesWithTheSpineBetween() {
        show()
        assertEquals("Livro", chip())
        val left = compose.onNodeWithTag("page-left").getUnclippedBoundsInRoot()
        val spine = compose.onNodeWithTag("spine").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("page-right").getUnclippedBoundsInRoot()
        assertTrue(left.right <= spine.left && spine.right <= right.left)
        assertEquals(0, tagCount("page"))

        compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        val m = Regex("""págs\. 3–4 de (\d+)""").matchEntire(count()) ?: error("counter was '${count()}'")
        val total = m.groupValues[1].toInt()
        assertTrue(total > 10 && total % 2 == 0)
        compose.onNodeWithTag("page-left").assertIsDisplayed()
        compose.onNodeWithTag("page-right").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w820dp-h1000dp")
    fun theChipGoesBookSheetScrollAtWideWidthAndKeepsTheChapter() {
        show()
        val story = stories[3]
        openFromContents(story.id)
        assertEquals(story.shortLabel(), text("pager-label"))
        assertStoryOnScreen(story.id)

        assertEquals("Livro", chip())
        listOf("Folha", "Rolagem", "Livro").forEach { next ->
            compose.onNodeWithTag("mode-chip").performClick()
            compose.waitForIdle()
            assertEquals(next, chip())
            assertEquals(story.shortLabel(), text("pager-label"))
            assertStoryOnScreen(story.id)
        }
        assertEquals(ReadingMode.BOOK, mode)
    }

    @Test
    @Config(qualifiers = "w384dp-h800dp")
    fun theChipGoesSheetScrollAtNarrowWidthAndKeepsTheChapter() {
        show()
        val story = stories[5]
        openFromContents(story.id)
        // a few pages into the story
        compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        assertStoryOnScreen(story.id)

        listOf("Folha", "Rolagem", "Folha", "Rolagem").zipWithNext().forEach { (now, next) ->
            assertEquals(now, chip())
            compose.onNodeWithTag("mode-chip").performClick()
            compose.waitForIdle()
            assertEquals(next, chip())
            assertEquals(story.shortLabel(), text("pager-label"))
            assertStoryOnScreen(story.id)
        }
    }

    @Test
    @Config(qualifiers = "w384dp-h800dp")
    fun buttonsAndSwipesMoveOneScreenAndTheEndsDisableTheirButton() {
        show()
        compose.onNodeWithTag("page-prev").assertIsNotEnabled()
        compose.onNodeWithTag("page-next").assertIsEnabled()
        val total = shortCount().second

        compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        assertEquals(2 to total, shortCount())
        compose.onNodeWithTag("page-prev").assertIsEnabled()

        compose.onNodeWithTag("pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(3 to total, shortCount())

        compose.onNodeWithTag("pages").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(2 to total, shortCount())

        compose.onNodeWithTag("page-prev").performClick()
        compose.waitForIdle()
        assertEquals(1 to total, shortCount())
        compose.onNodeWithTag("page-prev").assertIsNotEnabled()

        repeat(total - 1) {
            compose.onNodeWithTag("page-next").performClick()
            compose.waitForIdle()
        }
        assertEquals(total to total, shortCount())
        compose.onNodeWithTag("page-next").assertIsNotEnabled()
        compose.onNodeWithTag("end-mark").assertExists()
        compose.onNodeWithTag("pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(total to total, shortCount())
    }

    @Test
    @Config(qualifiers = "w384dp-h800dp")
    fun aPageSlidesWhenAnimationsAreOn() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page-next").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // mid-slide both screens are there
        assertEquals(1, tagCount("screen-0"))
        assertEquals(1, tagCount("screen-1"))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, tagCount("screen-0"))
    }

    @Test
    @Config(qualifiers = "w384dp-h800dp")
    fun withoutAnimationsThePageChangesAtOnce() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            show()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("page-next").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertEquals(0, tagCount("screen-0"))
            assertEquals(1, tagCount("screen-1"))
            compose.onNodeWithTag("screen-1").assertIsDisplayed()
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }
}
