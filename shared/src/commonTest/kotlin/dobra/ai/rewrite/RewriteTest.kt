package dobra.ai.rewrite

import dobra.model.Block
import dobra.model.Format
import dobra.model.Item
import dobra.model.ItemKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerificationTest {
    private val link = "https://github.com/acme/tool"
    private val listing = "https://github.com/trending"
    private val picture = "https://raw.example.org/acme/shot.png"
    private val clip = "https://videos.example.org/acme/demo"

    private fun sources(vararg urls: String) = Block(
        "sources",
        mapOf("links" to buildJsonArray { urls.forEach { u -> add(buildJsonObject { put("label", "x"); put("url", u) }) } }),
    )

    private fun block(type: String, vararg fields: Pair<String, String>) =
        Block(type, fields.associate { it.first to JsonPrimitive(it.second) })

    private fun check(vararg blocks: Block, media: Set<String> = setOf(picture, clip)) =
        Verification.check(blocks.toList(), setOf(link, listing), media)

    @Test
    fun linksOfTheRecordAndTheSourceArePassed() {
        assertEquals(emptyList(), check(sources(link, "$listing/"), block("quote", "text" to "x", "who" to "y", "url" to link)))
    }

    @Test
    fun aSourcesLinkThatIsNotAmongTheRecordsLinksFails() {
        val faults = check(block("title", "kicker" to "k", "text" to "t"), sources(link, "https://invented.example.com/post"))
        assertEquals(1, faults.size)
        assertTrue("block 2 (sources)" in faults[0] && "https://invented.example.com/post" in faults[0], faults[0])
        assertTrue("not among the links of this item's records" in faults[0])
    }

    @Test
    fun aSourcesBlockWithNoLinkFails() {
        assertTrue("there is no link" in check(sources()).single())
    }

    @Test
    fun aQuoteLinkingElsewhereFails() {
        val faults = check(block("quote", "text" to "x", "who" to "y", "url" to "https://elsewhere.example.com"))
        assertTrue("block 1 (quote)" in faults.single())
    }

    @Test
    fun anImageOrVideoAddressNotInTheMediaListFails() {
        val faults = check(
            block("image", "src" to "https://cdn.example.com/stolen.png", "alt" to "a", "caption" to "c", "credit" to "d", "href" to link),
            block("video", "url" to "https://youtube.example.com/other", "title" to "t"),
            block("image", "src" to picture, "alt" to "a", "caption" to "c", "credit" to "d", "href" to link),
            block("video", "url" to clip, "title" to "t"),
        )
        assertEquals(2, faults.size, faults.toString())
        assertTrue("block 1 (image)" in faults[0] && "not in the media list" in faults[0])
        assertTrue("block 2 (video)" in faults[1])
    }

    @Test
    fun withoutMediaAnyImageFailsAndTheMessageSaysSo() {
        val faults = check(block("image", "src" to picture, "alt" to "a", "caption" to "c", "credit" to "d", "href" to link), media = emptySet())
        assertTrue("have no media" in faults.single())
    }
}

class ItemRulesTest {
    private val format = Format()

    @Test
    fun anAnswerInAFenceIsRead() {
        val text = "```json\n" + Samples.item("Título", "https://a.example.org/1") + "\n```"
        val v = ItemRules.judge(text, format, setOf("https://a.example.org/1"), emptySet(), ItemKind.STORY, update = false)
        assertIs<Verdict.Ok<Item>>(v)
        assertEquals("titulo-de-teste", ItemRules.slug(v.value.id))
    }

    @Test
    fun formatFaultsAndVerificationFaultsComeTogether() {
        val text = """{"id": "x", "blocks": [{"type": "title", "kicker": "k", "text": "t"},
            {"type": "sources", "links": [{"label": "l", "url": "https://elsewhere.example.com"}]}, {"type": "paragraph"}]}"""
        val v = ItemRules.judge(text, format, setOf("https://a.example.org/1"), emptySet(), ItemKind.STORY, update = false)
        assertIs<Verdict.Faults>(v)
        assertTrue(v.messages.any { "the field \"text\" is missing" in it }, v.messages.toString())
        assertTrue(v.messages.any { "not among the links" in it }, v.messages.toString())
    }

    @Test
    fun anUpdateMustSayWhatChangedAndIsMarked() {
        val links = setOf("https://a.example.org/1")
        val without = ItemRules.judge(Samples.item("Ferramenta", links.first()), format, links, emptySet(), ItemKind.STORY, update = true)
        assertIs<Verdict.Faults>(without)
        assertTrue("say what changed" in without.messages.single())

        val with = Samples.item("Ferramenta", links.first(), changed = "Saiu a versão 2.0 com suporte a Rust.")
        val ok = ItemRules.judge(with, format, links, emptySet(), ItemKind.STORY, update = true)
        assertIs<Verdict.Ok<Item>>(ok)
        assertTrue(ItemRules.isUpdate(ok.value))
        val title = ok.value.blocks.first { it.type == "title" }
        assertEquals("Atualização · Ferramentas", (title["kicker"] as JsonPrimitive).content)
        val changed = ok.value.blocks[ok.value.blocks.indexOf(title) + 1]
        assertEquals("callout", changed.type)
        assertEquals("Saiu a versão 2.0 com suporte a Rust.", (changed["text"] as JsonPrimitive).content)
    }

    @Test
    fun withoutCalloutTheChangeGoesToTheNextAcceptedType() {
        val blocks = listOf(Block("title", mapOf("kicker" to JsonPrimitive("K"), "text" to JsonPrimitive("T"))))
        val noCallout = Format().without("callout")
        assertEquals("note", ItemRules.markUpdate(blocks, "mudou", noCallout)[1].type)
        val bare = Format(listOf("title", "sources"))
        val marked = ItemRules.markUpdate(blocks, "mudou", bare)
        assertEquals(1, marked.size)
        assertEquals("Atualização: mudou · K", (marked[0]["kicker"] as JsonPrimitive).content)
    }

    @Test
    fun skipIsAnAnswer() {
        val v = ItemRules.judge("""{"skip": "nada de novo"}""", format, emptySet(), emptySet(), ItemKind.STORY, update = true)
        assertEquals(Verdict.Skip("nada de novo"), v)
    }
}

class RewriteLoopTest {
    private val link = "https://a.example.org/1"
    private val invalid = """{"id": "x", "kind": "story", "blocks": [{"type": "title", "kicker": "k", "text": "t"}]}"""

    @Test
    fun aRefusedAnswerGoesBackWithTheValidatorsMessageUntilTheLimit() = runTest {
        val ai = Recorded(listOf(invalid, invalid, invalid))
        val loop = RewriteLoop(ai.gateway, maxAttempts = 3)
        val result = loop.run("system", "write it", "m/2026-11-13") {
            ItemRules.judge(it, Format(), setOf(link), emptySet(), ItemKind.STORY, update = false)
        }
        assertIs<LoopResult.Failed>(result)
        assertEquals(3, ai.transport.sent.size)
        assertEquals(listOf("the required block \"sources\" is missing"), result.faults)
        val second = ai.prompts[1]
        assertTrue(second.startsWith("write it"))
        assertTrue("# The validator refused it for these reasons\n- the required block \"sources\" is missing" in second, second)
        assertTrue(invalid in second)
        assertEquals(3, ai.ledger.totalFor("m/2026-11-13").calls)
    }

    @Test
    fun aCorrectedAnswerIsAcceptedAndJsonModeIsAskedFor() = runTest {
        val ai = Recorded(listOf(invalid, Samples.item("Ok", link)))
        val result = RewriteLoop(ai.gateway).run("system", "write it", null) {
            ItemRules.judge(it, Format(), setOf(link), emptySet(), ItemKind.STORY, update = false)
        }
        assertIs<LoopResult.Done<Item>>(result)
        assertEquals(2, result.attempts.size)
        val body = Recorded.bodyOf(ai.transport.sent.first())
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"].let { (it as JsonPrimitive).content })
    }
}

/** Hand-written answers in the shape the prompts ask for. */
object Samples {
    fun item(title: String, link: String, changed: String? = null, kind: String = "story"): String = buildJsonObject {
        put("id", "$title de teste")
        put("kind", kind)
        changed?.let { put("changed", it) }
        put("blocks", buildJsonArray {
            add(buildJsonObject { put("type", "title"); put("kicker", "Ferramentas"); put("text", "$title de teste") })
            add(buildJsonObject { put("type", "summary"); put("text", "O que faz e para que serve.") })
            add(buildJsonObject { put("type", "paragraph"); put("text", "Um parágrafo com o que está no texto coletado.") })
            add(buildJsonObject {
                put("type", "facts")
                put("items", buildJsonArray { add(JsonPrimitive("Licença MIT, segundo o README.")) })
            })
            add(buildJsonObject {
                put("type", "sources")
                put("links", buildJsonArray { add(buildJsonObject { put("label", "Fonte primária"); put("url", link) }) })
            })
        })
    }.toString()
}
