package dobra.make

import dobra.ai.rewrite.RecordRef
import dobra.ai.rewrite.Verdict
import dobra.collect.engine.RawRecord
import dobra.model.ItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PageTextTest {
    @Test
    fun scriptsStylesAndTagsGoAndEntitiesAreRead() {
        val html = """<html><head><style>p{color:red}</style><script>var a = "<p>no</p>";</script></head>
            <body><h1>Título &amp; mais</h1><!-- hidden --><p>Primeira&nbsp;linha &#233; boa.</p><p>Segunda <b>linha</b>.</p></body></html>"""
        assertEquals("Título & mais\nPrimeira linha é boa.\nSegunda linha .", PageText.fromHtml(html))
        assertEquals(5, PageText.fromHtml(html, max = 5).length)
    }
}

class SelectionTest {
    private fun ref(n: Int, published: Boolean = false) =
        RecordRef("r$n", "Fonte", "https://fonte.example.org", RawRecord("Título $n", "https://a.example.org/$n", "", "texto"), published)

    private val byRef = (1..4).associate { "r$it" to ref(it, published = it == 4) }

    @Test
    fun keptEntriesBecomePicksAndAPublishedRecordIsAlwaysAnUpdate() {
        val text = """{"keep": [{"records": ["r2"], "kind": "note"}, {"records": ["r4"], "update": false}, {"records": ["r2", "r3"]}],
            "leftOut": [{"record": "r1", "reason": "fora do assunto"}, {"record": "r2", "reason": "ignored, it was kept"}]}"""
        val v = EditionMaker.judgeSelection(text, byRef)
        assertIs<Verdict.Ok<Selection>>(v)
        val picks = v.value.keep
        assertEquals(listOf(listOf("r2"), listOf("r4"), listOf("r3")), picks.map { p -> p.refs.map { it.ref } })
        assertEquals(ItemKind.NOTE, picks[0].kind)
        assertEquals(listOf(false, true, false), picks.map { it.update })
        assertEquals(listOf("Título 1"), v.value.leftOut.map { it.title })
        assertEquals("https://a.example.org/1", v.value.leftOut.single().link)
    }

    @Test
    fun unknownRecordsAndMissingKeepAreSentBack() {
        val unknown = EditionMaker.judgeSelection("""{"keep": [{"records": ["r9"]}]}""", byRef)
        assertIs<Verdict.Faults>(unknown)
        assertTrue("r9" in unknown.messages.single())
        assertIs<Verdict.Faults>(EditionMaker.judgeSelection("""{"chosen": []}""", byRef))
        assertIs<Verdict.Faults>(EditionMaker.judgeSelection("not json", byRef))
        val none = EditionMaker.judgeSelection("""{"keep": []}""", byRef)
        assertIs<Verdict.Ok<Selection>>(none)
        assertTrue(none.value.keep.isEmpty())
    }
}
