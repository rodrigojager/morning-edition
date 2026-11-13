package dobra.app.sources

import android.content.Context
import dobra.collect.worker.CursorStore

/** Keeps each source's cursor in the app's private preferences; the recipe (and its version) stays untouched. */
class PrefsCursorStore(context: Context) : CursorStore {
    private val prefs = context.getSharedPreferences("collector-cursors", Context.MODE_PRIVATE)

    override suspend fun cursor(magazine: String, source: String): String? = prefs.getString("$magazine/$source", null)

    override suspend fun setCursor(magazine: String, source: String, cursor: String?) {
        prefs.edit().apply { if (cursor == null) remove("$magazine/$source") else putString("$magazine/$source", cursor) }.apply()
    }
}
