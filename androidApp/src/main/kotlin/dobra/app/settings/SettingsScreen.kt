package dobra.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dobra.app.ai.AiProvidersScreen
import dobra.app.ai.AiSetup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.store.DeviceSettings
import dobra.store.Theme

/** Device settings. For now: the reading theme (Papel, Sépia, Noite). */
@Composable
fun SettingsScreen(settings: DeviceSettings, onChange: (DeviceSettings) -> Unit, ai: AiSetup? = null) {
    val colors = LocalReaderColors.current
    var showAi by rememberSaveable { mutableStateOf(false) }
    if (ai != null && showAi) {
        BackHandler { showAi = false }
        AiProvidersScreen(ai, onBack = { showAi = false })
        return
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(20.dp).testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            color = colors.ink,
            fontFamily = AppFonts.display,
            fontSize = 28.sp,
        )
        Text(
            stringResource(R.string.settings_theme),
            color = colors.ink3,
            fontFamily = AppFonts.mono,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Theme.entries.forEach { theme ->
                val on = theme == settings.theme
                Text(
                    text = stringResource(themeLabel(theme)),
                    color = if (on) colors.accentInk else colors.ink,
                    fontFamily = AppFonts.mono,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .testTag("theme-${theme.name.lowercase()}")
                        .heightIn(min = 44.dp)
                        .background(if (on) colors.accent else colors.page, RoundedCornerShape(999.dp))
                        .border(1.dp, if (on) colors.accent else colors.rule, RoundedCornerShape(999.dp))
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onChange(settings.copy(theme = theme)) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (ai != null) {
            Text(
                text = stringResource(R.string.ai_route),
                color = colors.ink,
                fontFamily = AppFonts.mono,
                fontSize = 14.sp,
                modifier = Modifier
                    .testTag("settings-ai")
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { showAi = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

private fun themeLabel(theme: Theme): Int = when (theme) {
    Theme.PAPER -> R.string.theme_paper
    Theme.SEPIA -> R.string.theme_sepia
    Theme.NIGHT -> R.string.theme_night
}
