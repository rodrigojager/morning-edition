package dobra.app.library

import android.content.Context
import dobra.ai.collector.Fixture
import dobra.ai.collector.FixtureStore
import dobra.collect.health.HealthRecord
import dobra.collect.health.HealthStore
import dobra.model.DobraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps each source's health record in the app's private preferences; the recipe (and its version) stays untouched. */
class PrefsHealthStore(context: Context) : HealthStore {
    private val prefs = context.getSharedPreferences("collector-health", Context.MODE_PRIVATE)

    override suspend fun get(magazine: String, source: String): HealthRecord =
        prefs.getString("$magazine/$source", null)
            ?.let { runCatching { DobraJson.decodeFromString(HealthRecord.serializer(), it) }.getOrNull() }
            ?: HealthRecord()

    override suspend fun put(magazine: String, source: String, record: HealthRecord) {
        prefs.edit().putString("$magazine/$source", DobraJson.encodeToString(HealthRecord.serializer(), record)).apply()
    }
}

/**
 * Keeps the saved copies of the sources' pages as JSON files in the app's private folder, one file per key. Keys are
 * `<magazine>/<source>/<version>`; each part becomes a file-name-safe piece.
 */
class FileFixtureStore(private val dir: File) : FixtureStore {
    private fun fileOf(key: String) = File(dir, key.split('/').joinToString("__") { safe(it) } + ".json")

    private fun safe(part: String) = part.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
        .joinToString("")

    override suspend fun get(key: String): Fixture? = withContext(Dispatchers.IO) {
        val file = fileOf(key)
        if (!file.isFile) null else runCatching { DobraJson.decodeFromString(Fixture.serializer(), file.readText()) }.getOrNull()
    }

    override suspend fun put(key: String, fixture: Fixture) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = fileOf(key)
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(DobraJson.encodeToString(Fixture.serializer(), fixture))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        Unit
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        fileOf(key).delete()
        Unit
    }
}
