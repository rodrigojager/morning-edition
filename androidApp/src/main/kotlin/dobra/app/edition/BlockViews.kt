package dobra.app.edition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.LocalTextScale
import dobra.model.Block
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** How a block type is drawn. */
typealias BlockDrawer = @Composable (Block) -> Unit

/**
 * The one table from block type to drawing function. This task draws title, summary, section, paragraph, list, note
 * and sources; the other types are added to this table by the next task.
 */
val blockDrawers: Map<String, BlockDrawer> = mapOf(
    "title" to { TitleBlock(it) },
    "summary" to { SummaryBlock(it) },
    "section" to { SectionBlock(it) },
    "paragraph" to { ParagraphBlock(it) },
    "list" to { ListBlock(it) },
    "note" to { NoteBlock(it) },
    "sources" to { SourcesBlock(it) },
)

/** Draws [block] through the table, wrapped in a node tagged `block-<type>`; types without a drawer draw nothing. */
@Composable
fun BlockView(block: Block, modifier: Modifier = Modifier) {
    val drawer = blockDrawers[block.type] ?: return
    Box(modifier.testTag("block-${block.type}")) { drawer(block) }
}

// ---- field readers ----

internal fun Block.text(name: String): String? = (fields[name] as? JsonPrimitive)?.contentOrNull

internal fun Block.strings(name: String): List<String> =
    (fields[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

internal fun Block.int(name: String): Int? = (fields[name] as? JsonPrimitive)?.intOrNull

internal fun Block.flag(name: String): Boolean = (fields[name] as? JsonPrimitive)?.contentOrNull == "true"

internal fun Block.objects(name: String): List<JsonObject> =
    (fields[name] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

// ---- drawing helpers ----

@Composable
private fun scaled(size: Float) = (size * LocalTextScale.current).sp

@Composable
private fun markup(text: String): AnnotatedString {
    val colors = LocalReaderColors.current
    return remember(text, colors) { inlineText(text, AppFonts.mono, colors.accent, colors.rule.copy(alpha = 0.45f)) }
}

// ---- the drawers ----

@Composable
fun TitleBlock(block: Block) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        block.text("kicker")?.let {
            Text(
                it.uppercase(), color = colors.accent, fontFamily = AppFonts.mono,
                fontWeight = FontWeight.SemiBold, fontSize = scaled(11.5f), letterSpacing = 1.6.sp,
            )
        }
        Text(
            block.text("text").orEmpty(), color = colors.ink, fontFamily = AppFonts.display,
            fontWeight = FontWeight(620), fontSize = scaled(30f), lineHeight = scaled(33f),
        )
        val meta = listOfNotNull(
            block.int("readMin")?.let { stringResource(R.string.story_read_min, it) },
            block.text("date"),
            block.strings("discovery").takeIf { it.isNotEmpty() }?.joinToString(" · "),
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Text(meta, color = colors.ink3, fontFamily = AppFonts.mono, fontSize = scaled(11f))
        }
    }
}

@Composable
fun SummaryBlock(block: Block) {
    val colors = LocalReaderColors.current
    Text(
        markup(block.text("text").orEmpty()), color = colors.ink2, fontFamily = AppFonts.text,
        fontSize = scaled(19.5f), lineHeight = scaled(28f),
    )
}

@Composable
fun SectionBlock(block: Block) {
    val colors = LocalReaderColors.current
    Text(
        block.text("text").orEmpty(), color = colors.ink, fontFamily = AppFonts.display,
        fontWeight = FontWeight(650), fontSize = scaled(23f), lineHeight = scaled(28f),
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
fun ParagraphBlock(block: Block) {
    val colors = LocalReaderColors.current
    Text(
        markup(block.text("text").orEmpty()), color = colors.ink, fontFamily = AppFonts.text,
        fontSize = scaled(17f), lineHeight = scaled(26f),
    )
}

@Composable
fun ListBlock(block: Block) {
    val colors = LocalReaderColors.current
    val ordered = block.flag("ordered")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.strings("items").forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (ordered) "${i + 1}." else "•", color = colors.accent, fontFamily = AppFonts.mono,
                    fontSize = scaled(14f), modifier = Modifier.width(24.dp),
                )
                Text(
                    markup(line), color = colors.ink, fontFamily = AppFonts.text,
                    fontSize = scaled(17f), lineHeight = scaled(25f), modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun NoteBlock(block: Block) {
    val colors = LocalReaderColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.rule))
        Text(
            markup(block.text("text").orEmpty()), color = colors.ink2, fontFamily = AppFonts.text,
            fontSize = scaled(15f), lineHeight = scaled(22f), modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
fun SourcesBlock(block: Block) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.sources_heading).uppercase(), color = colors.ink3, fontFamily = AppFonts.mono,
            fontWeight = FontWeight.SemiBold, fontSize = scaled(11f), letterSpacing = 1.4.sp,
        )
        block.objects("links").forEach { link ->
            val label = link.text("label").orEmpty()
            val url = link.text("url")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                link.text("role")?.let {
                    Text(it, color = colors.ink3, fontFamily = AppFonts.mono, fontSize = scaled(10.5f))
                }
                Text(
                    markup(if (url != null) "[$label]($url)" else label), color = colors.ink,
                    fontFamily = AppFonts.text, fontSize = scaled(16f), lineHeight = scaled(22f),
                )
                link.text("note")?.let {
                    Text(it, color = colors.ink3, fontFamily = AppFonts.text, fontSize = scaled(14f))
                }
            }
        }
    }
}
