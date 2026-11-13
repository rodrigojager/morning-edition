package dobra.app.edition.paged

import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
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
import dobra.app.edition.FakeMarks
import dobra.app.edition.ImageLoader
import dobra.app.edition.LocalImageLoader
import dobra.store.Keys
import dobra.store.Feedback
import androidx.compose.ui.test.performScrollTo
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val magazine = EditionFixture.magazine
    private val edition = EditionFixture.edition
    private val stories = edition.items.filter { it.kind == ItemKind.STORY }
    private var mode by mutableStateOf(ReadingMode.BOOK)
    private var previewView: android.view.View? = null

    private fun show(start: ReadingMode = ReadingMode.BOOK, marks: FakeMarks? = null) {
        mode = start
        compose.setContent {
            previewView = androidx.compose.ui.platform.LocalView.current
            AppTheme {
                CompositionLocalProvider(LocalImageLoader provides ImageLoader { null }) {
                    ReaderScreen(magazine, edition, mode, marks = marks, onMode = { mode = it }, onBackToLibrary = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = tag == "pager-label").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()

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
        compose.onNodeWithTag("reader-contents").performClick()
        compose.waitForIdle()
        if (mode != ReadingMode.SCROLL) {
            repeat(8) {
                if (tagCount("toc-$id") == 0) {
                    compose.onNodeWithTag("page-next").performClick()
                    compose.waitForIdle()
                }
            }
        }
        val entry = compose.onNodeWithTag("toc-$id")
        if (mode == ReadingMode.SCROLL) entry.performScrollTo()
        entry.performClick()
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w384dp-h800dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        compose.onNodeWithTag("cover-title").assertIsDisplayed()
        compose.onNodeWithTag("cover-summary").assertIsDisplayed()
        assertCoverLeadFits()
        val cover = compose.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("cover-title").getUnclippedBoundsInRoot()
        assertTrue("cover must not push the lead to the bottom", title.top < cover.top + (cover.bottom - cover.top) * 0.75f)
        compose.onNodeWithTag("cover-visual").assertIsDisplayed()
        preview("reader-cover")
    }

    @Test
    @Config(qualifiers = "w384dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactCoverKeepsItsLeadOnThePageWithLargerText() {
        compose.setContent {
            previewView = androidx.compose.ui.platform.LocalView.current
            AppTheme(textScale = 1.3f) {
                ReaderScreen(magazine, edition, ReadingMode.SHEET, onMode = {}, onBackToLibrary = {})
            }
        }
        compose.waitForIdle()
        assertCoverLeadFits()
        preview("reader-cover-compact")
    }

    private fun assertCoverLeadFits() {
        val cover = compose.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        val lead = compose.onNodeWithTag("cover-title").getUnclippedBoundsInRoot()
        assertTrue("lead must fit on the cover: $lead in $cover", lead.top >= cover.top && lead.bottom <= cover.bottom)
    }

    private fun preview(name: String) {
        val directory = System.getenv("DOBRA_READER_PREVIEWS") ?: return
        compose.runOnIdle {
            val view = checkNotNull(previewView)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = File(directory, "$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun contentsShortcutReachesTheLastNoteAndFeedbackFromAnyReadingMode() {
        val marks = FakeMarks()
        show(marks = marks)
        assertTrue("cover and hidden measurement must not mark articles", marks.read.isEmpty())
        val note = edition.items.last { it.kind == ItemKind.NOTE }
        openFromContents(note.id)
        assertStoryOnScreen(note.id)
        assertTrue(Keys.item(edition.key, note.id) in marks.read)
        val ask = compose.onNodeWithTag("ask-${note.id}").getUnclippedBoundsInRoot()
        val save = compose.onNodeWithTag("save-${note.id}").getUnclippedBoundsInRoot()
        assertEquals(ask.bottom - ask.top, save.bottom - save.top)
        assertEquals(ask.right - ask.left, save.right - save.left)
        preview("reader-note")
        compose.onNodeWithTag("more-${note.id}").performClick()
        compose.waitForIdle()
        assertEquals(Feedback.MORE, marks.feedbacks[Keys.item(edition.key, note.id)])
        openFromContents(stories.first().id)
        assertStoryOnScreen(stories.first().id)
        compose.onNodeWithTag("mode-chip").performClick()
        compose.waitForIdle()
        assertEquals(ReadingMode.SCROLL, mode)
        openFromContents(note.id)
        assertStoryOnScreen(note.id)
        openFromContents(stories.first().id)
        assertStoryOnScreen(stories.first().id)
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
