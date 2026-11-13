package dobra.app.edition

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineTest {
    @Test
    fun splitsCodeBoldAndLinks() {
        val pieces = parseInline("Use `run` e **veja** [aqui](https://example.com/a) hoje")
        assertEquals(
            listOf(
                InlinePiece.Plain("Use "),
                InlinePiece.Code("run"),
                InlinePiece.Plain(" e "),
                InlinePiece.Bold("veja"),
                InlinePiece.Plain(" "),
                InlinePiece.Link("aqui", "https://example.com/a"),
                InlinePiece.Plain(" hoje"),
            ),
            pieces,
        )
    }

    @Test
    fun brokenMarkupStaysPlain() {
        assertEquals(listOf(InlinePiece.Plain("a ** b `c [d](")), parseInline("a ** b `c [d]("))
    }
}
