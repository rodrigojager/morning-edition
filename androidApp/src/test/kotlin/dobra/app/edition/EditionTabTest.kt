package dobra.app.edition

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.shell.AppTheme
import dobra.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditionTabTest {
    @get:Rule
    val compose = createComposeRule()

    private val magazine = EditionFixture.magazine
    private val edition = EditionFixture.edition
    private val stories = edition.items.filter { it.kind == ItemKind.STORY }

    private fun show() = compose.setContent {
        AppTheme { EditionTab(magazine, edition, onBackToLibrary = {}, onPreviousEdition = null) }
    }

    private fun blockTags(chapterTag: String): List<String> =
        compose.onAllNodes(
            SemanticsMatcher("block tag") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("block-") == true
            } and hasAnyAncestor(hasTestTag(chapterTag)),
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    @Test
    fun storyShowsItsBlocksInOrder() {
        show()
        val story = stories[0]
        val chapter = "chapter-${story.id}"
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag(chapter))

        val drawn = setOf("title", "summary", "section", "paragraph", "list", "note", "sources")
        val expected = story.blocks.map { it.type }.filter { it in drawn }.map { "block-$it" }
        assertEquals(expected, blockTags(chapter))
        assertTrue(expected.containsAll(listOf("block-title", "block-summary", "block-section", "block-list", "block-note", "block-sources")))

        val titleText = story.blocks.first { it.type == "title" }.text("text")!!
        compose.onNode(hasText(titleText) and hasAnyAncestor(hasTestTag("block-title"))).assertExists()
        story.blocks.filter { it.type == "section" }.forEach {
            compose.onNode(hasText(it.text("text")!!) and hasAnyAncestor(hasTestTag(chapter))).assertExists()
        }
    }

    @Test
    fun endMarkComesAfterTheLastChapter() {
        val pages = pagesOf(edition)
        assertEquals(Page.End, pages.last())
        assertTrue(pages.dropLast(1).last() is Page.Backstage)
        assertTrue(pages.indexOfLast { it is Page.Chapter } < pages.indexOf(Page.End))

        show()
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag("end-mark"))
        compose.onNodeWithTag("end-mark").assertIsDisplayed()
        compose.onNodeWithTag("end-library").assertExists()
        compose.onNodeWithTag("end-previous").assertDoesNotExist()
    }

    @Test
    fun contentsListsEightStoriesAndOpensOne() {
        assertEquals(8, stories.size)
        show()
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag("toc-${stories[7].id}"))
        stories.forEach { story ->
            compose.onNodeWithTag("toc-${story.id}").assertExists()
            compose.onNode(hasText(story.title()) and hasTestTag("toc-${story.id}")).assertExists()
            val minutes = story.readMin()!!
            compose.onNode(hasText("$minutes min") and hasTestTag("toc-${story.id}")).assertExists()
        }

        val target = stories[3]
        compose.onNodeWithTag("toc-${target.id}").performClick()
        compose.waitForIdle()
        val top = compose.onNodeWithTag("chapter-${target.id}").getUnclippedBoundsInRoot().top
        val listTop = compose.onNodeWithTag("edition-scroll").getUnclippedBoundsInRoot().top
        assertTrue("chapter should be at the top, was ${top - listTop}", top - listTop < 2.dp)
        compose.onNodeWithTag("toc").assertDoesNotExist()
    }

    @Test
    fun scrollPositionSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppTheme { EditionTab(magazine, edition, onBackToLibrary = {}, onPreviousEdition = null) }
        }
        val target = stories[4]
        compose.onNodeWithTag("cover").assertExists()
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag("chapter-${target.id}"))
        compose.onNodeWithTag("cover").assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag("cover").assertDoesNotExist()
        compose.onNodeWithTag("chapter-${target.id}").assertExists()
    }
}
