package dobra.app.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors

/** A scrolling page of the library with the status bar left free. */
@Composable
internal fun LibraryPage(tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
internal fun Kicker(text: String) {
    Text(text, color = LocalReaderColors.current.accent, fontFamily = AppFonts.mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Headline(text: String, size: Int = 28) {
    Text(text, color = LocalReaderColors.current.ink, fontFamily = AppFonts.display, fontSize = size.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Body(text: String, tag: String? = null) {
    Text(
        text,
        color = LocalReaderColors.current.ink2,
        fontFamily = AppFonts.text,
        fontSize = 16.sp,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
    )
}

@Composable
internal fun Meta(text: String, tag: String? = null) {
    Text(
        text,
        color = LocalReaderColors.current.ink3,
        fontFamily = AppFonts.mono,
        fontSize = 12.sp,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
    )
}

@Composable
internal fun SectionTitle(text: String) {
    val colors = LocalReaderColors.current
    Column(Modifier.padding(top = 10.dp)) {
        Text(text, color = colors.ink, fontFamily = AppFonts.display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 1.dp).background(colors.rule))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Chips(items: List<String>, tag: String? = null) {
    val colors = LocalReaderColors.current
    FlowRow(
        Modifier.then(if (tag == null) Modifier else Modifier.testTag(tag)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach {
            Text(
                it,
                color = colors.ink2,
                fontFamily = AppFonts.mono,
                fontSize = 11.sp,
                modifier = Modifier
                    .border(BorderStroke(1.dp, colors.rule), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** A button drawn as text in a frame; [primary] fills it with the accent colour. */
@Composable
internal fun Btn(label: String, tag: String, primary: Boolean = false, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .background(if (primary) colors.accent else colors.page, shape)
            .border(BorderStroke(1.dp, if (primary) colors.accent else colors.rule), shape)
            .testTag(tag)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) colors.accentInk else colors.ink,
            fontFamily = AppFonts.mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BtnRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A framed card of the library. */
@Composable
internal fun Card(tag: String, content: @Composable () -> Unit) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.page, shape)
            .border(BorderStroke(1.dp, colors.rule), shape)
            .padding(14.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A labelled text field. */
@Composable
internal fun Field(
    label: String,
    value: String,
    tag: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    numeric: Boolean = false,
) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Meta(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = colors.ink, fontFamily = AppFonts.text, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(colors.bg, RoundedCornerShape(6.dp))
                .border(BorderStroke(1.dp, colors.rule), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .testTag(tag),
        )
    }
}

/** One choice among a few, drawn as a chip that is filled when picked. */
@Composable
internal fun Choice(label: String, picked: Boolean, tag: String, onPick: () -> Unit) {
    val colors = LocalReaderColors.current
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (picked) colors.accentInk else colors.ink2,
        fontFamily = AppFonts.mono,
        fontSize = 12.sp,
        modifier = Modifier
            .background(if (picked) colors.accent else colors.page, shape)
            .border(BorderStroke(1.dp, if (picked) colors.accent else colors.rule), shape)
            .testTag(tag)
            .semantics { role = Role.RadioButton }
            .clickable(onClick = onPick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
    )
}

@Composable
internal fun Empty(title: String, body: String, tag: String) {
    Card(tag) {
        Text(title, color = LocalReaderColors.current.ink, fontFamily = AppFonts.display, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Body(body)
    }
}

/** The question shown before something is deleted; nothing changes until "Apagar". */
@Composable
internal fun ConfirmCard(title: String, body: String, onYes: () -> Unit, onNo: () -> Unit) {
    Card("confirm") {
        Kicker(title)
        Body(body)
        BtnRow {
            Btn(stringResource(R.string.confirm_yes), "confirm-yes", primary = true, onClick = onYes)
            Btn(stringResource(R.string.confirm_no), "confirm-no", onClick = onNo)
        }
    }
}

@Composable
internal fun countOf(n: Int, one: Int, many: Int): String = stringResource(if (n == 1) one else many, n)
