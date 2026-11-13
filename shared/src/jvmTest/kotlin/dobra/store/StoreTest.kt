package dobra.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dobra.model.CollectorInfo
import dobra.model.Edition
import dobra.model.EditionState
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.Magazine
import dobra.model.Topic
import dobra.seed.Seed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val root: File = Files.createTempDirectory("dobra-store-test").toFile()
    private val opened = mutableListOf<Store>()

    // Gradle runs jvmTest with the module directory (shared/) as the working directory.
    private val seedDir = File("seed")
    private val seed = {
        SeedContent(
            Seed.magazine(File(seedDir, Seed.MAGAZINE_FILE).readText()),
            Seed.edition(File(seedDir, Seed.EDITION_FILE).readText()),
        )
    }

    private fun open(withSeed: Boolean = true): Store = runBlocking {
        openStore(root.path, if (withSeed) seed else null).also { opened += it }
    }

    private fun reopen(store: Store, withSeed: Boolean = true): Store {
        store.close()
        opened.remove(store)
        return open(withSeed)
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun edition(magazine: String, date: String, stories: Int = 2, version: Int = 1, state: EditionState = EditionState.PUBLISHED) =
        Edition(
            magazine = magazine, date = date, number = 1, recipeVersion = version, state = state,
            items = (1..stories).map { Item("s$it", ItemKind.STORY, emptyList()) } + Item("n1", ItemKind.NOTE, emptyList()),
        )

    @Test
    fun firstStartImportsOneMagazineWithOneEditionAndSecondStartImportsNothing() = runBlocking {
        var store = open()
        assertEquals(1, store.magazines.all().size)
        val magazine = store.magazines.all().single()
        assertEquals(1, store.editions.list(magazine.id).size)
        val key = store.editions.list(magazine.id).single().key
        assertEquals(14, store.editions.get(key)!!.items.size)

        store = reopen(store)
        assertEquals(1, store.magazines.all().size)
        assertEquals(1, store.editions.list(magazine.id).size)

        // Even after the reader deletes everything, a later start does not bring the demonstration back.
        store.magazines.delete(magazine.id)
        store = reopen(store)
        assertEquals(0, store.magazines.all().size)
    }

    @Test
    fun upgradingTheOldStarterFillsOnlyMissingCollectorsAndDoesNotRestoreDeletedContent() = runBlocking {
        var store = open(withSeed = false)
        val starter = seed()
        val custom = CollectorInfo("function collect(){ return { records:[] }; }", 8, "2026-11-13", "reader")
        val sources = starter.magazine.sources.dropLast(1).mapIndexed { index, source ->
            source.copy(collector = if (index == 0) custom else null)
        }.map { if (it.id == "dev") it.copy(url = "https://dev.to/custom") else it }
        val edited = store.magazines.save(starter.magazine.copy(prompt = "Only practical tools, no opinion.", sources = sources))
        store.editions.put(starter.edition)
        store.editions.delete(starter.edition.key)
        store.close()
        opened.remove(store)
        // State of an installation before starter_collectors_v1 existed.
        BundledSQLiteDriver().open(File(root, "dobra.db").path).use { db ->
            db.prepare("INSERT INTO setting (key, value) VALUES ('seeded', '1')").use { it.step() }
        }
        store = open()
        val upgraded = store.magazines.get(edited.id)!!
        assertEquals(edited.prompt, upgraded.prompt)
        assertEquals(edited.sources.map { it.id }, upgraded.sources.map { it.id })
        assertEquals(custom, upgraded.sources.first().collector)
        assertNull(upgraded.sources.first { it.id == "dev" }.collector)
        assertTrue(upgraded.sources.filter { it.id != "dev" }.all { it.collector != null })
        assertTrue(store.editions.list(edited.id).isEmpty())
        assertTrue(store.editions.isDeleted(starter.edition.key))
        assertEquals(upgraded, reopen(store).magazines.get(edited.id))
    }

    @Test
    fun savedItemKeepsItsOwnCopyAfterItsEditionIsDeleted() = runBlocking {
        val store = open()
        val mag = store.magazines.all().single()
        val summary = store.editions.list(mag.id).single()
        val full = store.editions.get(summary.key)!!
        val item = full.items.first()
        store.marks.save(summary.key, item)

        store.editions.delete(summary.key)
        assertNull(store.editions.get(summary.key))
        assertTrue(store.editions.list(mag.id).isEmpty())

        val back = reopen(store).marks.saved().single()
        assertEquals(Keys.item(summary.key, item.id), back.key)
        assertEquals(item, back.item)
        assertTrue(back.item.blocks.size > 1)
    }

    @Test
    fun everyMarkIsKeptUnderItsKeyAndReadBackAfterReopening() = runBlocking {
        var store = open(withSeed = false)
        store.magazines.save(Magazine(id = "m", name = "M", topics = listOf(Topic("a"), Topic("b"))))
        store.editions.put(edition("m", "2026-01-01"))
        store.editions.put(edition("m", "2026-01-02"))
        val item = Item("s1", ItemKind.STORY, emptyList())

        store.marks.save("m/2026-01-01", item)
        store.marks.setStoryRead("m/2026-01-01/s1")
        store.marks.setEditionRead("m/2026-01-02")
        store.editions.delete("m/2026-01-01")
        store.marks.setFeedback("m/2026-01-02/s2", Feedback.LESS)
        store.marks.setFeedback("m/2026-01-02/s1", Feedback.MORE)
        store.marks.setInterestOff("m", "a", true)
        store.marks.setOrder("m/2026-01-02", listOf("s2", "s1"))
        val settings = DeviceSettings(Theme.NIGHT, 1.3, ReadingMode.SCROLL, Controls.HIDDEN)
        store.settings.set(settings)

        store = reopen(store, withSeed = false)
        assertTrue(store.marks.isSaved("m/2026-01-01/s1"))
        assertEquals(setOf("m/2026-01-01/s1"), store.marks.readStories("m/2026-01-01"))
        assertTrue(store.marks.isEditionRead("m/2026-01-02"))
        assertFalse(store.marks.isEditionRead("m/2026-01-01"))
        assertTrue(store.editions.isDeleted("m/2026-01-01"))
        assertFalse(store.editions.isDeleted("m/2026-01-02"))
        assertEquals(Feedback.LESS, store.marks.feedback("m/2026-01-02/s2"))
        assertEquals(mapOf("m/2026-01-02/s2" to Feedback.LESS, "m/2026-01-02/s1" to Feedback.MORE), store.marks.feedbackOf("m"))
        assertEquals(setOf("a"), store.marks.interestsOff("m"))
        assertEquals(listOf("s2", "s1"), store.marks.order("m/2026-01-02"))
        assertEquals(settings, store.settings.get())

        // A deleted edition cannot come back through a later collection.
        assertFalse(store.editions.put(edition("m", "2026-01-01")))

        // Marks can be switched off again.
        store.marks.setInterestOff("m", "a", false)
        store.marks.setFeedback("m/2026-01-02/s2", null)
        store.marks.unsave("m/2026-01-01/s1")
        assertTrue(store.marks.interestsOff("m").isEmpty())
        assertNull(store.marks.feedback("m/2026-01-02/s2"))
        assertFalse(store.marks.isSaved("m/2026-01-01/s1"))
    }

    @Test
    fun defaultSettingsWhenNothingWasChosen() = runBlocking {
        assertEquals(DeviceSettings(), open(withSeed = false).settings.get())
    }

    @Test
    fun unreadCountOfAMagazine() = runBlocking {
        val store = open(withSeed = false)
        store.magazines.save(Magazine(id = "m", name = "M"))
        store.magazines.save(Magazine(id = "other", name = "O"))
        store.editions.put(edition("m", "2026-01-01")) // stories s1, s2
        store.editions.put(edition("m", "2026-01-02"))
        store.editions.put(edition("m", "2026-01-03"))
        store.editions.put(edition("m", "2026-01-04", state = EditionState.WRITTEN)) // not published: never counts
        store.editions.put(edition("other", "2026-01-01"))
        assertEquals(3, store.unread.count("m"))
        assertEquals(1, store.unread.count("other"))

        store.marks.setStoryRead("m/2026-01-01/s1")
        assertEquals(3, store.unread.count("m")) // one story of two is not enough
        store.marks.setStoryRead("m/2026-01-01/s2")
        assertEquals(2, store.unread.count("m")) // every story read
        store.marks.setEditionRead("m/2026-01-02")
        assertEquals(1, store.unread.count("m")) // marked as read
        store.editions.delete("m/2026-01-03")
        assertEquals(0, store.unread.count("m")) // deleted never counts
        assertEquals(1, store.unread.count("other"))
        assertEquals(0, store.unread.observe("m").first())
    }

    @Test
    fun editingARecipeRaisesItsVersionButAnExistingEditionKeepsItsOwn() = runBlocking {
        val store = open(withSeed = false)
        val first = store.magazines.save(Magazine(id = "m", name = "M"))
        assertEquals(1, first.version)
        store.editions.put(edition("m", "2026-01-01", version = first.version))

        assertEquals(1, store.magazines.save(first).version) // nothing changed: same version
        val edited = store.magazines.save(first.copy(name = "New name", version = 99))
        assertEquals(2, edited.version)
        assertEquals(2, store.magazines.get("m")!!.version)
        assertEquals("New name", store.magazines.get("m")!!.name)
        assertEquals(3, store.magazines.save(edited.copy(keywords = listOf("kotlin"))).version)

        assertEquals(1, store.editions.list("m").single().recipeVersion)
        assertEquals(1, store.editions.get("m/2026-01-01")!!.recipeVersion)
    }

    @Test
    fun deletingAMagazineRemovesItsEditionsAndKeepsSavedItems() = runBlocking {
        val store = open(withSeed = false)
        store.magazines.save(Magazine(id = "m", name = "M"))
        store.magazines.save(Magazine(id = "other", name = "O"))
        store.editions.put(edition("m", "2026-01-01"))
        store.editions.put(edition("other", "2026-01-01"))
        val item = Item("s1", ItemKind.STORY, emptyList())
        store.marks.save("m/2026-01-01", item)
        store.marks.setStoryRead("m/2026-01-01/s2")
        store.marks.setInterestOff("m", "a", true)

        store.magazines.delete("m")

        assertNull(store.magazines.get("m"))
        assertNotNull(store.magazines.get("other"))
        assertTrue(store.editions.list("m").isEmpty())
        assertNull(store.editions.get("m/2026-01-01"))
        assertFalse(File(root, "editions/m/2026-01-01.json").exists())
        assertEquals(1, store.editions.list("other").size)
        assertEquals(listOf("m/2026-01-01/s1"), store.marks.saved().map { it.key })
        assertTrue(store.marks.readStories("m/2026-01-01").isEmpty())
        assertTrue(store.marks.interestsOff("m").isEmpty())
    }

    @Test
    fun observersEmitAgainAfterAChange() = runBlocking {
        val store = open(withSeed = false)
        assertTrue(store.magazines.observeAll().first().isEmpty())
        store.magazines.save(Magazine(id = "m", name = "M"))
        assertEquals(1, store.magazines.observeAll().first().size)
    }
}
