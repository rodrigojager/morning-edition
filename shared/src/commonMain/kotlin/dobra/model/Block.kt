package dobra.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One typed block of an item. On the wire it is a flat JSON object: `{ "type": ..., <fields> }`. */
@Serializable(with = BlockSerializer::class)
data class Block(val type: String, val fields: Map<String, JsonElement> = emptyMap()) {
    operator fun get(name: String): JsonElement? = fields[name]
}

object BlockSerializer : KSerializer<Block> {
    private val delegate = JsonObject.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Block) {
        val map = LinkedHashMap<String, JsonElement>()
        map["type"] = JsonPrimitive(value.type)
        map.putAll(value.fields)
        encoder.encodeSerializableValue(delegate, JsonObject(map))
    }

    override fun deserialize(decoder: Decoder): Block {
        val obj = decoder.decodeSerializableValue(delegate)
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return Block(type, obj.filterKeys { it != "type" })
    }
}

/** The kind of value a block field holds. */
enum class FieldType(val schemaName: String) {
    STRING("string"), ARRAY("array"), OBJECT("object"), NUMBER("number"), BOOLEAN("boolean")
}

data class FieldSpec(val name: String, val type: FieldType, val optional: Boolean = false)

/** The standard definition of one block type: its id, a label and its fields. */
data class BlockSpec(
    val id: String,
    val label: String,
    val fields: List<FieldSpec>,
    val required: Boolean = false,
)

/** The standard set of 19 block types (see the data model document). */
object Blocks {
    private fun s(n: String, opt: Boolean = false) = FieldSpec(n, FieldType.STRING, opt)
    private fun a(n: String) = FieldSpec(n, FieldType.ARRAY)

    val all: List<BlockSpec> = listOf(
        BlockSpec("illustration", "Illustration", listOf(FieldSpec("art", FieldType.OBJECT))),
        BlockSpec("title", "Title", listOf(s("kicker"), s("text")), required = true),
        BlockSpec("summary", "Summary", listOf(s("text"))),
        BlockSpec("section", "Section title", listOf(s("text"))),
        BlockSpec("paragraph", "Paragraph", listOf(s("text"))),
        BlockSpec("callout", "Callout", listOf(s("text"))),
        BlockSpec("glossary", "Glossary", listOf(a("terms"))),
        BlockSpec("list", "List", listOf(a("items"), FieldSpec("ordered", FieldType.BOOLEAN, optional = true))),
        BlockSpec("image", "Image", listOf(s("src"), s("alt"), s("caption"), s("credit"), s("href"))),
        BlockSpec("video", "Video", listOf(s("url"), s("title"), s("credit", opt = true))),
        BlockSpec("code", "Code block", listOf(s("code"))),
        BlockSpec("facts", "Verified facts", listOf(a("items"))),
        BlockSpec("hypothesis", "Hypothesis", listOf(a("paragraphs"))),
        BlockSpec(
            "factsheet", "Fact sheet",
            listOf(a("rows"), FieldSpec("difficulty", FieldType.NUMBER, optional = true), s("difficultyNote", opt = true)),
        ),
        BlockSpec("quote", "Quote", listOf(s("text"), s("who"), s("url"), s("pt", opt = true))),
        BlockSpec("evidence", "Dated evidence", listOf(a("rows"))),
        BlockSpec("sources", "Sources", listOf(a("links")), required = true),
        BlockSpec("note", "Note", listOf(s("text"))),
        BlockSpec("why", "Why it is in the edition", listOf(s("text"))),
    )

    private val byId = all.associateBy { it.id }
    val ids: List<String> get() = all.map { it.id }
    val requiredIds: List<String> get() = all.filter { it.required }.map { it.id }

    fun spec(id: String): BlockSpec? = byId[id]
}
