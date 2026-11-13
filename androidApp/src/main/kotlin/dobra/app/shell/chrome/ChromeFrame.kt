package dobra.app.shell.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

/**
 * The reader's page with its controls. With fixed controls the page and the pager share the height as a column. In the
 * hiding mode the page takes all of it and the top bar and the [bottom] pager are drawn over it, so showing or hiding
 * them never moves the page; a tap on the page (one that no link or button took) shows or hides them, and a small handle
 * with the position [handleText] stays where they were while they are away.
 */
@Composable
fun ChromeFrame(
    chrome: ChromeState,
    title: String,
    handleText: String,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!chrome.auto) {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().then(contentModifier)) { content() }
            bottom()
        }
        return
    }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().then(contentModifier).pointerInput(chrome) {
                detectTapGestures(onTap = { chrome.toggle() })
            },
        ) { content() }
        if (chrome.visible) {
            ChromeTopBar(title, Modifier.align(Alignment.TopCenter))
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { chrome.tabBarPx.toDp() }),
            ) { bottom() }
        } else {
            ChromeHandle(handleText, Modifier.align(Alignment.BottomCenter), onClick = { chrome.show() })
        }
    }
}

@Composable
private fun ChromeTopBar(title: String, modifier: Modifier) {
    val colors = LocalReaderColors.current
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind {
                drawLine(colors.rule, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .statusBarsPadding()
            .testTag("top-bar")
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            title, color = colors.ink2, fontFamily = AppFonts.mono, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small pill that stays while the controls are away: it shows the position and brings them back. */
@Composable
private fun ChromeHandle(text: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    val description = stringResource(R.string.chrome_handle, text)
    Box(
        modifier.navigationBarsPadding().padding(bottom = 6.dp).testTag("chrome-handle-area"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .heightIn(min = 32.dp)
                .background(colors.bg, RoundedCornerShape(16.dp))
                .border(1.dp, colors.rule, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 14.dp)
                .testTag("chrome-handle"),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = colors.ink2, fontFamily = AppFonts.mono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag("chrome-handle-text"))
        }
    }
}
