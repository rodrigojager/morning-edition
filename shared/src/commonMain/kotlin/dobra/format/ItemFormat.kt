package dobra.format

import dobra.model.Block
import dobra.model.Blocks
import dobra.model.FieldType
import dobra.model.Format
import dobra.model.Item
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the check found. `off` are accepted-nowhere types; `errors` are structural faults. */
data class CheckResult(val off: List<String>, val errors: List<String>) {
    /** Every message, one per problem; empty when the item is valid. */
    val messages: List<String> get() = errors
    val ok: Boolean get() = off.isEmpty() && errors.isEmpty()
}

object ItemFormat {
    private fun kindOf(v: JsonElement?): FieldType? = when {
        v == null || v is JsonNull -> null
        v is JsonArray -> FieldType.ARRAY
        v is JsonObject -> FieldType.OBJECT
        v is JsonPrimitive && v.isString -> FieldType.STRING
        v is JsonPrimitive && v.booleanOrNull != null -> FieldType.BOOLEAN
        else -> FieldType.NUMBER
    }

    /**
     * Checks the blocks of one item against a format. Messages name the block (1-based position and type)
     * and the field.
     */
    fun check(blocks: List<Block>, format: Format): CheckResult {
        val off = mutableListOf<String>()
        val errors = mutableListOf<String>()
        blocks.forEachIndexed { i, b ->
            val spec = Blocks.spec(b.type)
            if (spec == null) {
                errors += "block ${i + 1}: the type \"${b.type}\" does not exist in the format"
                return@forEachIndexed
            }
            if (!format.accepts(b.type)) {
                if (b.type !in off) off += b.type
                errors += "block ${i + 1} (${b.type}): the type is not accepted by this magazine's format"
            }
            for (f in spec.fields) {
                val got = kindOf(b[f.name])
                if (got == null) {
                    if (!f.optional) errors += "block ${i + 1} (${b.type}): the field \"${f.name}\" is missing"
                } else if (got != f.type) {
                    errors += "block ${i + 1} (${b.type}): the field \"${f.name}\" should be of type ${f.type.schemaName}"
                }
            }
        }
        for (r in Blocks.all.filter { it.required }) {
            if (blocks.none { it.type == r.id }) errors += "the required block \"${r.id}\" is missing"
        }
        return CheckResult(off, errors)
    }

    fun check(item: Item, format: Format): CheckResult = check(item.blocks, format)

    /** The blocks to draw: only accepted types, and no section title left with nothing under it. */
    fun shown(blocks: List<Block>, format: Format): List<Block> {
        val on = blocks.filter { format.accepts(it.type) }
        return on.filterIndexed { i, b -> b.type != "section" || (i + 1 < on.size && on[i + 1].type != "section") }
    }

    /**
     * The content contract of a magazine as a JSON Schema (draft 2020-12): one `oneOf` entry per accepted
     * type and one `contains` entry per required type.
     */
    fun schemaOf(format: Format, magazineName: String = ""): JsonObject {
        val on = Blocks.all.filter { format.accepts(it.id) }
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            if (magazineName.isNotEmpty()) put("title", "Item of the magazine $magazineName")
            put("type", "object")
            putJsonArray("required") { add(JsonPrimitive("id")); add(JsonPrimitive("blocks")) }
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("blocks") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("oneOf", buildJsonArray {
                            for (b in on) add(buildJsonObject {
                                put("type", "object")
                                putJsonArray("required") {
                                    add(JsonPrimitive("type"))
                                    b.fields.filter { !it.optional }.forEach { add(JsonPrimitive(it.name)) }
                                }
                                putJsonObject("properties") {
                                    putJsonObject("type") { put("const", b.id) }
                                    for (f in b.fields) putJsonObject(f.name) { put("type", f.type.schemaName) }
                                }
                                put("additionalProperties", false)
                            })
                        })
                    }
                    put("allOf", buildJsonArray {
                        for (b in on.filter { it.required }) add(buildJsonObject {
                            putJsonObject("contains") {
                                put("type", "object")
                                putJsonObject("properties") { putJsonObject("type") { put("const", b.id) } }
                                putJsonArray("required") { add(JsonPrimitive("type")) }
                            }
                        })
                    })
                }
            }
        }
    }
}
