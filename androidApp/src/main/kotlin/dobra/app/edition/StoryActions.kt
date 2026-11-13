package dobra.app.edition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
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
import dobra.app.shell.ActionGlyph
import dobra.app.shell.ActionIcon
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.LocalTextScale
import dobra.store.Feedback

/** One pill button of the story: filled when [on], outlined otherwise. At least 44 dp tall. */
@Composable
internal fun PillButton(label: String, tag: String, on: Boolean, accent: Boolean = false, icon: ActionGlyph? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(22.dp)
    val filled = on || accent
    val ink = if (filled) colors.accentInk else colors.ink
    Row(
        modifier.testTag(tag).heightIn(min = 44.dp).clip(shape)
            .background(if (filled) (if (accent && !on) colors.accent else colors.ink) else colors.page)
            .border(1.dp, if (filled) colors.ink else colors.rule, shape)
            .semantics { role = Role.Button; selected = on }
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ActionIcon(icon, color = ink)
        Text(label, color = ink, fontFamily = AppFonts.mono, fontWeight = FontWeight.SemiBold,
            fontSize = (13 * LocalTextScale.current).sp)
    }
}

/**
 * The buttons under the head of a story: ask the AI ([onAsk] opens the panel), save, and say more or less of this.
 * [feedback] is the reader's current choice; touching it again clears it.
 */
@Composable
internal fun StoryActions(
    itemId: String,
    saved: Boolean,
    feedback: Feedback?,
    onSave: () -> Unit,
    onFeedback: (Feedback) -> Unit,
    onAsk: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().testTag("actions-$itemId"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.story_ask_ai), "ask-$itemId", false, accent = true,
                icon = ActionGlyph.ASK, modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onAsk)
            PillButton(stringResource(if (saved) R.string.story_saved else R.string.story_save), "save-$itemId", saved,
                icon = ActionGlyph.SAVE, modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onSave)
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.story_more), "more-$itemId", feedback == Feedback.MORE,
                icon = ActionGlyph.MORE, modifier = Modifier.weight(1f).fillMaxHeight()) { onFeedback(Feedback.MORE) }
            PillButton(stringResource(R.string.story_less), "less-$itemId", feedback == Feedback.LESS,
                icon = ActionGlyph.LESS, modifier = Modifier.weight(1f).fillMaxHeight()) { onFeedback(Feedback.LESS) }
        }
    }
}

/** The Salvar button at the foot of a short note. */
@Composable
internal fun NoteSave(itemId: String, saved: Boolean, onSave: () -> Unit) {
    PillButton(
        stringResource(if (saved) R.string.story_saved else R.string.story_save), "save-$itemId", saved, onClick = onSave,
    )
}
