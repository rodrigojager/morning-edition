package dobra.store

import dobra.model.Edition
import dobra.model.EditionState
import dobra.model.Item
import dobra.model.Magazine
import kotlinx.coroutines.flow.Flow

/** Keys of the data model: an edition is `<magazine>/<date>`, an item is `<magazine>/<date>/<item>`. */
object Keys {
    fun edition(magazine: String, date: String) = "$magazine/$date"
    fun item(magazine: String, date: String, item: String) = "$magazine/$date/$item"
    fun item(editionKey: String, item: String) = "$editionKey/$item"
}

/** One line of the edition index: enough to list editions without opening their files. */
data class EditionSummary(
    val key: String,
    val magazine: String,
    val date: String,
    val number: Int,
    val recipeVersion: Int,
    val state: EditionState,
    /** Ids of the items of kind story, in order. */
    val storyIds: List<String>,
)

/** A saved item with its own copy, independent of the edition it came from. */
data class SavedItem(val key: String, val item: Item)

enum class Feedback { MORE, LESS }

enum class Theme { PAPER, SEPIA, NIGHT }
enum class ReadingMode { BOOK, SHEET, SCROLL }
enum class Controls { PINNED, HIDDEN }

/** Device-wide settings. `textSize` is a scale factor, 1.0 being the default. */
data class DeviceSettings(
    val theme: Theme = Theme.PAPER,
    val textSize: Double = 1.0,
    val readingMode: ReadingMode = ReadingMode.BOOK,
    val controls: Controls = Controls.PINNED,
)

/**
 * Recipes of the magazines. Every function is safe to call from any coroutine; observers emit again after each
 * change made through this store.
 */
interface MagazineRepository {
    fun observeAll(): Flow<List<Magazine>>
    suspend fun all(): List<Magazine>
    suspend fun get(id: String): Magazine?

    /**
     * Stores a recipe. A new magazine keeps the version it carries; an existing one whose content changed gets its
     * stored version plus one (the incoming version is ignored); an unchanged one is left alone. Returns what was stored.
     */
    suspend fun save(magazine: Magazine): Magazine

    /** Removes the magazine, its editions and the marks on them. Saved items stay. */
    suspend fun delete(id: String)
}

/** Editions: an index in SQLite and one JSON file per edition. */
interface EditionRepository {
    fun observe(magazine: String): Flow<List<EditionSummary>>

    /** Editions of a magazine, newest date first; deleted ones are not listed. */
    suspend fun list(magazine: String): List<EditionSummary>
    suspend fun get(key: String): Edition?

    /** Stores an edition. Returns false (and stores nothing) when the reader deleted that edition before. */
    suspend fun put(edition: Edition): Boolean

    /** Deletes the edition's content and records the deletion. Saved items keep their own copy. */
    suspend fun delete(key: String)
    suspend fun isDeleted(key: String): Boolean
}

/** The reader's marks, kept apart from the content. */
interface MarksRepository {
    fun observeSaved(): Flow<List<SavedItem>>
    /** Saved items, most recently saved first. */
    suspend fun saved(): List<SavedItem>
    suspend fun isSaved(itemKey: String): Boolean
    /** Saves a copy of [item] under the key of the item inside [editionKey]. */
    suspend fun save(editionKey: String, item: Item)
    suspend fun unsave(itemKey: String)

    suspend fun setStoryRead(itemKey: String, read: Boolean = true)
    /** Keys of the stories read inside an edition. */
    suspend fun readStories(editionKey: String): Set<String>

    suspend fun setEditionRead(editionKey: String, read: Boolean = true)
    suspend fun isEditionRead(editionKey: String): Boolean

    suspend fun setOrder(editionKey: String, itemIds: List<String>?)
    suspend fun order(editionKey: String): List<String>?

    suspend fun setFeedback(itemKey: String, feedback: Feedback?)
    suspend fun feedback(itemKey: String): Feedback?
    /** Feedback given on any item of a magazine, by item key. */
    suspend fun feedbackOf(magazine: String): Map<String, Feedback>

    suspend fun setInterestOff(magazine: String, topic: String, off: Boolean)
    suspend fun interestsOff(magazine: String): Set<String>
}

interface SettingsRepository {
    fun observe(): Flow<DeviceSettings>
    suspend fun get(): DeviceSettings
    suspend fun set(settings: DeviceSettings)
}

/** Derived numbers over editions and marks. */
interface UnreadRepository {
    fun observe(magazine: String): Flow<Int>

    /**
     * Editions of the magazine that still wait for the reader: published, not deleted, not marked as read, and with
     * at least one story not read yet.
     */
    suspend fun count(magazine: String): Int
}

/** Everything kept on the phone. Create it with [openStore]. */
class Store internal constructor(
    val magazines: MagazineRepository,
    val editions: EditionRepository,
    val marks: MarksRepository,
    val settings: SettingsRepository,
    val unread: UnreadRepository,
    private val closer: () -> Unit,
) {
    fun close() = closer()
}
