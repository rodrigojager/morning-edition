package dobra.app.edition.paged

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dobra.app.edition.CutSink
import dobra.app.edition.LocalCutSink
import dobra.reader.FlowChapter
import dobra.reader.Piece
import kotlin.math.roundToInt

/** The flow measured at one page size, in pixels. */
internal data class Measured(val width: Int, val pageHeight: Int, val flow: List<FlowChapter>)

/** Collects, for one piece, its laid out node and the lines and entries its blocks report. */
private class PieceProbe : CutSink {
    var root: LayoutCoordinates? = null
    private val texts = LinkedHashMap<Any, Pair<LayoutCoordinates, TextLayoutResult>>()
    private val entries = LinkedHashMap<Any, LayoutCoordinates>()

    override fun lines(key: Any, coordinates: LayoutCoordinates, layout: TextLayoutResult) {
        texts[key] = coordinates to layout
    }

    override fun entry(key: Any, coordinates: LayoutCoordinates) {
        entries[key] = coordinates
    }

    fun piece(spec: PieceSpec, space: Float): Piece? {
        val r = root?.takeIf { it.isAttached } ?: return null
        val height = r.size.height
        val cuts = when (spec.rule) {
            CutRule.WHOLE -> emptyList()
            CutRule.LINES -> texts.values.filter { it.first.isAttached }.flatMap { (c, layout) ->
                val y = r.localPositionOf(c, Offset.Zero).y
                (0 until layout.lineCount).map { y + layout.getLineBottom(it) }
            }
            CutRule.ENTRIES -> entries.values.filter { it.isAttached }.map { r.localPositionOf(it, Offset.Zero).y + it.size.height }
        }.map { it.roundToInt() }.filter { it in 1 until height }.distinct().sorted()
        return Piece(height.toFloat(), cuts.map { it.toFloat() }, spec.keepWithNext, space)
    }
}

/**
 * Lays every piece out at the page [width], out of sight and out of the semantics tree, and hands the measured flow to
 * [onMeasured] after each layout that changed it (a different width or text size, an image that arrived).
 */
@Composable
internal fun MeasureLayer(chapters: List<ChapterSpec>, width: Int, pageHeight: Int, onMeasured: (Measured) -> Unit) {
    val density = LocalDensity.current
    val probes = remember(chapters) { chapters.map { ch -> ch.pieces.map { PieceProbe() } } }
    var laidOut by remember { mutableIntStateOf(0) }
    val lastSize = remember { arrayOf(IntSize.Zero) }
    val report by rememberUpdatedState(onMeasured)
    Box(Modifier.size(0.dp).clipToBounds().clearAndSetSemantics {}.drawWithContent {}) {
        Column(
            Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                    layout(0, 0) { placeable.place(0, 0) }
                }
                .onGloballyPositioned {
                    if (it.size != lastSize[0]) {
                        lastSize[0] = it.size
                        laidOut++
                    }
                },
        ) {
            chapters.forEachIndexed { c, chapter ->
                chapter.pieces.forEachIndexed { i, spec ->
                    if (!spec.fill) {
                        val probe = probes[c][i]
                        key(chapter.id, spec.key) {
                            Box(Modifier.onGloballyPositioned { probe.root = it }) {
                                CompositionLocalProvider(LocalCutSink provides probe) { spec.content() }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(laidOut, probes, width, pageHeight) {
        if (laidOut == 0) return@LaunchedEffect
        withFrameNanos { }
        val flow = chapters.mapIndexed { c, chapter ->
            FlowChapter(
                chapter.id,
                chapter.pieces.mapIndexed { i, spec ->
                    val space = with(density) { spec.space.toPx() }.roundToInt().toFloat()
                    if (spec.fill) Piece(pageHeight.toFloat(), spaceBefore = space, fill = true)
                    else probes[c][i].piece(spec, space) ?: return@LaunchedEffect
                },
            )
        }
        report(Measured(width, pageHeight, flow))
    }
}
