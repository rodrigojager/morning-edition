package dobra.app.shell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal enum class ActionGlyph { ASK, SAVE, MORE, LESS, READ, UNREAD, DELETE, OPEN, CONTENTS }

/** Small native line icons; the containing button supplies the accessible label. */
@Composable
internal fun ActionIcon(glyph: ActionGlyph, modifier: Modifier = Modifier, color: Color = LocalReaderColors.current.ink) {
    Canvas(modifier.size(18.dp)) {
        val path = Path().apply {
            fun line(vararg points: Float) {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
            }
            when (glyph) {
                ActionGlyph.ASK -> line(4f, 3f, 20f, 3f, 20f, 16f, 10f, 16f, 4f, 21f, 4f, 3f)
                ActionGlyph.SAVE -> line(6f, 3f, 18f, 3f, 18f, 21f, 12f, 17f, 6f, 21f, 6f, 3f)
                ActionGlyph.MORE -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                ActionGlyph.LESS -> line(5f, 12f, 19f, 12f)
                ActionGlyph.READ -> line(4f, 12f, 9f, 17f, 20f, 6f)
                ActionGlyph.UNREAD -> { line(4f, 5f, 20f, 5f, 20f, 19f, 4f, 19f, 4f, 5f); line(4f, 5f, 12f, 12f, 20f, 5f) }
                ActionGlyph.DELETE -> { line(3f, 6f, 21f, 6f); line(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f); line(9f, 6f, 9f, 3f, 15f, 3f, 15f, 6f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
                ActionGlyph.OPEN -> { line(14f, 3f, 21f, 3f, 21f, 10f); line(21f, 3f, 11f, 13f); line(9f, 5f, 3f, 5f, 3f, 21f, 19f, 21f, 19f, 15f) }
                ActionGlyph.CONTENTS -> { for (y in listOf(5f, 12f, 19f)) { line(3f, y, 5f, y); line(9f, y, 21f, y) } }
            }
        }
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
