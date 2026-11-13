package dobra.app.work

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.model.Magazine
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val When: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm")

/** How the screen writes a moment: "14/11 às 07:00". */
fun whenText(at: ZonedDateTime): String = at.format(When)

/**
 * "Geração de edições": the two conditions (only on Wi-Fi, only while charging) and, per magazine, when the next
 * edition is due and how the last one made in the background went. Opening it for the first time is when the app first
 * needs to notify, so the permission is asked then, once.
 */
@Composable
fun GenerationScreen(
    generation: Generation,
    magazines: List<Magazine>,
    onBack: () -> Unit,
    askNotify: (() -> Unit)? = null,
) {
    val colors = LocalReaderColors.current
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (EditionNotices.shouldAsk(context, generation)) {
            generation.notifyAsked = true
            askNotify?.invoke() ?: launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val options by generation.options.collectAsState()
    val runs by generation.runs.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("screen-generation"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.generation_back),
            color = colors.accent,
            fontFamily = AppFonts.mono,
            fontSize = 13.sp,
            modifier = Modifier.testTag("generation-back").clickable(role = Role.Button, onClick = onBack).padding(vertical = 8.dp),
        )
        Text(stringResource(R.string.generation_title), color = colors.ink, fontFamily = AppFonts.display, fontSize = 28.sp)
        Text(stringResource(R.string.generation_about), color = colors.ink2, fontFamily = AppFonts.text, fontSize = 15.sp)
        Label(stringResource(R.string.generation_conditions))
        Option(stringResource(R.string.generation_wifi), options.wifiOnly, "generation-wifi") {
            generation.setOptions(options.copy(wifiOnly = it))
        }
        Option(stringResource(R.string.generation_charging), options.chargingOnly, "generation-charging") {
            generation.setOptions(options.copy(chargingOnly = it))
        }
        Label(stringResource(R.string.generation_magazines))
        magazines.forEach { magazine ->
            val last = remember(runs, magazine.id) { generation.lastRun(magazine.id) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
                    .padding(14.dp)
                    .testTag("generation-mag-${magazine.id}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(magazine.name, color = colors.ink, fontFamily = AppFonts.display, fontSize = 18.sp)
                Text(
                    stringResource(R.string.generation_next, whenText(generation.nextDue(magazine))),
                    color = colors.ink2, fontFamily = AppFonts.mono, fontSize = 12.sp,
                    modifier = Modifier.testTag("generation-next-${magazine.id}"),
                )
                Text(
                    lastText(last),
                    color = colors.ink3, fontFamily = AppFonts.mono, fontSize = 12.sp,
                    modifier = Modifier.testTag("generation-last-${magazine.id}"),
                )
            }
        }
        Text(stringResource(R.string.generation_notify_hint), color = colors.ink3, fontFamily = AppFonts.mono, fontSize = 11.sp)
    }
}

@Composable
private fun lastText(last: LastRun?): String {
    if (last == null) return stringResource(R.string.generation_last_none)
    val at = runCatching { whenText(OffsetDateTime.parse(last.at).toZonedDateTime()) }.getOrDefault(last.at)
    return when (last.outcome) {
        LastOutcome.MADE -> stringResource(R.string.generation_last_made, at)
        LastOutcome.NOTHING -> stringResource(R.string.generation_last_nothing, at)
        LastOutcome.MISSING_SETUP -> stringResource(R.string.generation_last_missing, at)
        LastOutcome.FAILED -> stringResource(R.string.generation_last_failed, at, last.detail)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = LocalReaderColors.current.ink3, fontFamily = AppFonts.mono, fontSize = 12.sp)
}

@Composable
private fun Option(label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colors = LocalReaderColors.current
    Row(
        Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (on) colors.accent else colors.page, RoundedCornerShape(10.dp))
            .border(1.dp, if (on) colors.accent else colors.rule, RoundedCornerShape(10.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, color = if (on) colors.accentInk else colors.ink, fontFamily = AppFonts.mono, fontSize = 14.sp)
    }
}
