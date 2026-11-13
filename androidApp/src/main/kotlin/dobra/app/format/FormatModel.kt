package dobra.app.format

import android.content.res.Resources
import dobra.app.R
import dobra.format.ItemFormat
import dobra.model.Blocks
import dobra.model.DobraJson
import dobra.model.Edition
import dobra.model.Format
import dobra.model.Item
import dobra.model.Look
import dobra.model.Looks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** The four things that can be set for how a block type looks. */
enum class LookKey(val label: Int, val options: List<Pair<String, Int>>) {
    FONT(
        R.string.look_font,
        listOf(
            "" to R.string.look_default,
            "display" to R.string.look_font_display,
            "text" to R.string.look_font_text,
            "mono" to R.string.look_font_mono,
        ),
    ),
    SIZE(
        R.string.look_size,
        listOf(
            "" to R.string.look_default,
            "0.88" to R.string.look_size_smaller,
            "1.15" to R.string.look_size_larger,
            "1.3" to R.string.look_size_largest,
        ),
    ),
    COLOR(
        R.string.look_color,
        listOf(
            "" to R.string.look_default,
            "ink" to R.string.look_color_ink,
            "ink-2" to R.string.look_color_ink2,
            "accent" to R.string.look_color_accent,
        ),
    ),
    ALIGN(
        R.string.look_align,
        listOf(
            "" to R.string.look_default,
            "left" to R.string.look_align_left,
            "center" to R.string.look_align_center,
            "right" to R.string.look_align_right,
            "justify" to R.string.look_align_justify,
        ),
    ),
    ;

    val id: String get() = name.lowercase()

    fun of(look: Look?): String? = when (this) {
        FONT -> look?.font
        SIZE -> look?.size
        COLOR -> look?.color
        ALIGN -> look?.align
    }

    fun set(look: Look?, value: String): Look {
        val v = value.ifEmpty { null }
        val base = look ?: Look()
        return when (this) {
            FONT -> base.copy(font = v)
            SIZE -> base.copy(size = v)
            COLOR -> base.copy(color = v)
            ALIGN -> base.copy(align = v)
        }
    }
}

private fun Look.isEmpty() = font == null && size == null && color == null && align == null

/** [looks] with [key] of [type] set to [value] (empty means the default); a type left with no look is dropped. */
fun Looks.with(type: String, key: LookKey, value: String): Looks {
    val next = key.set(this[type], value)
    return if (next.isEmpty()) this - type else this + (type to next)
}

/** [format] with [type] accepted or refused. */
fun Format.with(type: String, accepted: Boolean): Format =
    Format(Blocks.all.filter { it.required || (if (it.id == type) accepted else accepts(it.id)) }.map { it.id })

/** The looks of the accepted types only, as the data stored apart from the content. */
fun looksData(format: Format, looks: Looks): JsonObject = buildJsonObject {
    for (b in Blocks.all) {
        val look = looks[b.id]
        if (format.accepts(b.id) && look != null && !look.isEmpty()) put(b.id, DobraJson.encodeToJsonElement(Look.serializer(), look))
    }
}

private val pretty = Json { prettyPrint = true }

fun prettyJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

/** The schema of [format] as text. */
fun schemaText(format: Format, magazineName: String): String = prettyJson(ItemFormat.schemaOf(format, magazineName))

/** How one item stands against the format: its title, the refused types it carries and its other faults. */
data class ItemCheck(
    val id: String,
    val title: String,
    val off: List<String>,
    val faults: List<String>,
) {
    val ok: Boolean get() = off.isEmpty() && faults.isEmpty()
}

/** The result of checking a whole edition. */
data class EditionCheck(val items: List<ItemCheck>) {
    val inside: Int get() = items.count { it.ok }
    val total: Int get() = items.size
}

private const val NOT_ACCEPTED = "is not accepted by this magazine's format"

fun checkEdition(edition: Edition, format: Format): EditionCheck = EditionCheck(
    edition.items.map { item -> checkItem(item, format) },
)

fun checkItem(item: Item, format: Format): ItemCheck {
    val result = ItemFormat.check(item, format)
    val title = item.blocks.firstOrNull { it.type == "title" }
        ?.let { (it["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() } ?: item.id
    return ItemCheck(item.id, title, result.off, result.errors.filter { NOT_ACCEPTED !in it })
}

/** How many blocks of each type the edition has. */
fun usesOf(edition: Edition): Map<String, Int> =
    edition.items.flatMap { it.blocks }.groupingBy { it.type }.eachCount()

/** The label of a block type, in Portuguese. */
fun blockLabel(res: Resources, type: String): String {
    val id = BLOCK_TEXT[type]?.first ?: return type
    return res.getString(id)
}

/** The one-line explanation of a block type. */
fun blockWhat(res: Resources, type: String): String {
    val id = BLOCK_TEXT[type]?.second ?: return ""
    return res.getString(id)
}

private val BLOCK_TEXT: Map<String, Pair<Int, Int>> = mapOf(
    "illustration" to (R.string.format_block_illustration to R.string.format_what_illustration),
    "title" to (R.string.format_block_title to R.string.format_what_title),
    "summary" to (R.string.format_block_summary to R.string.format_what_summary),
    "section" to (R.string.format_block_section to R.string.format_what_section),
    "paragraph" to (R.string.format_block_paragraph to R.string.format_what_paragraph),
    "callout" to (R.string.format_block_callout to R.string.format_what_callout),
    "glossary" to (R.string.format_block_glossary to R.string.format_what_glossary),
    "list" to (R.string.format_block_list to R.string.format_what_list),
    "image" to (R.string.format_block_image to R.string.format_what_image),
    "video" to (R.string.format_block_video to R.string.format_what_video),
    "code" to (R.string.format_block_code to R.string.format_what_code),
    "facts" to (R.string.format_block_facts to R.string.format_what_facts),
    "hypothesis" to (R.string.format_block_hypothesis to R.string.format_what_hypothesis),
    "factsheet" to (R.string.format_block_factsheet to R.string.format_what_factsheet),
    "quote" to (R.string.format_block_quote to R.string.format_what_quote),
    "evidence" to (R.string.format_block_evidence to R.string.format_what_evidence),
    "sources" to (R.string.format_block_sources to R.string.format_what_sources),
    "note" to (R.string.format_block_note to R.string.format_what_note),
    "why" to (R.string.format_block_why to R.string.format_what_why),
)

private val MISSING_FIELD = Regex("""block (\d+) \((\w+)\): the field "(\w+)" is missing""")
private val WRONG_FIELD = Regex("""block (\d+) \((\w+)\): the field "(\w+)" should be of type (\w+)""")
private val MISSING_BLOCK = Regex("""the required block "(\w+)" is missing""")
private val NO_TYPE = Regex("""block (\d+): the type "([^"]*)" does not exist in the format""")

/** Puts a message of the shared check in plain Portuguese for the reader. */
fun explain(res: Resources, message: String): String {
    MISSING_FIELD.matchEntire(message)?.let {
        val (n, type, field) = it.destructured
        return res.getString(R.string.format_fault_missing_field, n.toInt(), blockLabel(res, type), field)
    }
    WRONG_FIELD.matchEntire(message)?.let {
        val (n, type, field, kind) = it.destructured
        return res.getString(R.string.format_fault_wrong_field, n.toInt(), blockLabel(res, type), field, kind)
    }
    MISSING_BLOCK.matchEntire(message)?.let {
        return res.getString(R.string.format_fault_missing_block, blockLabel(res, it.groupValues[1]))
    }
    NO_TYPE.matchEntire(message)?.let {
        return res.getString(R.string.format_fault_no_type, it.groupValues[1].toInt(), it.groupValues[2])
    }
    return message
}
