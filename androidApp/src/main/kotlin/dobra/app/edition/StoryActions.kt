package dobra.app.edition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.LocalTextScale
import dobra.store.Feedback

/** One pill button of the story: filled when [on], outlined otherwise. At least 44 dp tall. */
@Composable
internal fun PillButton(label: String, tag: String, on: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(22.dp)
    val filled = on || accent
    Text(
        label,
        color = if (filled) colors.accentInk else colors.ink,
        fontFamily = AppFonts.mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = (13 * LocalTextScale.current).sp,
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (filled) (if (accent && !on) colors.accent else colors.ink) else colors.page)
            .border(1.dp, if (filled) colors.ink else colors.rule, shape)
            .semantics { role = Role.Button; selected = on }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * The buttons under the head of a story: ask the AI (drawn, opens nothing yet), save, and say more or less of this.
 * [feedback] is the reader's current choice; touching it again clears it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoryActions(
    itemId: String,
    saved: Boolean,
    feedback: Feedback?,
    onSave: () -> Unit,
    onFeedback: (Feedback) -> Unit,
) {
    FlowRow(
        Modifier.testTag("actions-$itemId"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillButton(stringResource(R.string.story_ask_ai), "ask-$itemId", on = false, accent = true) {}
        PillButton(
            stringResource(if (saved) R.string.story_saved else R.string.story_save), "save-$itemId", saved, onClick = onSave,
        )
        PillButton(stringResource(R.string.story_more), "more-$itemId", feedback == Feedback.MORE) { onFeedback(Feedback.MORE) }
        PillButton(stringResource(R.string.story_less), "less-$itemId", feedback == Feedback.LESS) { onFeedback(Feedback.LESS) }
    }
}

/** The Salvar button at the foot of a short note. */
@Composable
internal fun NoteSave(itemId: String, saved: Boolean, onSave: () -> Unit) {
    PillButton(
        stringResource(if (saved) R.string.story_saved else R.string.story_save), "save-$itemId", saved, onClick = onSave,
    )
}
