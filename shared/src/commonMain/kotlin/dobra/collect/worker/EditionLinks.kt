package dobra.collect.worker

import dobra.model.Edition
import dobra.store.EditionRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The published links of a magazine: every address in the `sources` blocks of its stored editions. */
class EditionLinks(private val editions: EditionRepository) : PublishedLinks {
    override suspend fun of(magazine: String): Set<String> =
        editions.list(magazine).mapNotNull { editions.get(it.key) }.flatMapTo(LinkedHashSet()) { linksOf(it) }

    companion object {
        fun linksOf(edition: Edition): List<String> = edition.items.flatMap { item ->
            item.blocks.filter { it.type == "sources" }.flatMap { block ->
                ((block["links"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { link ->
                    ((link as? JsonObject)?.get("url") as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
            }
        }
    }
}
