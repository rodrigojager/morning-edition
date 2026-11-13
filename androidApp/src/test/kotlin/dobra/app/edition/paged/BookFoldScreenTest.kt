package dobra.app.edition.paged

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature as Fold
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import dobra.app.edition.EditionFixture
import dobra.app.edition.ImageLoader
import dobra.app.edition.LocalImageLoader
import dobra.app.shell.AppTheme
import dobra.model.ItemKind
import dobra.store.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The book on a foldable: the spine on the device's fold, and the place kept when the window folds or unfolds. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w820dp-h1000dp")
class BookFoldScreenTest {
    @get:Rule(order = 0)
    val publisher = WindowLayoutInfoPublisherRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val magazine = EditionFixture.magazine
    private val edition = EditionFixture.edition
    private val stories = edition.items.filter { it.kind == ItemKind.STORY }

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = tag == "pager-label").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()

    @Test
    fun aVerticalFoldInTheMiddleCarriesTheSpineAndNoTextRunsAcrossIt() {
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalImageLoader provides ImageLoader { null }) {
                    ReaderScreen(magazine, edition, ReadingMode.BOOK, onMode = {}, onBackToLibrary = {})
                }
            }
        }
        compose.waitForIdle()
        // a hinge 60 pixels wide in the middle of the window: wider than the book's own gutter
        val fold = FoldingFeature(compose.activity, size = 60, state = Fold.State.FLAT, orientation = Fold.Orientation.VERTICAL)
        publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(fold)))
        compose.waitForIdle()

        val root = compose.onRoot().fetchSemanticsNode().positionInWindow
        val foldLeft = fold.bounds.left - root.x
        val foldRight = fold.bounds.right - root.x
        assertEquals("the fold is 60 px wide", 60f, foldRight - foldLeft)

        val spine = boundsOf("spine")
        assertTrue("spine $spine covers the fold $foldLeft..$foldRight", spine.left <= foldLeft && spine.right >= foldRight)
        assertTrue("the left page ends before the fold", boundsOf("page-left").right <= foldLeft)
        assertTrue("the right page starts after the fold", boundsOf("page-right").left >= foldRight)

        val pages = boundsOf("pages")
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.width > 0f && it.top >= pages.top && it.bottom <= pages.bottom }
        assertTrue("the spread shows text, found ${texts.size} pieces", texts.size >= 3)
        texts.forEach { b ->
            assertTrue("text at $b runs across the fold $foldLeft..$foldRight", b.right <= foldLeft || b.left >= foldRight)
        }
    }

    @Test
    fun foldingAndUnfoldingTheWindowKeepsTheChapterOnScreen() {
        var width by mutableStateOf(384.dp)
        var mode by mutableStateOf(ReadingMode.BOOK)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppTheme {
                CompositionLocalProvider(LocalImageLoader provides ImageLoader { null }) {
                    Box(Modifier.width(width).fillMaxHeight()) {
                        ReaderScreen(magazine, edition, mode, onMode = { mode = it }, onBackToLibrary = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val story = stories[4]
        // folded: the single sheet; open the story from the contents and read on one page
        assertEquals("Folha", text("mode-chip"))
        compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("toc-${story.id}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-next").performClick()
        compose.waitForIdle()
        assertStoryOnScreen(story.id, story.shortLabel())

        // unfolded: the book, at the same chapter; then the activity made again, as a real fold does
        width = 820.dp
        compose.waitForIdle()
        assertEquals("Livro", text("mode-chip"))
        assertStoryOnScreen(story.id, story.shortLabel())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Livro", text("mode-chip"))
        assertStoryOnScreen(story.id, story.shortLabel())

        // folded again
        width = 384.dp
        compose.waitForIdle()
        assertEquals("Folha", text("mode-chip"))
        assertStoryOnScreen(story.id, story.shortLabel())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertStoryOnScreen(story.id, story.shortLabel())
        assertEquals(ReadingMode.BOOK, mode)
    }

    /** One of the story's page nodes is displayed and the pager names it. */
    private fun assertStoryOnScreen(id: String, label: String) {
        val nodes = compose.onAllNodesWithTag("chapter-$id")
        val shown = nodes.fetchSemanticsNodes().indices.any { i -> runCatching { nodes[i].assertIsDisplayed() }.isSuccess }
        assertTrue("story $id should be on screen in ${text("mode-chip")}", shown)
        assertEquals(label, text("pager-label"))
    }
}
