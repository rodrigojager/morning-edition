package dobra.app.edition

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.LocalTextScale
import dobra.app.shell.ReaderColors
import dobra.model.Block
import dobra.model.Format
import dobra.model.Looks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** How a block type is drawn. */
typealias BlockDrawer = @Composable (Block) -> Unit

/** The one table from block type to drawing function: all 19 types of the standard set. */
val blockDrawers: Map<String, BlockDrawer> = mapOf(
    "illustration" to { IllustrationBlock(it) },
    "title" to { TitleBlock(it) },
    "summary" to { SummaryBlock(it) },
    "section" to { SectionBlock(it) },
    "paragraph" to { ParagraphBlock(it) },
    "callout" to { CalloutBlock(it) },
    "glossary" to { GlossaryBlock(it) },
    "list" to { ListBlock(it) },
    "image" to { ImageBlock(it) },
    "video" to { VideoBlock(it) },
    "code" to { CodeBlock(it) },
    "facts" to { FactsBlock(it) },
    "hypothesis" to { HypothesisBlock(it) },
    "factsheet" to { FactSheetBlock(it) },
    "quote" to { QuoteBlock(it) },
    "evidence" to { EvidenceBlock(it) },
    "sources" to { SourcesBlock(it) },
    "note" to { NoteBlock(it) },
    "why" to { WhyBlock(it) },
)

/** Draws [block] through the table, wrapped in a node tagged `block-<type>`; types without a drawer draw nothing. */
@Composable
fun BlockView(block: Block, modifier: Modifier = Modifier) {
    val drawer = blockDrawers[block.type] ?: return
    Box(modifier.testTag("block-${block.type}")) { drawer(block) }
}

/**
 * The blocks of an item as the magazine's format lets them be shown: blocks of a refused type are left out, and so is
 * a section title left with nothing under it (the last block, or followed by another section title).
 */
fun shownBlocks(blocks: List<Block>, format: Format): List<Block> {
    val accepted = blocks.filter { format.accepts(it.type) }
    return accepted.filterIndexed { i, b ->
        b.type != "section" || accepted.getOrNull(i + 1)?.let { it.type != "section" } == true
    }
}

// ---- looks ----

/** The looks of the magazine being read: font, size, colour and alignment per block type. */
val LocalLooks = staticCompositionLocalOf<Looks> { emptyMap() }

// ---- field readers ----

internal fun Block.text(name: String): String? = (fields[name] as? JsonPrimitive)?.contentOrNull

internal fun Block.strings(name: String): List<String> =
    (fields[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

internal fun Block.int(name: String): Int? = (fields[name] as? JsonPrimitive)?.intOrNull

internal fun Block.flag(name: String): Boolean = (fields[name] as? JsonPrimitive)?.contentOrNull == "true"

internal fun Block.objects(name: String): List<JsonObject> =
    (fields[name] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** A field that is a list of lists of strings: glossary terms, fact sheet rows, dated evidence. */
internal fun Block.pairs(name: String): List<List<String>> =
    (fields[name] as? JsonArray)?.mapNotNull { row ->
        (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    } ?: emptyList()

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

// ---- drawing helpers ----

@Composable
private fun scaled(size: Float) = (size * LocalTextScale.current).sp

@Composable
private fun markup(text: String): AnnotatedString {
    val colors = LocalReaderColors.current
    return remember(text, colors) { inlineText(text, AppFonts.mono, colors.accent, colors.rule.copy(alpha = 0.45f)) }
}

private fun linkText(label: String, url: String?, accent: Color): AnnotatedString = buildAnnotatedString {
    if (url.isNullOrBlank()) {
        append(label)
    } else {
        withLink(
            LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))),
        ) { append(label) }
    }
}

private fun lookFont(name: String?): FontFamily? = when (name) {
    "display" -> AppFonts.display
    "text" -> AppFonts.text
    "mono" -> AppFonts.mono
    else -> null
}

private fun lookColor(name: String?, colors: ReaderColors): Color? = when (name) {
    "ink" -> colors.ink
    "ink-2" -> colors.ink2
    "accent" -> colors.accent
    else -> null
}

private fun lookAlign(name: String?): TextAlign? = when (name) {
    "left" -> TextAlign.Start
    "center" -> TextAlign.Center
    "right" -> TextAlign.End
    "justify" -> TextAlign.Justify
    else -> null
}

/**
 * The text of a block of [type], drawn with its default [font], [size] (in sp before the reader's text scale) and
 * [color], overridden by the magazine's look for that type when it has one.
 */
@Composable
private fun BlockText(
    type: String,
    text: AnnotatedString,
    font: FontFamily,
    size: Float,
    lineHeight: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
    italic: Boolean = false,
) {
    val look = LocalLooks.current[type]
    val colors = LocalReaderColors.current
    val factor = look?.size?.toFloatOrNull() ?: 1f
    Text(
        text,
        modifier = modifier,
        color = lookColor(look?.color, colors) ?: color,
        fontFamily = lookFont(look?.font) ?: font,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else null,
        fontSize = scaled(size * factor),
        lineHeight = scaled(lineHeight * factor),
        textAlign = lookAlign(look?.align) ?: TextAlign.Unspecified,
    )
}

@Composable
private fun BlockText(
    type: String, text: String, font: FontFamily, size: Float, lineHeight: Float, color: Color,
    modifier: Modifier = Modifier, weight: FontWeight? = null, italic: Boolean = false,
) = BlockText(type, AnnotatedString(text), font, size, lineHeight, color, modifier, weight, italic)

@Composable
private fun Label(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontFamily = AppFonts.mono, fontWeight = FontWeight.SemiBold,
        fontSize = scaled(11f), letterSpacing = 1.2.sp, modifier = modifier,
    )
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
        BlockText(
            "title", block.text("text").orEmpty(), AppFonts.display, 30f, 33f, colors.ink,
            weight = FontWeight(620),
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
    BlockText(
        "summary", markup(block.text("text").orEmpty()), AppFonts.text, 19.5f, 28f, LocalReaderColors.current.ink2,
    )
}

@Composable
fun SectionBlock(block: Block) {
    BlockText(
        "section", block.text("text").orEmpty(), AppFonts.display, 23f, 28f, LocalReaderColors.current.ink,
        modifier = Modifier.padding(top = 10.dp), weight = FontWeight(650),
    )
}

@Composable
fun ParagraphBlock(block: Block) {
    BlockText(
        "paragraph", markup(block.text("text").orEmpty()), AppFonts.text, 17f, 26f, LocalReaderColors.current.ink,
    )
}

@Composable
fun CalloutBlock(block: Block) {
    val colors = LocalReaderColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent))
        BlockText(
            "callout", markup(block.text("text").orEmpty()), AppFonts.text, 18f, 26f, colors.ink2,
            modifier = Modifier.padding(start = 16.dp), italic = true,
        )
    }
}

@Composable
fun GlossaryBlock(block: Block) {
    val colors = LocalReaderColors.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.rule)
        block.pairs("terms").forEach { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BlockText(
                    "glossary", markup(row.getOrNull(0).orEmpty()), AppFonts.mono, 13f, 18f, colors.accent,
                    weight = FontWeight.SemiBold,
                )
                BlockText("glossary", markup(row.getOrNull(1).orEmpty()), AppFonts.text, 16f, 22f, colors.ink)
            }
            HorizontalDivider(color = colors.rule)
        }
    }
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
                BlockText(
                    "list", markup(line), AppFonts.text, 17f, 25f, colors.ink, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun ImageBlock(block: Block) {
    val colors = LocalReaderColors.current
    val loader = LocalImageLoader.current
    val src = block.text("src").orEmpty()
    val loaded by produceState<Pair<Boolean, ImageBitmap?>>(false to null, src, loader) {
        value = true to (if (src.isBlank()) null else loader.load(src))
    }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val bitmap = loaded.second
        when {
            bitmap != null -> Image(
                bitmap, contentDescription = block.text("alt").orEmpty(), contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.rule, shape).testTag("image-loaded"),
            )
            loaded.first -> Box(
                Modifier.fillMaxWidth().border(1.dp, colors.rule, shape).padding(16.dp).testTag("image-unavailable"),
            ) {
                Text(
                    stringResource(R.string.block_image_unavailable), color = colors.ink3, fontFamily = AppFonts.text,
                    fontStyle = FontStyle.Italic, fontSize = scaled(15f), lineHeight = scaled(22f),
                )
            }
            else -> Box(
                Modifier.fillMaxWidth().height(180.dp).clip(shape).background(colors.rule.copy(alpha = 0.35f))
                    .testTag("image-loading"),
            )
        }
        val caption = markup(block.text("caption").orEmpty())
        val credit = block.text("credit")
        val creditLabel = stringResource(R.string.block_image_credit)
        val line = remember(caption, credit, creditLabel, colors) {
            buildAnnotatedString {
                append(caption)
                if (!credit.isNullOrBlank()) {
                    if (length > 0) append(" ")
                    append("$creditLabel ")
                    append(linkText(credit, block.text("href"), colors.accent))
                    append(".")
                }
            }
        }
        if (line.isNotEmpty()) {
            BlockText("image", line, AppFonts.text, 14f, 20f, colors.ink3)
        }
    }
}

@Composable
fun VideoBlock(block: Block) {
    val colors = LocalReaderColors.current
    val url = block.text("url").orEmpty()
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.rule, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("▶", color = colors.accent, fontSize = scaled(16f))
            BlockText(
                "video", linkText(block.text("title").orEmpty(), url, colors.accent), AppFonts.text, 17f, 24f,
                colors.ink, weight = FontWeight.SemiBold,
            )
        }
        block.text("credit")?.let {
            Text(it, color = colors.ink3, fontFamily = AppFonts.mono, fontSize = scaled(11.5f))
        }
        Text(
            linkText(url, url, colors.accent), fontFamily = AppFonts.mono, fontSize = scaled(12f),
            color = colors.ink3,
        )
    }
}

@Composable
fun CodeBlock(block: Block) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(colors.rule.copy(alpha = 0.35f)).horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        BlockText("code", block.text("code").orEmpty(), AppFonts.mono, 13f, 19f, colors.ink)
    }
}

@Composable
fun FactsBlock(block: Block) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(stringResource(R.string.block_facts_label), colors.ink3)
        block.strings("items").forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", color = colors.accent, fontFamily = AppFonts.mono, fontSize = scaled(14f), modifier = Modifier.width(24.dp))
                BlockText("facts", markup(line), AppFonts.text, 17f, 25f, colors.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The tint and the dashed edge of the hypothesis box, per reading theme (the prototype's `--hyp` and `--hyp-rule`). */
private fun hypothesisColors(colors: ReaderColors): Pair<Color, Color> = when {
    colors.dark -> Color(0xFF1D2027) to Color(0xFF7D6A3F)
    colors.page == Color(0xFFEFE3C9) -> Color(0xFFE6D6B0) to Color(0xFF9B7C3C)
    else -> Color(0xFFF1E7D0) to Color(0xFFB89B5E)
}

@Composable
fun HypothesisBlock(block: Block) {
    val colors = LocalReaderColors.current
    val (tint, edge) = hypothesisColors(colors)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint)
            .drawBehind {
                drawRoundRect(
                    edge, cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(stringResource(R.string.block_hypothesis_label), colors.ink2)
        block.strings("paragraphs").forEach {
            BlockText("hypothesis", markup(it), AppFonts.text, 17f, 25f, colors.ink)
        }
    }
}

@Composable
fun FactSheetBlock(block: Block) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(12.dp)
    val difficulty = block.int("difficulty")
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.rule, shape).background(colors.page)) {
        block.pairs("rows").chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEach { row ->
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            row.getOrNull(0).orEmpty(), color = colors.ink3, fontFamily = AppFonts.mono,
                            fontSize = scaled(10.5f), letterSpacing = 0.8.sp,
                        )
                        BlockText("factsheet", markup(row.getOrNull(1).orEmpty()), AppFonts.text, 15f, 20f, colors.ink)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
            HorizontalDivider(color = colors.rule)
        }
        if (difficulty != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.block_difficulty), color = colors.ink3, fontFamily = AppFonts.mono,
                    fontSize = scaled(10.5f), letterSpacing = 0.8.sp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("difficulty-$difficulty"),
                ) {
                    (1..3).forEach { i ->
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(if (i <= difficulty) colors.accent else colors.rule),
                        )
                    }
                }
                block.text("difficultyNote")?.let {
                    BlockText("factsheet", markup(it), AppFonts.text, 15f, 20f, colors.ink)
                }
            }
        }
    }
}

@Composable
fun QuoteBlock(block: Block) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BlockText(
            "quote", "“${block.text("text").orEmpty()}”", AppFonts.display, 21f, 28f, colors.ink,
            weight = FontWeight(400), italic = true,
        )
        val pt = block.text("pt").orEmpty()
        val who = block.text("who").orEmpty()
        val footer = remember(pt, who, colors) {
            buildAnnotatedString {
                if (pt.isNotEmpty()) append("$pt ")
                append(linkText(who, block.text("url"), colors.accent))
            }
        }
        Text(footer, color = colors.ink3, fontFamily = AppFonts.mono, fontSize = scaled(12f), lineHeight = scaled(18f))
    }
}

@Composable
fun EvidenceBlock(block: Block) {
    val colors = LocalReaderColors.current
    Column(Modifier.fillMaxWidth()) {
        block.pairs("rows").forEach { row ->
            HorizontalDivider(color = colors.rule)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    row.getOrNull(0).orEmpty(), color = colors.accent, fontFamily = AppFonts.mono,
                    fontSize = scaled(12f), modifier = Modifier.width(88.dp).padding(top = 2.dp),
                )
                BlockText(
                    "evidence", markup(row.getOrNull(1).orEmpty()), AppFonts.text, 15.5f, 22f, colors.ink,
                    modifier = Modifier.weight(1f),
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
        BlockText(
            "note", markup(block.text("text").orEmpty()), AppFonts.text, 15f, 22f, colors.ink2,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
fun WhyBlock(block: Block) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.rule, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(stringResource(R.string.block_why_heading), colors.ink3)
        BlockText("why", markup(block.text("text").orEmpty()), AppFonts.text, 16f, 23f, colors.ink)
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
                    linkText(label, url, colors.accent), color = colors.ink,
                    fontFamily = AppFonts.text, fontSize = scaled(16f), lineHeight = scaled(22f),
                )
                link.text("note")?.let {
                    Text(it, color = colors.ink3, fontFamily = AppFonts.text, fontSize = scaled(14f))
                }
            }
        }
    }
}
