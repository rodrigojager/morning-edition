package dobra.format

import dobra.model.Block
import dobra.model.Blocks
import dobra.model.DobraJson
import dobra.model.Format
import dobra.model.Item
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemFormatTest {
    private fun str(s: String) = JsonPrimitive(s)
    private val arr = JsonArray(listOf(str("x")))

    private fun block(type: String, vararg f: Pair<String, JsonElement>) = Block(type, mapOf(*f))

    private fun valid(): List<Block> = listOf(
        block("title", "kicker" to str("k"), "text" to str("t")),
        block("paragraph", "text" to str("p")),
        block("sources", "links" to arr),
    )

    @Test
    fun validItemPasses() {
        val r = ItemFormat.check(valid(), Format())
        assertTrue(r.ok, r.messages.toString())
    }

    @Test
    fun refusedTypeFailsNamingBlock() {
        val blocks = valid() + block("quote", "text" to str("a"), "who" to str("b"), "url" to str("u"))
        val r = ItemFormat.check(blocks, Format().without("quote"))
        assertEquals(listOf("quote"), r.off)
        assertTrue(r.messages.any { "block 4" in it && "quote" in it })
    }

    @Test
    fun unknownTypeFails() {
        val r = ItemFormat.check(valid() + block("poem"), Format())
        assertTrue(r.messages.any { "block 4" in it && "poem" in it })
    }

    @Test
    fun missingFieldFails() {
        val blocks = listOf(
            block("title", "kicker" to str("k")),
            block("sources", "links" to arr),
        )
        val r = ItemFormat.check(blocks, Format())
        assertTrue(r.messages.any { "block 1" in it && "title" in it && "\"text\"" in it && "missing" in it })
    }

    @Test
    fun wrongTypeFieldFails() {
        val blocks = valid().toMutableList()
        blocks[1] = block("paragraph", "text" to arr)
        val r = ItemFormat.check(blocks, Format())
        assertTrue(r.messages.any { "block 2" in it && "paragraph" in it && "\"text\"" in it && "string" in it })
        val n = block("factsheet", "rows" to arr, "difficulty" to str("hard"))
        assertTrue(ItemFormat.check(valid() + n, Format()).messages.any { "\"difficulty\"" in it && "number" in it })
    }

    @Test
    fun noTitleFails() {
        val r = ItemFormat.check(valid().filter { it.type != "title" }, Format())
        assertTrue(r.messages.any { "title" in it && "required" in it })
    }

    @Test
    fun noSourcesFails() {
        val r = ItemFormat.check(valid().filter { it.type != "sources" }, Format())
        assertTrue(r.messages.any { "sources" in it && "required" in it })
    }

    @Test
    fun schemaHasOneEntryPerAcceptedAndRequiredType() {
        fun blocksOf(s: JsonObject) = s["properties"]!!.jsonObject["blocks"]!!.jsonObject
        val s = ItemFormat.schemaOf(Format())
        assertEquals("https://json-schema.org/draft/2020-12/schema", s["\$schema"]!!.jsonPrimitive.content)
        val one = blocksOf(s)["items"]!!.jsonObject["oneOf"]!!.jsonArray
        assertEquals(19, one.size)
        assertEquals(2, blocksOf(s)["allOf"]!!.jsonArray.size)
        val noQuote = ItemFormat.schemaOf(Format().without("quote"))
        val ids = blocksOf(noQuote)["items"]!!.jsonObject["oneOf"]!!.jsonArray.map {
            it.jsonObject["properties"]!!.jsonObject["type"]!!.jsonObject["const"]!!.jsonPrimitive.content
        }
        assertEquals(18, ids.size)
        assertFalse("quote" in ids)
        // required types cannot be refused
        assertEquals(19, blocksOf(ItemFormat.schemaOf(Format().without("title")))["items"]!!.jsonObject["oneOf"]!!.jsonArray.size)
    }

    @Test
    fun shownDropsRefusedTypesAndEmptySections() {
        val sec = { t: String -> block("section", "text" to str(t)) }
        val quote = block("quote", "text" to str("a"), "who" to str("b"), "url" to str("u"))
        val para = block("paragraph", "text" to str("p"))
        val blocks = listOf(
            block("title", "kicker" to str("k"), "text" to str("t")),
            sec("A"), quote,          // A holds only a refused block: dropped
            sec("B"), para,
            sec("C"),                 // last, nothing after: dropped
        )
        val out = ItemFormat.shown(blocks, Format().without("quote"))
        assertEquals(listOf("title", "section", "paragraph"), out.map { it.type })
        assertEquals("B", out[1]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun itemWithAllNineteenTypesRoundTrips() {
        val json = """
        {"id":"i1","kind":"story","blocks":[
          {"type":"illustration","art":{"kind":"plate","n":1}},
          {"type":"title","kicker":"k","text":"t"},
          {"type":"summary","text":"s"},
          {"type":"section","text":"sec"},
          {"type":"paragraph","text":"p"},
          {"type":"callout","text":"c"},
          {"type":"glossary","terms":[{"term":"a","meaning":"b"}]},
          {"type":"list","items":["a","b"],"ordered":true},
          {"type":"image","src":"https://x/i.png","alt":"a","caption":"c","credit":"cr","href":"https://x"},
          {"type":"video","url":"https://x/v","title":"v","credit":"cr"},
          {"type":"code","code":"ls"},
          {"type":"facts","items":["f"]},
          {"type":"hypothesis","paragraphs":["h"]},
          {"type":"factsheet","rows":[["a","b"]],"difficulty":2,"difficultyNote":"n"},
          {"type":"quote","text":"q","who":"w","url":"https://x","pt":"t"},
          {"type":"evidence","rows":[{"date":"2026-01-01","text":"e"}]},
          {"type":"sources","links":[{"label":"l","url":"https://x"}]},
          {"type":"note","text":"n"},
          {"type":"why","text":"w"}
        ]}
        """.trimIndent()
        val item = DobraJson.decodeFromString(Item.serializer(), json)
        assertEquals(19, item.blocks.size)
        assertEquals(Blocks.ids, item.blocks.map { it.type })
        assertTrue(ItemFormat.check(item, Format()).ok)
        val back = DobraJson.encodeToString(Item.serializer(), item)
        assertEquals(DobraJson.parseToJsonElement(json), DobraJson.parseToJsonElement(back))
    }
}
