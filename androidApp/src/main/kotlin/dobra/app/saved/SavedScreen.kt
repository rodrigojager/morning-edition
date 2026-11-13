package dobra.app.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.edition.BlockView
import dobra.app.edition.ItemStoryActions
import dobra.app.edition.LocalLooks
import dobra.app.edition.LocalStoryNumber
import dobra.app.edition.NoteSave
import dobra.app.edition.headOf
import dobra.app.edition.shownBlocks
import dobra.app.edition.text
import dobra.app.edition.title
import dobra.app.library.Body
import dobra.app.library.Btn
import dobra.app.library.BtnRow
import dobra.app.library.Card
import dobra.app.library.Empty
import dobra.app.library.Headline
import dobra.app.library.Kicker
import dobra.app.library.LibraryPage
import dobra.app.library.Meta
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.PlaceholderScreen
import dobra.model.Format
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.Magazine
import dobra.store.SavedItem
import dobra.store.Store
import kotlinx.coroutines.launch

/** The edition a saved item came from: its key is `<magazine>/<date>/<item>`. */
internal fun SavedItem.editionKey(): String = key.substringBeforeLast('/')
internal fun SavedItem.magazineId(): String = key.substringBefore('/')
internal fun SavedItem.date(): String = editionKey().substringAfter('/')

/** Saved items grouped by magazine, then by edition; newest edition first, magazines by name. */
internal class SavedGroup(val magazineId: String, val editions: List<Pair<String, List<SavedItem>>>)

internal fun groupSaved(saved: List<SavedItem>): List<SavedGroup> =
    saved.groupBy { it.magazineId() }.map { (mag, items) ->
        SavedGroup(
            mag,
            items.groupBy { it.editionKey() }.toList().sortedByDescending { (k, _) -> k.substringAfter('/') },
        )
    }

/**
 * The Salvos tab: what the reader saved, by magazine and edition, and the reading of one edition with only its saved
 * items. Saved items keep their own copy, so they stay even when the edition or the magazine is deleted.
 */
@Composable
fun SavedScreen(store: Store? = null) {
    if (store == null) {
        PlaceholderScreen(stringResource(R.string.saved_placeholder), "screen-saved")
        return
    }
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    val saved by remember(store) { store.marks.observeSaved() }.collectAsState(initial = emptyList())
    val magazines by remember(store) { store.magazines.observeAll() }.collectAsState(initial = emptyList())
    val open = reading
    if (open == null) {
        SavedList(store, saved, magazines) { reading = it }
    } else {
        val items = saved.filter { it.editionKey() == open }
        if (items.isEmpty()) LaunchedEffect(Unit) { reading = null }
        else SavedOnlyReader(store, open, items, magazines.firstOrNull { it.id == items.first().magazineId() }) { reading = null }
    }
}

@Composable
private fun SavedList(store: Store, saved: List<SavedItem>, magazines: List<Magazine>, onRead: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    LibraryPage("screen-saved") {
        Kicker(stringResource(R.string.saved_kicker))
        Headline(stringResource(R.string.saved_title))
        Body(stringResource(R.string.saved_intro))
        if (saved.isEmpty()) Empty(stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body), "saved-empty")
        groupSaved(saved).forEach { group ->
            val name = magazines.firstOrNull { it.id == group.magazineId }?.name ?: group.magazineId
            Text(
                name, color = LocalReaderColors.current.ink, fontFamily = AppFonts.display, fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp).testTag("saved-mag-${group.magazineId}"),
            )
            group.editions.forEach { (editionKey, items) ->
                Card("saved-edition-$editionKey") {
                    Meta(stringResource(R.string.saved_edition_date, editionKey.substringAfter('/')), "saved-date-$editionKey")
                    items.forEach { SavedRow(it) { scope.launch { store.marks.unsave(it.key) } } }
                    BtnRow { Btn(stringResource(R.string.saved_read_only), "saved-read-$editionKey", primary = true) { onRead(editionKey) } }
                }
            }
        }
    }
}

@Composable
private fun SavedRow(saved: SavedItem, onRemove: () -> Unit) {
    val colors = LocalReaderColors.current
    val isNote = saved.item.kind == ItemKind.NOTE
    Column(Modifier.fillMaxWidth().testTag("saved-item-${saved.key}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Meta(stringResource(if (isNote) R.string.saved_kind_note else R.string.saved_kind_story).uppercase())
        Text(
            saved.item.label(), color = colors.ink, fontFamily = AppFonts.text, fontSize = 17.sp,
            modifier = Modifier.testTag("saved-title-${saved.key}"),
        )
        BtnRow { Btn(stringResource(R.string.saved_remove), "saved-remove-${saved.key}", onClick = onRemove) }
    }
}

/** The title of a story, or the first words of a short note. */
@Composable
private fun Item.label(): String {
    title().takeIf { it.isNotBlank() }?.let { return it }
    val text = blocks.firstNotNullOfOrNull { it.text("text")?.takeIf { t -> t.isNotBlank() } }
    return text?.take(90) ?: stringResource(R.string.saved_untitled)
}

/**
 * One edition read with only its saved items: the stories, then a chapter "Notas salvas". No cover, contents or
 * backstage. Un-saving an item takes it out of this reading at once.
 */
@Composable
private fun SavedOnlyReader(store: Store, editionKey: String, items: List<SavedItem>, magazine: Magazine?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = LocalReaderColors.current
    val format = magazine?.format ?: Format()
    val stories = items.filter { it.item.kind == ItemKind.STORY }
    val notes = items.filter { it.item.kind == ItemKind.NOTE }
    CompositionLocalProvider(LocalLooks provides (magazine?.looks ?: emptyMap())) {
        LazyColumn(Modifier.fillMaxWidth().statusBarsPadding().testTag("saved-reader")) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kicker(stringResource(R.string.saved_only_kicker, magazine?.name ?: editionKey.substringBefore('/'), editionKey.substringAfter('/')))
                    BtnRow { Btn(stringResource(R.string.saved_back), "saved-back", onClick = onBack) }
                }
            }
            items(stories.size, key = { "s-" + stories[it].key }) { i ->
                val s = stories[i]
                val blocks = shownBlocks(s.item.blocks, format)
                val head = headOf(blocks)
                CompositionLocalProvider(LocalStoryNumber provides (i + 1)) {
                    Column(
                        Modifier.fillMaxWidth().testTag("saved-story-${s.item.id}").background(colors.page)
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        head.forEach { BlockView(it) }
                        ItemStoryActions(s.item, s.key, true, store.marks) { scope.launch { store.marks.unsave(s.key) } }
                        blocks.drop(head.size).forEach { BlockView(it) }
                    }
                }
            }
            if (notes.isNotEmpty()) {
                item(key = "notes-header") {
                    Text(
                        stringResource(R.string.saved_notes_title), color = colors.ink, fontFamily = AppFonts.display,
                        fontWeight = FontWeight(650), fontSize = 26.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp).testTag("saved-notes-header"),
                    )
                }
                items(notes.size, key = { "n-" + notes[it].key }) { i ->
                    val n = notes[i]
                    Column(
                        Modifier.fillMaxWidth().testTag("saved-note-${n.item.id}").background(colors.page)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        shownBlocks(n.item.blocks, format).forEach { BlockView(it) }
                        NoteSave(n.item.id, true) { scope.launch { store.marks.unsave(n.key) } }
                    }
                }
            }
        }
    }
}
