package dobra.app.edition

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.ReaderColors
import dobra.model.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/** Which colour of the page a stroke or fill may be forced to, besides the class of the shape. */
enum class PlateInk { INK, PAGE }

/**
 * What a motif can draw, in the 400 by 225 space of the prototype's plates. Classes: `p1` solid accent hue, `p2` soft hue,
 * `s1` thin ink line, `s2` hue line, `ink` and `pg` flat ink and page fills.
 */
interface PlateScope {
    fun circle(cls: String, cx: Float, cy: Float, r: Float, dash: Float? = null, dashGap: Float = dash ?: 0f)
    fun rect(cls: String, x: Float, y: Float, w: Float, h: Float, rx: Float = 0f)
    fun path(
        cls: String, d: String, dash: Float? = null, dashGap: Float = dash ?: 0f,
        stroke: PlateInk? = null, width: Float? = null, round: Boolean = false,
    )
}

/** The eight motifs of the demonstration edition, ported from the prototype's `MOTIFS` table. */
object Motifs {
    val table: Map<String, PlateScope.() -> Unit> = mapOf(
        "rings" to {
            for (i in 1..6) circle(if (i == 3) "s2" else "s1", 262f, 112f, i * 16f)
            circle("p1", 262f, 112f, 7f)
            circle("ink", 310f, 112f, 5f)
            path("s1", "M262 112h43")
        },
        "chain" to {
            for (i in 0 until 5) {
                rect(if (i == 2) "p1" else "s1", 96f + i * 56, 86f, 42f, 42f, 9f)
                path("s2", "M${104 + i * 56} 150h26")
                if (i < 4) path("s1", "M${138 + i * 56} 107h14")
            }
            path("s1", "M96 164h266", dash = 3f, dashGap = 6f)
        },
        "leak" to {
            for (i in 0 until 5) rect("p2", 110f, 62f + i * 24, if (i == 2) 96f else 190f - i * 14, 9f, 2f)
            rect("p1", 286f, 64f, 78f, 13f, 3f)
            path("s2", "M212 114c40 0 46-42 70-44", dash = 4f, dashGap = 5f)
            circle("s1", 325f, 150f, 20f)
            circle("s1", 325f, 150f, 7f)
            path("s1", "M332 150v5c0 6 12 5 12-5")
        },
        "blocks" to {
            rect("s1", 104f, 44f, 250f, 150f, 10f)
            path("s1", "M104 68h250")
            circle("ink", 118f, 56f, 3f)
            circle("ink", 130f, 56f, 3f)
            rect("p2", 116f, 80f, 226f, 102f, 4f)
            rect("p1", 170f, 98f, 118f, 68f, 8f)
            rect("pg", 184f, 114f, 60f, 7f, 2f)
            rect("pg", 184f, 130f, 90f, 5f, 2f)
            rect("pg", 244f, 146f, 30f, 10f, 5f)
        },
        "mirror" to {
            fun cube(x: Int, y: Int, c: String) {
                path(c, "M$x ${y}l46-24 46 24-46 24z")
                path(c, "M$x ${y}v48l46 24v-48z")
                path(c, "M${x + 92} ${y}v48l-46 24v-48z")
            }
            cube(96, 84, "s1")
            cube(236, 84, "p1")
            path("s2", "M212 40v150", dash = 5f, dashGap = 6f)
            path("s1", "M250 96l32 16M250 108l32 16", stroke = PlateInk.PAGE)
        },
        "hub" to {
            for (i in 0 until 7) {
                val a = i / 7.0 * Math.PI * 2 - 1.2
                val x = (250 + Math.cos(a) * 84).toFloat()
                val y = (112 + Math.sin(a) * 70).toFloat()
                val open = i == 2 || i == 5
                path("s1", "M250 112L$x $y", dash = if (open) 3f else null, dashGap = 5f)
                circle(if (open) "s2" else "ink", x, y, if (open) 10f else 7f, dash = if (open) 3f else null, dashGap = 4f)
            }
            circle("p1", 250f, 112f, 22f)
            circle("pg", 250f, 112f, 7f)
        },
        "parcel" to {
            path("s1", "M250 40l66 38v76l-66 38-66-38V78z")
            path("p2", "M250 62l47 27v54l-47 27-47-27V89z")
            path("s1", "M184 78l66 38 66-38M250 116v76")
            path("pg", "M228 84h50l14 14v56h-64z", stroke = PlateInk.INK, width = 1.6f)
            path("s1", "M278 84v14h14")
            path("s2", "M238 112h40M238 124h40M238 136h26")
        },
        "seal" to {
            for (i in 0 until 5) {
                val x = 110 + i * 60
                if (i < 3) {
                    circle("p1", x.toFloat(), 108f, 22f)
                    path("s1", "M${x - 9} 108l7 7 12-14", stroke = PlateInk.PAGE, width = 3.4f, round = true)
                } else {
                    circle("s1", x.toFloat(), 108f, 22f, dash = 4f, dashGap = 5f)
                    path("s1", "M${x - 6} 108h12")
                }
            }
            path("s1", "M88 152h264")
            path("s2", "M88 164h150")
        },
    )

    val names: Set<String> get() = table.keys

    /** The motif to draw for [name]; an unknown name draws the rings, as in the prototype. */
    fun of(name: String?): PlateScope.() -> Unit = table[name] ?: table.getValue("rings")
}

/** The number of the story on the page, written on its plate. Zero draws no number. */
val LocalStoryNumber = compositionLocalOf { 0 }

private fun hue(h: Float, s: Float, l: Float) = Color.hsl(((h % 360f) + 360f) % 360f, s, l)

private class CanvasPlate(
    private val scope: DrawScope,
    private val colors: ReaderColors,
    private val hue: Float,
) : PlateScope {
    private val accent = if (colors.dark) hue(hue, 0.70f, 0.62f) else hue(hue, 0.58f, 0.42f)
    private val soft = lerp(hue(hue, 0.60f, 0.50f), colors.page, 0.58f)

    private fun fill(cls: String): Color? = when (cls) {
        "p1" -> accent
        "p2" -> soft
        "ink" -> colors.ink
        "pg" -> colors.page
        else -> null
    }

    private fun line(cls: String): Pair<Color, Float>? = when (cls) {
        "s1" -> colors.ink to 1.6f
        "s2" -> accent to 2.4f
        else -> null
    }

    private fun dashed(on: Float?, gap: Float) = on?.let { PathEffect.dashPathEffect(floatArrayOf(it, gap)) }

    override fun circle(cls: String, cx: Float, cy: Float, r: Float, dash: Float?, dashGap: Float) {
        fill(cls)?.let { scope.drawCircle(it, r, Offset(cx, cy)) }
        line(cls)?.let { (c, w) ->
            scope.drawCircle(c, r, Offset(cx, cy), style = Stroke(w, pathEffect = dashed(dash, dashGap)))
        }
    }

    override fun rect(cls: String, x: Float, y: Float, w: Float, h: Float, rx: Float) {
        fill(cls)?.let { scope.drawRoundRect(it, Offset(x, y), Size(w, h), CornerRadius(rx)) }
        line(cls)?.let { (c, sw) ->
            scope.drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(rx), style = Stroke(sw))
        }
    }

    override fun path(cls: String, d: String, dash: Float?, dashGap: Float, stroke: PlateInk?, width: Float?, round: Boolean) {
        val p = PathParser().parsePathString(d).toPath()
        fill(cls)?.let { scope.drawPath(p, it) }
        val own = line(cls)
        val color = when (stroke) {
            PlateInk.INK -> colors.ink
            PlateInk.PAGE -> colors.page
            null -> own?.first
        }
        if (color != null) {
            scope.drawPath(
                p, color,
                style = Stroke(
                    width ?: own?.second ?: 1.6f,
                    cap = if (round) StrokeCap.Round else StrokeCap.Butt,
                    join = if (round) StrokeJoin.Round else StrokeJoin.Miter,
                    pathEffect = dashed(dash, dashGap),
                ),
            )
        }
    }
}

/** Draws [motif] on a plate of the given hue, the way the prototype's SVG plate does: 400 by 225, cropped to fill. */
private fun DrawScope.drawPlate(
    motif: String?, hueDeg: Float, colors: ReaderColors, number: Int, measurer: TextMeasurer,
) {
    drawRect(lerp(hue(hueDeg, 0.60f, 0.50f), colors.page, 0.84f))
    val k = maxOf(size.width / 400f, size.height / 225f)
    val dx = (size.width - 400f * k) / 2f
    val dy = (size.height - 225f * k) / 2f
    clipRect {
        withTransform({
            translate(dx, dy)
            scale(k, k, pivot = Offset.Zero)
        }) {
            Motifs.of(motif).invoke(CanvasPlate(this, colors, hueDeg))
            if (number > 0) {
                drawText(
                    measurer, number.toString().padStart(2, '0'), Offset(18f, 208f - 70f * 0.8f),
                    style = TextStyle(
                        color = colors.ink.copy(alpha = 0.9f), fontSize = 70.sp, fontFamily = AppFonts.display,
                        fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light,
                    ),
                )
            }
        }
    }
}

/** The illustration block: a plate drawn by the app, announced as generated by it. */
@Composable
fun IllustrationBlock(block: Block) {
    val colors = LocalReaderColors.current
    val art = block.fields["art"] as? JsonObject
    val motif = (art?.get("motif") as? JsonPrimitive)?.contentOrNull
    val alt = (art?.get("alt") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val hueDeg = (art?.get("hue") as? JsonPrimitive)?.floatOrNull ?: 20f
    val number = LocalStoryNumber.current
    val measurer = rememberTextMeasurer()
    val description = stringResource(R.string.block_plate_description, alt)
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 4.dp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 8f)
            .clip(shape)
            .border(1.dp, colors.rule, shape)
            .testTag("plate-${if (motif in Motifs.names) motif else "rings"}")
            .semantics { contentDescription = description },
    ) { drawPlate(motif, hueDeg, colors, number, measurer) }
}
