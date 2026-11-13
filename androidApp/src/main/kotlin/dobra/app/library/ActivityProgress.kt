package dobra.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dobra.app.shell.LocalReaderColors
import dobra.make.MakeProgress
import dobra.make.MakeStage

/** Activity remains visible even in phases whose total is not known yet. Counts describe the current phase. */
@Composable
internal fun ActivityProgress(label: String, tag: String, progress: MakeProgress? = null) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp).testTag("$tag-spinner"), color = colors.accent, strokeWidth = 2.dp)
            Meta(label, tag)
        }
        val bar = Modifier.fillMaxWidth().testTag("$tag-bar")
        if (progress != null && progress.total > 0 && progress.stage != MakeStage.CHOOSING) {
            LinearProgressIndicator(
                progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = bar, color = colors.accent, trackColor = colors.rule,
            )
        } else {
            LinearProgressIndicator(modifier = bar, color = colors.accent, trackColor = colors.rule)
        }
    }
}
