package dobra.collect.health

import dobra.model.Magazine
import dobra.model.Source

/** Recipes kept in memory, for tests. */
class MemorySourceBook(vararg magazines: Magazine) : SourceBook {
    private val stored = magazines.associateBy { it.id }.toMutableMap()

    /** How many times a recipe was changed. */
    var saves = 0
        private set

    fun source(magazineId: String, sourceId: String): Source = stored.getValue(magazineId).sources.first { it.id == sourceId }

    override suspend fun magazine(id: String): Magazine? = stored[id]

    override suspend fun update(magazineId: String, sourceId: String, change: (Source) -> Source): Source? {
        val m = stored[magazineId] ?: return null
        val old = m.sources.firstOrNull { it.id == sourceId } ?: return null
        val next = change(old)
        if (next != old) {
            stored[magazineId] = m.copy(sources = m.sources.map { if (it.id == sourceId) next else it })
            saves++
        }
        return next
    }

    override suspend fun addKeywords(magazineId: String, words: List<String>): Magazine? {
        val m = stored[magazineId] ?: return null
        val next = m.copy(keywords = mergeKeywords(m.keywords, words))
        if (next != m) saves++
        stored[magazineId] = next
        return next
    }
}
