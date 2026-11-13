package dobra.ai.rewrite

import dobra.model.Block
import dobra.model.Item
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What code can enforce of the verification rule (PLAN-ai, "The verification rule"): an item may cite only the links
 * of the records it was written from, and may embed only the media addresses the collector recorded for them. Whether
 * a sentence is really in the collected text is left to the model and its instructions; this checks addresses.
 */
object Verification {
    /** Addresses are compared without surrounding spaces and without a trailing slash. */
    fun normalize(url: String): String = url.trim().removeSuffix("/")

    /**
     * The faults of [blocks], one English message per problem naming the block (1-based) and the address, ready to be
     * sent back to the model. [links] are the addresses the item may cite, [media] those it may embed.
     */
    fun check(blocks: List<Block>, links: Set<String>, media: Set<String>): List<String> {
        val allowedLinks = links.map(::normalize).toSet()
        val allowedMedia = media.map(::normalize).toSet()
        val linkList = links.joinToString(", ")
        val mediaHint = if (media.isEmpty()) "these records have no media: leave image and video blocks out"
        else "use only: " + media.joinToString(", ")
        val faults = ArrayList<String>()
        blocks.forEachIndexed { index, b ->
            val n = index + 1
            when (b.type) {
                "sources" -> {
                    val list = b["links"] as? JsonArray ?: return@forEachIndexed // the format check reports it
                    if (list.isEmpty()) faults += "block $n (sources): there is no link; cite at least one of: $linkList"
                    list.forEachIndexed { i, link ->
                        val url = (link as? JsonObject)?.let { str(it, "url") }
                        when {
                            url == null -> faults += "block $n (sources): link ${i + 1} has no \"url\""
                            normalize(url) !in allowedLinks -> faults +=
                                "block $n (sources): the link \"$url\" is not among the links of this item's records; cite only: $linkList"
                        }
                    }
                }
                "quote" -> str(b.fields, "url")?.let { url ->
                    if (normalize(url) !in allowedLinks) {
                        faults += "block $n (quote): the address \"$url\" is not among the links of this item's records; cite only: $linkList"
                    }
                }
                "image", "video" -> {
                    val field = if (b.type == "image") "src" else "url"
                    str(b.fields, field)?.let { url ->
                        if (normalize(url) !in allowedMedia) {
                            faults += "block $n (${b.type}): the address \"$url\" is not in the media list of this item's records; $mediaHint"
                        }
                    }
                }
            }
        }
        return faults
    }

    /** The links an existing item cites (its sources and quotes) and the media it embeds. */
    fun addressesOf(item: Item): Pair<Set<String>, Set<String>> {
        val links = LinkedHashSet<String>()
        val media = LinkedHashSet<String>()
        for (b in item.blocks) when (b.type) {
            "sources" -> (b["links"] as? JsonArray)?.forEach { l -> (l as? JsonObject)?.let { str(it, "url") }?.let(links::add) }
            "quote" -> str(b.fields, "url")?.let(links::add)
            "image" -> str(b.fields, "src")?.let(media::add)
            "video" -> str(b.fields, "url")?.let(media::add)
        }
        return links to media
    }

    private fun str(o: Map<String, kotlinx.serialization.json.JsonElement>, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
