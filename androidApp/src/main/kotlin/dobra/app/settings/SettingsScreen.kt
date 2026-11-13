package dobra.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.ai.AiProvidersScreen
import dobra.app.ai.AiSetup
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.PlaceholderScreen
import dobra.app.work.Generation
import dobra.app.work.GenerationScreen
import dobra.model.Magazine
import dobra.model.Topic
import dobra.store.Controls
import dobra.store.DeviceSettings
import dobra.store.ReadingMode
import dobra.store.Theme
import kotlin.math.abs

/** The four text sizes, as scale factors: small, standard, large and very large. */
internal val TextSizes = listOf(0.9, 1.0, 1.15, 1.3)

/** The weight a switched-off interest keeps. */
private const val OFF_WEIGHT = 0.2

private enum class Sub { NONE, AI, GENERATION }

/**
 * Device settings: theme, reading mode, controls, text size and the interests of the magazine, then the rows that open
 * the AI providers and the generation of editions. Every choice goes out through [onChange] (or [onTopics]) to be kept.
 */
@Composable
fun SettingsScreen(
    settings: DeviceSettings,
    onChange: (DeviceSettings) -> Unit,
    ai: AiSetup? = null,
    topics: List<Topic> = emptyList(),
    onTopics: ((List<Topic>) -> Unit)? = null,
    generation: Generation? = null,
    magazines: List<Magazine> = emptyList(),
) {
    val colors = LocalReaderColors.current
    var sub by rememberSaveable { mutableStateOf(Sub.NONE) }
    when (sub) {
        Sub.AI -> {
            BackHandler { sub = Sub.NONE }
            if (ai != null) {
                AiProvidersScreen(ai, onBack = { sub = Sub.NONE })
            } else {
                PlaceholderScreen(stringResource(R.string.ai_placeholder), "screen-ai-placeholder")
            }
            return
        }
        Sub.GENERATION -> {
            BackHandler { sub = Sub.NONE }
            if (generation != null) {
                GenerationScreen(generation, magazines, onBack = { sub = Sub.NONE })
            } else {
                PlaceholderScreen(stringResource(R.string.generation_placeholder), "screen-generation")
            }
            return
        }
        Sub.NONE -> Unit
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            color = colors.ink,
            fontFamily = AppFonts.display,
            fontSize = 28.sp,
        )
        Heading(R.string.settings_theme)
        Choices {
            Theme.entries.forEach { theme ->
                Choice(
                    label = stringResource(themeLabel(theme)), on = theme == settings.theme,
                    tag = "theme-${theme.name.lowercase()}",
                ) { onChange(settings.copy(theme = theme)) }
            }
        }
        Heading(R.string.settings_mode)
        Choices {
            ReadingMode.entries.forEach { mode ->
                Choice(
                    label = stringResource(modeLabel(mode)), on = mode == settings.readingMode,
                    tag = "mode-${mode.name.lowercase()}",
                ) { onChange(settings.copy(readingMode = mode)) }
            }
        }
        Heading(R.string.settings_controls)
        Choices {
            Controls.entries.forEach { controls ->
                Choice(
                    label = stringResource(controlsLabel(controls)), on = controls == settings.controls,
                    tag = "controls-${controls.name.lowercase()}",
                ) { onChange(settings.copy(controls = controls)) }
            }
        }
        Heading(R.string.settings_text_size)
        Choices {
            val current = TextSizes.indices.minBy { abs(TextSizes[it] - settings.textSize) }
            TextSizes.forEachIndexed { i, size ->
                Choice(
                    label = stringResource(textSizeLabel(i)), on = i == current, tag = "text-size-$i",
                ) { onChange(settings.copy(textSize = size)) }
            }
        }
        if (topics.isNotEmpty() && onTopics != null) {
            Heading(R.string.settings_interests)
            Choices {
                topics.forEachIndexed { i, topic ->
                    val on = topic.weight > OFF_WEIGHT
                    Choice(label = topic.name, on = on, tag = "interest-$i", role = Role.Checkbox) {
                        onTopics(topics.mapIndexed { j, t -> if (j == i) t.copy(weight = if (on) OFF_WEIGHT else 1.0) else t })
                    }
                }
            }
            Text(
                stringResource(R.string.settings_interests_hint),
                color = colors.ink3,
                fontFamily = AppFonts.mono,
                fontSize = 11.sp,
            )
        }
        RouteRow(stringResource(R.string.ai_route), "settings-ai") { sub = Sub.AI }
        RouteRow(stringResource(R.string.settings_generation), "settings-generation") { sub = Sub.GENERATION }
    }
}

@Composable
private fun Heading(text: Int) {
    Text(
        stringResource(text),
        color = LocalReaderColors.current.ink3,
        fontFamily = AppFonts.mono,
        fontSize = 12.sp,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Choices(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

@Composable
private fun Choice(label: String, on: Boolean, tag: String, role: Role = Role.RadioButton, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    Text(
        text = label,
        color = if (on) colors.accentInk else colors.ink,
        fontFamily = AppFonts.mono,
        fontSize = 13.sp,
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 44.dp)
            .background(if (on) colors.accent else colors.page, RoundedCornerShape(999.dp))
            .border(1.dp, if (on) colors.accent else colors.rule, RoundedCornerShape(999.dp))
            .selectable(selected = on, role = role, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun RouteRow(text: String, tag: String, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    Text(
        text = text,
        color = colors.ink,
        fontFamily = AppFonts.mono,
        fontSize = 14.sp,
        modifier = Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

private fun themeLabel(theme: Theme): Int = when (theme) {
    Theme.PAPER -> R.string.theme_paper
    Theme.SEPIA -> R.string.theme_sepia
    Theme.NIGHT -> R.string.theme_night
}

private fun modeLabel(mode: ReadingMode): Int = when (mode) {
    ReadingMode.BOOK -> R.string.settings_mode_book
    ReadingMode.SHEET -> R.string.settings_mode_sheet
    ReadingMode.SCROLL -> R.string.settings_mode_scroll
}

private fun controlsLabel(controls: Controls): Int = when (controls) {
    Controls.PINNED -> R.string.controls_pinned
    Controls.HIDDEN -> R.string.controls_hidden
}

private fun textSizeLabel(index: Int): Int = when (index) {
    0 -> R.string.text_size_small
    1 -> R.string.text_size_normal
    2 -> R.string.text_size_large
    else -> R.string.text_size_huge
}
