package dobra.ai.rewrite

import dobra.model.Block
import dobra.model.DobraJson
import dobra.model.ItemKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads what a model answered. Models are asked for JSON only; a Markdown fence or a line around it is tolerated. */
object Answers {
    private val json = Json { ignoreUnknownKeys = true }

    /** The JSON object of [text], or null when there is none. */
    fun objectOf(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    fun string(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}

/** What a writing answer holds: the blocks of one item, or the decision to leave it out. */
sealed interface ItemAnswer {
    /** [changed] says what changed since an earlier publication; only an update carries it. */
    data class Written(val id: String?, val kind: ItemKind?, val blocks: List<Block>, val changed: String?) : ItemAnswer

    data class Skip(val reason: String) : ItemAnswer

    /** The answer could not be read as an item; [message] goes back to the model. */
    data class Unreadable(val message: String) : ItemAnswer

    companion object {
        /** Accepts `{id, kind, blocks, changed?}`, the same wrapped in `{"item": ...}`, or `{"skip": "<reason>"}`. */
        fun read(text: String): ItemAnswer {
            val root = Answers.objectOf(text) ?: return Unreadable("the answer is not a JSON object")
            Answers.string(root, "skip")?.let { return Skip(it) }
            val o = (root["item"] as? JsonObject) ?: root
            val list = o["blocks"] as? JsonArray
                ?: return Unreadable("the answer has no \"blocks\" array")
            val blocks = ArrayList<Block>()
            list.forEachIndexed { i, e ->
                if (e !is JsonObject) return Unreadable("block ${i + 1} is not a JSON object")
                blocks += DobraJson.decodeFromJsonElement(Block.serializer(), e as JsonElement)
            }
            val kind = when (Answers.string(o, "kind")) {
                "note" -> ItemKind.NOTE
                "story" -> ItemKind.STORY
                else -> null
            }
            return Written(Answers.string(o, "id"), kind, blocks, Answers.string(o, "changed") ?: Answers.string(root, "changed"))
        }
    }
}
