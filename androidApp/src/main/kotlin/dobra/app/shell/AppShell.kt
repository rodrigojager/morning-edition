package dobra.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.edition.paged.ReaderScreen
import dobra.app.library.LibraryScreen
import dobra.app.saved.SavedScreen
import dobra.app.settings.SettingsScreen
import dobra.app.sources.SourcesScreen
import dobra.model.Edition
import dobra.model.Magazine
import dobra.store.DeviceSettings
import dobra.store.Store

/** The magazine and the edition the Edição tab shows, plus the way back to the previous edition when there is one. */
class ShellData(
    val magazine: Magazine,
    val edition: Edition,
    val onPreviousEdition: (() -> Unit)? = null,
)

/**
 * The frame of the app: the page painted in the reader's theme, the current tab and the bottom bar with the five tabs.
 * Stateless about data: the caller hands in what to show and receives settings changes.
 */
@Composable
fun AppShell(
    data: ShellData?,
    settings: DeviceSettings,
    onSettings: (DeviceSettings) -> Unit,
    initialTab: String = Routes.EDITION,
    store: Store? = null,
    onOpenEdition: (String) -> Unit = {},
    ai: dobra.app.ai.AiSetup? = null,
) {
    AppTheme(settings.theme, settings.textSize.toFloat()) {
        val colors = LocalReaderColors.current
        var tab by rememberSaveable { mutableStateOf(initialTab) }
        val holder = rememberSaveableStateHolder()
        dobra.app.ask.AskHost(ai) {
        Column(
            Modifier
                .fillMaxSize()
                .testTag("app-root")
                .semantics { pageBackground = colors.bg }
                .background(colors.bg),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                holder.SaveableStateProvider(tab) {
                    when (tab) {
                        Routes.EDITION -> if (data == null) LoadingContent() else ReaderScreen(
                            magazine = data.magazine,
                            edition = data.edition,
                            mode = settings.readingMode,
                            onMode = { onSettings(settings.copy(readingMode = it)) },
                            onBackToLibrary = { tab = Routes.LIBRARY },
                            onPreviousEdition = data.onPreviousEdition,
                            marks = store?.marks,
                        )
                        Routes.LIBRARY -> LibraryScreen(
                            store = store,
                            onOpenEdition = { key ->
                                onOpenEdition(key)
                                tab = Routes.EDITION
                            },
                        )
                        Routes.SAVED -> SavedScreen(store)
                        Routes.SOURCES -> SourcesScreen()
                        Routes.SETTINGS -> SettingsScreen(settings, onSettings, ai)
                    }
                }
            }
            val savedCount by remember(store) { store?.marks?.observeSaved()?.map { it.size } ?: flowOf(0) }
                .collectAsState(initial = 0)
            TabBar(selected = tab, onSelect = { tab = it }, savedCount = savedCount)
        }
        }
    }
}

@Composable
private fun tabLabel(route: String): String = stringResource(
    when (route) {
        Routes.EDITION -> R.string.tab_edition
        Routes.LIBRARY -> R.string.tab_library
        Routes.SAVED -> R.string.tab_saved
        Routes.SOURCES -> R.string.tab_sources
        else -> R.string.tab_settings
    },
)

@Composable
private fun TabBar(selected: String, onSelect: (String) -> Unit, savedCount: Int = 0) {
    val colors = LocalReaderColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.page)
            .drawBehind {
                drawLine(colors.rule, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .navigationBarsPadding()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Routes.tabs.forEach { route ->
            val on = route == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("tab-$route")
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(route) })
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = tabLabel(route),
                        color = if (on) colors.accent else colors.ink3,
                        fontFamily = AppFonts.mono,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    if (route == Routes.SAVED && savedCount > 0) {
                        Text(
                            text = savedCount.toString(),
                            color = colors.accent,
                            fontFamily = AppFonts.mono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier.testTag("tab-count-saved"),
                        )
                    }
                }
            }
        }
    }
}

/** What the screen shows while the store opens. */
@Composable
fun LoadingPage() {
    Box(
        Modifier.fillMaxSize().background(ReaderPalettes.paper.bg).testTag("loading"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.app_name),
            color = ReaderPalettes.paper.ink3,
            fontFamily = AppFonts.display,
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun LoadingContent() {
    val colors = LocalReaderColors.current
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.loading),
            color = colors.ink3,
            fontFamily = AppFonts.mono,
            fontSize = 12.sp,
        )
    }
}
