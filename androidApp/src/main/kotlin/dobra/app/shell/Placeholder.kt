package dobra.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A one-line screen for a tab whose feature comes in a later task. */
@Composable
fun PlaceholderScreen(text: String, tag: String) {
    val colors = LocalReaderColors.current
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = colors.ink2, fontFamily = AppFonts.text, fontSize = 17.sp)
    }
}
