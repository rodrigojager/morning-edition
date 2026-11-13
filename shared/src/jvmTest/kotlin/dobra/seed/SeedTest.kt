package dobra.seed

import dobra.format.ItemFormat
import dobra.model.Edition
import dobra.model.ItemKind
import dobra.model.Magazine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeedTest {
    // Gradle runs jvmTest with the module directory (shared/) as the working directory.
    private val seedDir = File("seed")
    private val magazine: Magazine = Seed.magazine(File(seedDir, Seed.MAGAZINE_FILE).readText())
    private val edition: Edition = Seed.edition(File(seedDir, Seed.EDITION_FILE).readText())

    @Test
    fun hasEightStoriesSixNotesAndTenSources() {
        assertEquals(8, edition.items.count { it.kind == ItemKind.STORY })
        assertEquals(6, edition.items.count { it.kind == ItemKind.NOTE })
        assertEquals(10, magazine.sources.size)
        assertEquals(10, edition.collection.size)
        assertEquals(magazine.id, edition.magazine)
        assertEquals("2026-11-12", edition.date)
    }

    @Test
    fun allFourteenItemsPassTheDefaultFormat() {
        assertEquals(14, edition.items.size)
        for (item in edition.items) {
            val result = ItemFormat.check(item, magazine.format)
            assertTrue(result.ok, "${item.id}: ${result.messages}")
        }
    }

    @Test
    fun storyTitlesAndFirstParagraphsEqualThePrototype() {
        val js = File("../../_work/20-design/mock-html/morning-edition/edition-data.js")
        assertTrue(js.exists(), "prototype data file not found at ${js.absolutePath}")
        val text = js.readText()
        val stories = text.substring(text.indexOf("stories: ["), text.indexOf("briefs: ["))
        val str = "\"((?:[^\"\\\\]|\\\\.)*)\""
        fun unescape(s: String): String = Json.parseToJsonElement("\"$s\"").let { (it as JsonPrimitive).contentOrNull!! }
        val titles = Regex("(?m)^ {6}title: $str,").findAll(stories).map { unescape(it.groupValues[1]) }.toList()
        val firsts = Regex("(?m)^ {6}problem: \\[\\s*$str").findAll(stories).map { unescape(it.groupValues[1]) }.toList()
        assertEquals(8, titles.size)
        assertEquals(8, firsts.size)

        val seeded = edition.items.filter { it.kind == ItemKind.STORY }
        assertEquals(titles, seeded.map { it.blocks.first { b -> b.type == "title" }["text"]!!.let { e -> (e as JsonPrimitive).content } })
        assertEquals(firsts, seeded.map { it.blocks.first { b -> b.type == "paragraph" }["text"]!!.let { e -> (e as JsonPrimitive).content } })
    }
}
