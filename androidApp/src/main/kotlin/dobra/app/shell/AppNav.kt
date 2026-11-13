package dobra.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dobra.model.Edition
import dobra.model.Magazine
import dobra.store.EditionSummary
import dobra.store.Store
import kotlinx.coroutines.launch

/** Route names of the app: the five tabs. Each feature adds its routes here. */
object Routes {
    const val EDITION = "edition"
    const val LIBRARY = "library"
    const val SAVED = "saved"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"

    val tabs = listOf(EDITION, LIBRARY, SAVED, SOURCES, SETTINGS)
}

/** Connects the local store to the shell: opens the store, then follows magazine, edition and settings. */
@Composable
fun AppNav(container: AppContainer) {
    val store by produceState<Store?>(null, container) { value = container.store.await() }
    val s = store
    if (s == null) {
        LoadingPage()
        return
    }
    val scope = rememberCoroutineScope()
    val settings by s.settings.observe().collectAsState(initial = null)
    val magazines by s.magazines.observeAll().collectAsState(initial = null)
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val magazine = magazines?.let { list -> list.firstOrNull { it.id == openKey?.substringBefore('/') } ?: list.firstOrNull() }
    val current = settings
    if (current == null) {
        LoadingPage()
        return
    }
    AppShell(
        data = magazine?.let { m -> EditionSource(s, m, openKey?.takeIf { it.startsWith(m.id + "/") }, { openKey = null }) },
        settings = current,
        onSettings = { scope.launch { s.settings.set(it) } },
        store = s,
        onOpenEdition = { openKey = it },
        ai = container.ai,
        collector = container.collector,
        maker = container.maker,
    )
}

/** Picks the edition to show for a magazine (newest first, or the one the reader moved back to). */
@Composable
private fun EditionSource(store: Store, magazine: Magazine, opened: String?, onChosen: () -> Unit): ShellData? {
    var older by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = opened ?: older
    val summaries: List<EditionSummary> by remember(magazine.id) { store.editions.observe(magazine.id) }
        .collectAsState(initial = emptyList())
    val key = chosen ?: summaries.firstOrNull()?.key ?: return null
    val edition: Edition? by produceState<Edition?>(null, key) { value = store.editions.get(key) }
    val loaded = edition ?: return null
    val position = summaries.indexOfFirst { it.key == key }
    val previous = summaries.getOrNull(position + 1)
    return ShellData(magazine, loaded, previous?.let { p -> { older = p.key; onChosen() } })
}
