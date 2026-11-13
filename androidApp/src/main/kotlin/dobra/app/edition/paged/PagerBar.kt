package dobra.app.edition.paged

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.store.ReadingMode

@Composable
internal fun modeLabel(mode: ReadingMode): String = stringResource(
    when (mode) {
        ReadingMode.BOOK -> R.string.mode_book
        ReadingMode.SHEET -> R.string.mode_sheet
        ReadingMode.SCROLL -> R.string.mode_scroll
    },
)

/**
 * The pager under the edition: previous, the chapter being read, the mode chip, the counter and next, with a thin bar
 * of progress along its top edge.
 */
@Composable
internal fun PagerBar(
    label: String,
    count: String,
    progress: Float,
    mode: ReadingMode,
    atStart: Boolean,
    atEnd: Boolean,
    scroll: Boolean,
    onStep: (Int) -> Unit,
    onCycle: () -> Unit,
) {
    val colors = LocalReaderColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind {
                drawLine(colors.rule, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                drawLine(colors.accent, Offset(0f, 0f), Offset(size.width * progress.coerceIn(0f, 1f), 0f), strokeWidth = 3.dp.toPx())
            }
            .testTag("pager"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val prev = stringResource(if (scroll) R.string.section_previous else R.string.page_previous)
            val next = stringResource(if (scroll) R.string.section_next else R.string.page_next)
            Arrow(back = true, enabled = !atStart, description = prev, tag = "page-prev") { onStep(-1) }
            Text(
                label, color = colors.ink, fontFamily = AppFonts.mono, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("pager-label"),
            )
            val name = modeLabel(mode)
            val chipDescription = stringResource(R.string.mode_change, name)
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, colors.rule, RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button, onClick = onCycle)
                    .semantics { contentDescription = chipDescription }
                    .padding(horizontal = 14.dp)
                    .testTag("mode-chip"),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, color = colors.ink2, fontFamily = AppFonts.mono, fontSize = 12.sp)
            }
            Text(
                count, color = colors.ink2, fontFamily = AppFonts.mono, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.testTag("pager-count"),
            )
            Arrow(back = false, enabled = !atEnd, description = next, tag = "page-next") { onStep(1) }
        }
    }
}

@Composable
private fun Arrow(back: Boolean, enabled: Boolean, description: String, tag: String, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(1.dp, colors.rule, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val path = Path().apply {
                if (back) {
                    moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.32f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f)
                } else {
                    moveTo(w * 0.38f, w * 0.2f); lineTo(w * 0.68f, w * 0.5f); lineTo(w * 0.38f, w * 0.8f)
                }
            }
            drawPath(path, colors.ink, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
