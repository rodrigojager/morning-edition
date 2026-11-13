package dobra.ai.rewrite

import dobra.format.ItemFormat
import dobra.model.Block
import dobra.model.Format
import dobra.model.Item
import dobra.model.ItemKind
import kotlinx.serialization.json.JsonPrimitive

/** The checks one written item must pass before it may reach the reader, and the marks code puts on it. */
object ItemRules {
    /** The kicker that marks an item as an update of something already published. */
    const val UPDATE_KICKER = "Atualização"

    /** Where the sentence on what changed goes: the first of these types the format accepts. */
    private val CHANGE_TYPES = listOf("callout", "note", "paragraph", "summary")

    /**
     * Reads one answer and checks it: the format, the verification rule ([links], [media]) and, for an [update], that
     * it says what changed. An accepted update comes back marked (see [markUpdate]).
     */
    fun judge(
        text: String,
        format: Format,
        links: Set<String>,
        media: Set<String>,
        kind: ItemKind,
        update: Boolean,
        fixedId: String? = null,
    ): Verdict<Item> = when (val answer = ItemAnswer.read(text)) {
        is ItemAnswer.Unreadable -> Verdict.Faults(listOf(answer.message))
        is ItemAnswer.Skip -> Verdict.Skip(answer.reason)
        is ItemAnswer.Written -> {
            val faults = ArrayList<String>()
            faults += ItemFormat.check(answer.blocks, format).errors
            faults += Verification.check(answer.blocks, links, media)
            if (update && answer.changed == null) {
                faults += "this item is an update of something already published: say what changed in the field " +
                    "\"changed\", or answer {\"skip\": \"<reason>\"}"
            }
            if (faults.isNotEmpty()) {
                Verdict.Faults(faults)
            } else {
                val blocks = if (update) markUpdate(answer.blocks, answer.changed!!, format) else answer.blocks
                Verdict.Ok(Item(fixedId ?: answer.id ?: titleOf(blocks) ?: "item", kind, blocks))
            }
        }
    }

    /**
     * Marks an update: the title's kicker starts with [UPDATE_KICKER] and, right after the title, a block of the first
     * accepted type among callout, note, paragraph and summary says what changed. With none of them accepted the
     * sentence goes into the kicker.
     */
    fun markUpdate(blocks: List<Block>, changed: String, format: Format): List<Block> {
        val type = CHANGE_TYPES.firstOrNull { format.accepts(it) }
        val out = ArrayList<Block>()
        for (b in blocks) {
            if (b.type == "title") {
                val old = (b["kicker"] as? JsonPrimitive)?.content.orEmpty()
                val kicker = buildString {
                    append(UPDATE_KICKER)
                    if (type == null) append(": ").append(changed)
                    if (old.isNotBlank() && !old.startsWith(UPDATE_KICKER)) append(" · ").append(old)
                }
                out += b.copy(fields = b.fields + ("kicker" to JsonPrimitive(kicker)))
                if (type != null) out += Block(type, mapOf("text" to JsonPrimitive(changed)))
            } else {
                out += b
            }
        }
        return out
    }

    fun isUpdate(item: Item): Boolean = item.blocks.firstOrNull { it.type == "title" }
        ?.let { (it["kicker"] as? JsonPrimitive)?.content?.startsWith(UPDATE_KICKER) } == true

    fun titleOf(blocks: List<Block>): String? = blocks.firstOrNull { it.type == "title" }
        ?.let { (it["text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.takeIf { it.isNotBlank() }

    /** A short id made of lowercase letters, digits and dashes. */
    fun slug(text: String): String {
        val plain = text.lowercase().map { c ->
            when (c) {
                in 'a'..'z', in '0'..'9' -> c
                'á', 'à', 'â', 'ã', 'ä' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                'ú', 'ù', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                else -> '-'
            }
        }.joinToString("")
        return plain.split('-').filter { it.isNotEmpty() }.joinToString("-").take(48).trimEnd('-').ifEmpty { "item" }
    }
}
