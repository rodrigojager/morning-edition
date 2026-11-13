package dobra.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dobra.model.DobraJson
import dobra.model.Edition
import dobra.model.EditionState
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.Magazine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** The demonstration content imported once, on the very first start. */
class SeedContent(val magazine: Magazine, val edition: Edition)

/**
 * Opens (creating it if needed) the store kept in the folder [root]: `dobra.db` plus `editions/<magazine>/<date>.json`.
 * When [seed] is given and this folder never imported it, the magazine and edition are imported; later starts import
 * nothing, even if the reader deleted them.
 */
suspend fun openStore(root: String, seed: (() -> SeedContent)? = null): Store {
    val files = editionFilesIn(root)
    val connection = BundledSQLiteDriver().open(pathIn(root, "dobra.db"))
    val core = Core(connection, files)
    core.migrate()
    val store = Store(
        magazines = SqlMagazines(core),
        editions = SqlEditions(core),
        marks = SqlMarks(core),
        settings = SqlSettings(core),
        unread = SqlUnread(core),
        closer = { connection.close() },
    )
    if (seed != null && (core.setting(SEEDED) == null || core.setting(STARTER_COLLECTORS) == null)) {
        val content = seed()
        if (core.setting(SEEDED) == null) {
            store.magazines.save(content.magazine)
            store.editions.put(content.edition)
            core.locked { putSetting(SEEDED, "1") }
        } else {
            // Repair the old starter recipe once. Keep edited instructions, sources, scripts and deletions.
            val current = store.magazines.get(content.magazine.id)
            if (current != null) {
                val bundled = content.magazine.sources.associateBy { it.id }
                store.magazines.save(current.copy(sources = current.sources.map { source ->
                    val starter = bundled[source.id]
                    if (source.collector != null || starter?.collector == null || starter.url != source.url) source
                    else source.copy(collector = starter.collector, lastRun = null, cursor = null, health = starter.health)
                }))
            }
        }
        core.locked { putSetting(STARTER_COLLECTORS, "1") }
    }
    return store
}

private const val SEEDED = "seeded"
private const val STARTER_COLLECTORS = "starter_collectors_v1"
private const val SCHEMA_VERSION = 1

// Mark kinds (the first part of the primary key of the marks table).
internal object Kind {
    const val SAVED = "saved"               // value: the item JSON
    const val READ = "read"                 // key: item key
    const val EDITION_READ = "edition_read" // key: edition key
    const val DELETED = "edition_deleted"   // key: edition key
    const val ORDER = "order"               // key: edition key, value: JSON list of item ids
    const val FEEDBACK = "feedback"         // key: item key, value: more | less
    const val INTEREST_OFF = "interest_off" // key: magazine, value: JSON list of topics
}

/** One SQLite connection guarded by a mutex, the edition files, and a counter that tells observers to read again. */
internal class Core(private val connection: SQLiteConnection, val files: EditionFiles) {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)

    suspend fun <T> locked(block: Core.() -> T): T = mutex.withLock { block() }

    /** Runs [block] inside a transaction and tells observers when it ends. */
    suspend fun <T> write(block: Core.() -> T): T {
        val result = mutex.withLock {
            exec("BEGIN IMMEDIATE")
            try {
                val r = block()
                exec("COMMIT")
                r
            } catch (e: Throwable) {
                exec("ROLLBACK")
                throw e
            }
        }
        changes.value = changes.value + 1
        return result
    }

    fun <T> observe(read: suspend () -> T): Flow<T> = changes.map { read() }

    fun migrate() {
        exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS magazine (id TEXT PRIMARY KEY, position INTEGER NOT NULL, json TEXT NOT NULL)")
        exec(
            "CREATE TABLE IF NOT EXISTS edition_index (key TEXT PRIMARY KEY, magazine TEXT NOT NULL, date TEXT NOT NULL, " +
                "number INTEGER NOT NULL, recipe_version INTEGER NOT NULL, state TEXT NOT NULL, story_ids TEXT NOT NULL)",
        )
        exec("CREATE INDEX IF NOT EXISTS edition_by_magazine ON edition_index (magazine, date)")
        exec("CREATE TABLE IF NOT EXISTS marks (kind TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (kind, key))")
        exec("CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        exec("INSERT OR IGNORE INTO meta (key, value) VALUES ('schema', '$SCHEMA_VERSION')")
    }

    fun exec(sql: String, vararg args: Any?) {
        connection.prepare(sql).use { st ->
            bind(st, args)
            st.step()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (SQLiteStatement) -> T): List<T> =
        connection.prepare(sql).use { st ->
            bind(st, args)
            val out = ArrayList<T>()
            while (st.step()) out += map(st)
            out
        }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Int -> st.bindLong(i + 1, a.toLong())
                is Long -> st.bindLong(i + 1, a)
                else -> st.bindText(i + 1, a.toString())
            }
        }
    }

    suspend fun setting(key: String): String? = locked { settingNow(key) }
    fun settingNow(key: String): String? = query("SELECT value FROM setting WHERE key = ?", key) { it.getText(0) }.firstOrNull()
    fun putSetting(key: String, value: String) = exec("INSERT OR REPLACE INTO setting (key, value) VALUES (?, ?)", key, value)

    fun putMark(kind: String, key: String, value: String = "1") =
        exec("INSERT OR REPLACE INTO marks (kind, key, value) VALUES (?, ?, ?)", kind, key, value)

    fun removeMark(kind: String, key: String) = exec("DELETE FROM marks WHERE kind = ? AND key = ?", kind, key)
    fun mark(kind: String, key: String): String? =
        query("SELECT value FROM marks WHERE kind = ? AND key = ?", kind, key) { it.getText(0) }.firstOrNull()

    /** Marks of a kind whose key is [prefix] followed by anything (prefix includes the trailing `/`). */
    fun marksWithPrefix(kind: String, prefix: String): List<Pair<String, String>> =
        query(
            "SELECT key, value FROM marks WHERE kind = ? AND substr(key, 1, ?) = ?",
            kind, prefix.length, prefix,
        ) { it.getText(0) to it.getText(1) }

    fun removeMarksWithPrefix(kind: String, prefix: String) =
        exec("DELETE FROM marks WHERE kind = ? AND substr(key, 1, ?) = ?", kind, prefix.length, prefix)
}

private fun editionPath(magazine: String, date: String) = "editions/$magazine/$date.json"

private val stringList = ListSerializer(String.serializer())

// ---- magazines ----

internal class SqlMagazines(private val core: Core) : MagazineRepository {
    override fun observeAll(): Flow<List<Magazine>> = core.observe { all() }

    override suspend fun all(): List<Magazine> = core.locked {
        query("SELECT json FROM magazine ORDER BY position") { DobraJson.decodeFromString(Magazine.serializer(), it.getText(0)) }
    }

    override suspend fun get(id: String): Magazine? = core.locked { getNow(id) }

    private fun Core.getNow(id: String): Magazine? =
        query("SELECT json FROM magazine WHERE id = ?", id) { DobraJson.decodeFromString(Magazine.serializer(), it.getText(0)) }
            .firstOrNull()

    override suspend fun save(magazine: Magazine): Magazine = core.write {
        val old = getNow(magazine.id)
        when {
            old == null -> {
                val position = query("SELECT COALESCE(MAX(position), 0) + 1 FROM magazine") { it.getLong(0) }.first()
                exec(
                    "INSERT INTO magazine (id, position, json) VALUES (?, ?, ?)",
                    magazine.id, position, DobraJson.encodeToString(Magazine.serializer(), magazine),
                )
                magazine
            }
            old == magazine.copy(version = old.version) -> old
            else -> {
                val next = magazine.copy(version = old.version + 1)
                exec("UPDATE magazine SET json = ? WHERE id = ?", DobraJson.encodeToString(Magazine.serializer(), next), magazine.id)
                next
            }
        }
    }

    override suspend fun delete(id: String) = core.write {
        val prefix = "$id/"
        val dates = query("SELECT date FROM edition_index WHERE magazine = ?", id) { it.getText(0) }
        dates.forEach { files.delete(editionPath(id, it)) }
        exec("DELETE FROM edition_index WHERE magazine = ?", id)
        exec("DELETE FROM magazine WHERE id = ?", id)
        for (kind in listOf(Kind.READ, Kind.EDITION_READ, Kind.DELETED, Kind.ORDER, Kind.FEEDBACK)) removeMarksWithPrefix(kind, prefix)
        removeMark(Kind.INTEREST_OFF, id)
        // Saved items are deliberately kept: they carry their own copy.
    }
}

// ---- editions ----

private fun summaryOf(st: SQLiteStatement) = EditionSummary(
    key = st.getText(0),
    magazine = st.getText(1),
    date = st.getText(2),
    number = st.getLong(3).toInt(),
    recipeVersion = st.getLong(4).toInt(),
    state = EditionState.valueOf(st.getText(5)),
    storyIds = DobraJson.decodeFromString(stringList, st.getText(6)),
)

private const val SUMMARY_COLUMNS = "key, magazine, date, number, recipe_version, state, story_ids"

internal class SqlEditions(private val core: Core) : EditionRepository {
    override fun observe(magazine: String): Flow<List<EditionSummary>> = core.observe { list(magazine) }

    override suspend fun list(magazine: String): List<EditionSummary> = core.locked {
        query("SELECT $SUMMARY_COLUMNS FROM edition_index WHERE magazine = ? ORDER BY date DESC", magazine, map = ::summaryOf)
    }

    override suspend fun get(key: String): Edition? = core.locked {
        val row = query("SELECT magazine, date FROM edition_index WHERE key = ?", key) { it.getText(0) to it.getText(1) }
            .firstOrNull() ?: return@locked null
        files.read(editionPath(row.first, row.second))?.let { DobraJson.decodeFromString(Edition.serializer(), it) }
    }

    override suspend fun put(edition: Edition): Boolean = core.write {
        if (mark(Kind.DELETED, edition.key) != null) return@write false
        files.write(editionPath(edition.magazine, edition.date), DobraJson.encodeToString(Edition.serializer(), edition))
        val stories = edition.items.filter { it.kind == ItemKind.STORY }.map { it.id }
        exec(
            "INSERT OR REPLACE INTO edition_index ($SUMMARY_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?)",
            edition.key, edition.magazine, edition.date, edition.number, edition.recipeVersion,
            edition.state.name, DobraJson.encodeToString(stringList, stories),
        )
        true
    }

    override suspend fun delete(key: String) = core.write {
        val row = query("SELECT magazine, date FROM edition_index WHERE key = ?", key) { it.getText(0) to it.getText(1) }.firstOrNull()
        if (row != null) files.delete(editionPath(row.first, row.second))
        exec("DELETE FROM edition_index WHERE key = ?", key)
        putMark(Kind.DELETED, key)
    }

    override suspend fun isDeleted(key: String): Boolean = core.locked { mark(Kind.DELETED, key) != null }
}

// ---- marks ----

internal class SqlMarks(private val core: Core) : MarksRepository {
    override fun observeSaved(): Flow<List<SavedItem>> = core.observe { saved() }

    override suspend fun saved(): List<SavedItem> = core.locked {
        query("SELECT key, value FROM marks WHERE kind = ? ORDER BY rowid DESC", Kind.SAVED) {
            SavedItem(it.getText(0), DobraJson.decodeFromString(Item.serializer(), it.getText(1)))
        }
    }

    override suspend fun isSaved(itemKey: String): Boolean = core.locked { mark(Kind.SAVED, itemKey) != null }

    override suspend fun save(editionKey: String, item: Item) = core.write {
        putMark(Kind.SAVED, Keys.item(editionKey, item.id), DobraJson.encodeToString(Item.serializer(), item))
    }

    override suspend fun unsave(itemKey: String) = core.write { removeMark(Kind.SAVED, itemKey) }

    override suspend fun setStoryRead(itemKey: String, read: Boolean) = core.write {
        if (read) putMark(Kind.READ, itemKey) else removeMark(Kind.READ, itemKey)
    }

    override suspend fun readStories(editionKey: String): Set<String> =
        core.locked { marksWithPrefix(Kind.READ, "$editionKey/").map { it.first }.toSet() }

    override suspend fun setEditionRead(editionKey: String, read: Boolean) = core.write {
        if (read) putMark(Kind.EDITION_READ, editionKey) else removeMark(Kind.EDITION_READ, editionKey)
    }

    override suspend fun isEditionRead(editionKey: String): Boolean = core.locked { mark(Kind.EDITION_READ, editionKey) != null }

    override suspend fun setOrder(editionKey: String, itemIds: List<String>?) = core.write {
        if (itemIds == null) removeMark(Kind.ORDER, editionKey)
        else putMark(Kind.ORDER, editionKey, DobraJson.encodeToString(stringList, itemIds))
    }

    override suspend fun order(editionKey: String): List<String>? =
        core.locked { mark(Kind.ORDER, editionKey)?.let { DobraJson.decodeFromString(stringList, it) } }

    override suspend fun setFeedback(itemKey: String, feedback: Feedback?) = core.write {
        if (feedback == null) removeMark(Kind.FEEDBACK, itemKey) else putMark(Kind.FEEDBACK, itemKey, feedback.name.lowercase())
    }

    override suspend fun feedback(itemKey: String): Feedback? = core.locked { mark(Kind.FEEDBACK, itemKey)?.toFeedback() }

    override suspend fun feedbackOf(magazine: String): Map<String, Feedback> = core.locked {
        marksWithPrefix(Kind.FEEDBACK, "$magazine/").mapNotNull { (k, v) -> v.toFeedback()?.let { k to it } }.toMap()
    }

    override suspend fun setInterestOff(magazine: String, topic: String, off: Boolean) = core.write {
        val now = interestsNow(magazine).toMutableSet()
        if (off) now += topic else now -= topic
        if (now.isEmpty()) removeMark(Kind.INTEREST_OFF, magazine)
        else putMark(Kind.INTEREST_OFF, magazine, DobraJson.encodeToString(stringList, now.toList()))
    }

    override suspend fun interestsOff(magazine: String): Set<String> = core.locked { interestsNow(magazine) }

    private fun Core.interestsNow(magazine: String): Set<String> =
        mark(Kind.INTEREST_OFF, magazine)?.let { DobraJson.decodeFromString(stringList, it).toSet() } ?: emptySet()

    private fun String.toFeedback(): Feedback? = Feedback.entries.firstOrNull { it.name.lowercase() == this }
}

// ---- device settings ----

internal class SqlSettings(private val core: Core) : SettingsRepository {
    override fun observe(): Flow<DeviceSettings> = core.observe { get() }

    override suspend fun get(): DeviceSettings = core.locked {
        val d = DeviceSettings()
        DeviceSettings(
            theme = settingNow("theme")?.let { v -> Theme.entries.firstOrNull { it.name == v } } ?: d.theme,
            textSize = settingNow("text_size")?.toDoubleOrNull() ?: d.textSize,
            readingMode = settingNow("reading_mode")?.let { v -> ReadingMode.entries.firstOrNull { it.name == v } } ?: d.readingMode,
            controls = settingNow("controls")?.let { v -> Controls.entries.firstOrNull { it.name == v } } ?: d.controls,
        )
    }

    override suspend fun set(settings: DeviceSettings) = core.write {
        putSetting("theme", settings.theme.name)
        putSetting("text_size", settings.textSize.toString())
        putSetting("reading_mode", settings.readingMode.name)
        putSetting("controls", settings.controls.name)
    }
}

// ---- unread ----

internal class SqlUnread(private val core: Core) : UnreadRepository {
    override fun observe(magazine: String): Flow<Int> = core.observe { count(magazine) }

    override suspend fun count(magazine: String): Int = core.locked {
        val published = query(
            "SELECT $SUMMARY_COLUMNS FROM edition_index WHERE magazine = ? AND state = ?",
            magazine, EditionState.PUBLISHED.name, map = ::summaryOf,
        )
        val read = marksWithPrefix(Kind.READ, "$magazine/").map { it.first }.toSet()
        val markedRead = marksWithPrefix(Kind.EDITION_READ, "$magazine/").map { it.first }.toSet()
        val deleted = marksWithPrefix(Kind.DELETED, "$magazine/").map { it.first }.toSet()
        published.count { e ->
            e.key !in deleted && e.key !in markedRead &&
                e.storyIds.any { Keys.item(e.key, it) !in read }
        }
    }
}
