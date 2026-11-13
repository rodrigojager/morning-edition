package dobra.app.edition

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color

/** One piece of a text field with inline markup: plain text, `code`, **bold** or a [label](address) link. */
sealed interface InlinePiece {
    data class Plain(val text: String) : InlinePiece
    data class Code(val text: String) : InlinePiece
    data class Bold(val text: String) : InlinePiece
    data class Link(val label: String, val url: String) : InlinePiece
}

private val inlinePattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")

/** Splits [text] into pieces; whatever is not valid markup stays as plain text. */
fun parseInline(text: String): List<InlinePiece> {
    val pieces = ArrayList<InlinePiece>()
    var at = 0
    for (m in inlinePattern.findAll(text)) {
        if (m.range.first > at) pieces += InlinePiece.Plain(text.substring(at, m.range.first))
        val g = m.groupValues
        pieces += when {
            m.groups[1] != null -> InlinePiece.Bold(g[1])
            m.groups[2] != null -> InlinePiece.Code(g[2])
            else -> InlinePiece.Link(g[3], g[4])
        }
        at = m.range.last + 1
    }
    if (at < text.length) pieces += InlinePiece.Plain(text.substring(at))
    return pieces
}

/** Builds styled text from inline markup. A link opens the browser when touched. */
fun inlineText(text: String, mono: FontFamily, accent: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        for (piece in parseInline(text)) {
            when (piece) {
                is InlinePiece.Plain -> append(piece.text)
                is InlinePiece.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(piece.text) }
                is InlinePiece.Code -> withStyle(SpanStyle(fontFamily = mono, background = codeBackground)) {
                    append(piece.text)
                }
                is InlinePiece.Link -> withLink(
                    LinkAnnotation.Url(
                        piece.url,
                        TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(piece.label) }
            }
        }
    }
