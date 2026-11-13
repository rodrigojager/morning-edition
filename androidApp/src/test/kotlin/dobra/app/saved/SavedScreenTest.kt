package dobra.app.saved

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.edition.title
import dobra.app.shell.AppShell
import dobra.app.shell.Routes
import dobra.model.ItemKind
import dobra.store.DeviceSettings
import dobra.store.Keys
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SavedScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val root = Files.createTempDirectory("dobra-saved-test").toFile()
    private val store: Store = runBlocking {
        openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }
    private val edition = EditionFixture.edition
    private val story = edition.items.first { it.kind == ItemKind.STORY }
    private val note = edition.items.first { it.kind == ItemKind.NOTE }
    private val storyKey = Keys.item(edition.key, story.id)
    private val noteKey = Keys.item(edition.key, note.id)

    @After
    fun close() {
        store.close()
        root.deleteRecursively()
    }

    private fun showSaved() {
        runBlocking {
            store.marks.save(edition.key, story)
            store.marks.save(edition.key, note)
        }
        compose.setContent { AppShell(null, DeviceSettings(), onSettings = {}, initialTab = Routes.SAVED, store = store) }
    }

    private fun tag(t: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(t)
    }

    /** Brings a chapter of the lazy reader into view. */
    private fun inReader(t: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("saved-reader").performScrollToNode(hasTestTag(t))
        return tag(t)
    }

    private fun count() = compose.onNodeWithTag("tab-count-saved", useUnmergedTree = true)

    private fun gone(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isEmpty() }

    private fun click(t: String) = tag(t).performScrollTo().performClick()

    private fun readOnlySaved() {
        showSaved()
        click("saved-read-${edition.magazine}")
        tag("saved-reader")
    }

    @Test
    fun savedStoryAndNoteAreListedUnderMagazineAndEditionDateWithTheCountOnTheTab() {
        showSaved()
        tag("saved-mag-${EditionFixture.magazine.id}").assertTextEquals(EditionFixture.magazine.name)
        compose.onNode(hasText(edition.date, substring = true) and hasTestTag("saved-date-${edition.key}")).assertExists()
        tag("saved-title-$storyKey").assertTextEquals(story.title())
        tag("saved-item-$noteKey")
        count().assertTextEquals("2")
    }

    @Test
    fun readingOnlyTheSavedShowsTheStoryAndTheSavedNotesChapterWithoutCoverContentsOrBackstage() {
        readOnlySaved()
        tag("saved-story-${story.id}")
        compose.onNode(hasText(story.title(), substring = true) and hasAnyAncestor(hasTestTag("saved-story-${story.id}"))).assertExists()
        inReader("saved-notes-header").assertTextEquals("Notas salvas")
        inReader("saved-note-${note.id}")
        listOf("cover", "toc", "backstage", "notes-header").forEach {
            assertTrue("$it should not show", compose.onAllNodesWithTag(it).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun savedItemsSurviveDeletingTheEditionAndStillOpen() {
        runBlocking { store.editions.delete(edition.key) }
        readOnlySaved()
        tag("saved-story-${story.id}")
        inReader("saved-note-${note.id}")
        compose.onNodeWithTag("saved-reader").performScrollToNode(hasTestTag("saved-back"))
        click("saved-back")
        tag("saved-item-$storyKey")
        tag("saved-item-$noteKey")
    }

    @Test
    fun readingSavedCollectsEveryEditionOfOnlyTheSelectedMagazine() {
        val older = edition.copy(date = "2026-01-01")
        val secondStory = edition.items.last { it.kind == ItemKind.STORY }
        runBlocking {
            store.marks.save(older.key, secondStory)
            store.marks.save("another/2026-01-02", secondStory.copy(id = "other-magazine"))
        }
        readOnlySaved()
        inReader("saved-story-${secondStory.id}")
        compose.onNodeWithTag("saved-reader").performScrollToNode(hasTestTag("saved-back"))
        click("saved-back")
        tag("saved-read-${edition.magazine}")
        assertTrue(compose.onAllNodesWithTag("saved-read-${edition.key}").fetchSemanticsNodes().isEmpty())
        click("saved-read-another")
        tag("saved-story-other-magazine")
        assertTrue(compose.onAllNodesWithTag("saved-story-${story.id}").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun removingAnItemTakesItOutOfTheList() {
        showSaved()
        click("saved-remove-$noteKey")
        gone("saved-item-$noteKey")
        tag("saved-item-$storyKey")
        count().assertTextEquals("1")
        assertTrue(runBlocking { !store.marks.isSaved(noteKey) })
    }
}
