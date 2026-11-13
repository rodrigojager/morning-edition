package dobra.app.edition

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.LocalTextScale
import dobra.model.CollectionLine
import dobra.model.CollectionState
import dobra.model.Edition
import dobra.model.Format
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.LeftOut
import dobra.model.Magazine
import dobra.store.Feedback
import dobra.store.Keys
import dobra.store.MarksRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The pieces of the one continuous scroll, in order. */
internal sealed interface Page {
    val key: String

    data object Cover : Page { override val key = "cover" }
    data object Toc : Page { override val key = "toc" }
    data class Chapter(val item: Item) : Page { override val key = "chapter-${item.id}" }
    data object NotesHeader : Page { override val key = "notes-header" }
    data object Backstage : Page { override val key = "backstage" }
    data object End : Page { override val key = "end" }
}

internal fun pagesOf(edition: Edition): List<Page> = buildList {
    add(Page.Cover)
    add(Page.Toc)
    val stories = edition.items.filter { it.kind == ItemKind.STORY }
    val notes = edition.items.filter { it.kind == ItemKind.NOTE }
    stories.forEach { add(Page.Chapter(it)) }
    if (notes.isNotEmpty()) {
        add(Page.NotesHeader)
        notes.forEach { add(Page.Chapter(it)) }
    }
    add(Page.Backstage)
    add(Page.End)
}

internal fun Item.title(): String = blocks.firstOrNull { it.type == "title" }?.text("text").orEmpty()

internal fun Item.readMin(): Int? = blocks.firstOrNull { it.type == "title" }?.int("readMin")

/**
 * The edition as one continuous scroll: cover, contents, one chapter per story, the short notes, the backstage and
 * the end mark. The place in the scroll is saved with the screen's state and survives recreation.
 */
@Composable
fun EditionTab(
    magazine: Magazine,
    edition: Edition,
    onBackToLibrary: () -> Unit,
    onPreviousEdition: (() -> Unit)? = null,
    marks: MarksRepository? = null,
) {
    val pages = remember(edition) { pagesOf(edition) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val stories = remember(edition) { edition.items.filter { it.kind == ItemKind.STORY } }
    val savedKeys by remember(marks) {
        marks?.observeSaved()?.map { list -> list.map { it.key }.toSet() } ?: flowOf(emptySet())
    }.collectAsState(initial = emptySet())
    CompositionLocalProvider(LocalLooks provides magazine.looks) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().testTag("edition-scroll"),
            contentPadding = WindowInsets.statusBars.asPaddingValues(),
        ) {
            items(pages.size, key = { pages[it].key }) { index ->
                when (val page = pages[index]) {
                    Page.Cover -> CoverPage(magazine, edition, stories.firstOrNull())
                    Page.Toc -> TocPage(stories) { item ->
                        scope.launch { listState.scrollToItem(pages.indexOfFirst { it.key == "chapter-${item.id}" }) }
                    }
                    is Page.Chapter -> {
                        val itemKey = Keys.item(edition.key, page.item.id)
                        ChapterPage(
                            item = page.item,
                            number = stories.indexOfFirst { it.id == page.item.id } + 1,
                            format = magazine.format,
                            itemKey = itemKey,
                            saved = itemKey in savedKeys,
                            marks = marks,
                            onSave = {
                                scope.launch {
                                    if (itemKey in savedKeys) marks?.unsave(itemKey) else marks?.save(edition.key, page.item)
                                }
                            },
                        )
                    }
                    Page.NotesHeader -> NotesHeader()
                    Page.Backstage -> BackstagePage(edition.collection, edition.leftOut)
                    Page.End -> EndMark(onBackToLibrary, onPreviousEdition)
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.CoverPage(magazine: Magazine, edition: Edition, first: Item?) {
    val colors = LocalReaderColors.current
    val scale = LocalTextScale.current
    Column(
        Modifier
            .fillParentMaxHeight()
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("cover"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                magazine.name, color = colors.ink, fontFamily = AppFonts.display, fontWeight = FontWeight.Bold,
                fontSize = (42 * scale).sp, lineHeight = (44 * scale).sp,
            )
            Text(
                stringResource(R.string.edition_number_date, edition.number, edition.date),
                color = colors.ink3, fontFamily = AppFonts.mono, fontSize = (12 * scale).sp,
            )
        }
        if (first != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.cover_first_story).uppercase(), color = colors.accent,
                    fontFamily = AppFonts.mono, fontWeight = FontWeight.SemiBold, fontSize = (11.5f * scale).sp,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    first.title(), color = colors.ink, fontFamily = AppFonts.display, fontWeight = FontWeight(620),
                    fontSize = (28 * scale).sp, lineHeight = (31 * scale).sp, modifier = Modifier.testTag("cover-title"),
                )
            }
        }
    }
}

@Composable
private fun TocPage(stories: List<Item>, onOpen: (Item) -> Unit) {
    val colors = LocalReaderColors.current
    val scale = LocalTextScale.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp).testTag("toc")) {
        Text(
            stringResource(R.string.toc_title), color = colors.ink, fontFamily = AppFonts.display,
            fontWeight = FontWeight(650), fontSize = (26 * scale).sp,
        )
        Spacer(Modifier.height(12.dp))
        stories.forEachIndexed { i, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("toc-${item.id}")
                    .clickable { onOpen(item) }
                    .heightIn(min = 52.dp)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}".padStart(2, '0'), color = colors.accent, fontFamily = AppFonts.mono, fontSize = (13 * scale).sp)
                Text(
                    item.title(), color = colors.ink, fontFamily = AppFonts.text, fontSize = (17 * scale).sp,
                    lineHeight = (21 * scale).sp, modifier = Modifier.weight(1f),
                )
                item.readMin()?.let {
                    Text(
                        stringResource(R.string.toc_read_min, it), color = colors.ink3, fontFamily = AppFonts.mono,
                        fontSize = (12 * scale).sp, modifier = Modifier.testTag("toc-min-${item.id}"),
                    )
                }
            }
        }
    }
}

/**
 * One story or note: its blocks, one after the other, as the magazine's format lets them be shown. A story has its
 * buttons under the head (illustration, title, summary) and counts as read once it is opened; a note ends with Salvar.
 */
@Composable
private fun ChapterPage(
    item: Item,
    number: Int,
    format: Format,
    itemKey: String,
    saved: Boolean,
    marks: MarksRepository?,
    onSave: () -> Unit,
) {
    val colors = LocalReaderColors.current
    val scope = rememberCoroutineScope()
    val isStory = item.kind == ItemKind.STORY
    var feedback by remember(itemKey) { mutableStateOf<Feedback?>(null) }
    LaunchedEffect(itemKey, marks) {
        if (isStory) marks?.setStoryRead(itemKey)
        feedback = marks?.feedback(itemKey)
    }
    val blocks = remember(item, format) { shownBlocks(item.blocks, format) }
    val head = blocks.takeWhile { it.type in headTypes }
    val body = blocks.drop(head.size)
    CompositionLocalProvider(LocalStoryNumber provides (if (isStory) number else 0)) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("chapter-${item.id}")
                .background(colors.page)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            head.forEach { BlockView(it) }
            if (isStory) {
                StoryActions(
                    itemId = item.id,
                    saved = saved,
                    feedback = feedback,
                    onSave = onSave,
                    onFeedback = { choice ->
                        val next = if (feedback == choice) null else choice
                        feedback = next
                        scope.launch { marks?.setFeedback(itemKey, next) }
                    },
                )
            }
            body.forEach { BlockView(it) }
            if (!isStory) NoteSave(item.id, saved, onSave)
        }
    }
}

private val headTypes = setOf("illustration", "title", "summary")

@Composable
private fun NotesHeader() {
    val colors = LocalReaderColors.current
    Text(
        stringResource(R.string.notes_title), color = colors.ink, fontFamily = AppFonts.display,
        fontWeight = FontWeight(650), fontSize = (26 * LocalTextScale.current).sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp).testTag("notes-header"),
    )
}

@Composable
private fun BackstagePage(collection: List<CollectionLine>, leftOut: List<LeftOut>) {
    val colors = LocalReaderColors.current
    val scale = LocalTextScale.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp).testTag("backstage"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.backstage_title), color = colors.ink, fontFamily = AppFonts.display,
            fontWeight = FontWeight(650), fontSize = (26 * scale).sp,
        )
        if (collection.isNotEmpty()) {
            Label(stringResource(R.string.backstage_collection))
            collection.forEach { line ->
                val state = stringResource(
                    when (line.state) {
                        CollectionState.ANSWERED -> R.string.state_answered
                        CollectionState.PARTIAL -> R.string.state_partial
                        CollectionState.FAILED -> R.string.state_failed
                    },
                )
                Text(
                    "${line.source} · ${stringResource(R.string.collection_records, line.records)} · $state",
                    color = if (line.state == CollectionState.FAILED) colors.accent else colors.ink2,
                    fontFamily = AppFonts.text, fontSize = (15 * scale).sp,
                )
                line.note?.let { Text(it, color = colors.ink3, fontFamily = AppFonts.text, fontSize = (14 * scale).sp) }
            }
        }
        if (leftOut.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Label(stringResource(R.string.backstage_left_out))
            leftOut.forEach {
                Text(it.title, color = colors.ink, fontFamily = AppFonts.text, fontWeight = FontWeight.SemiBold, fontSize = (15 * scale).sp)
                Text(it.reason, color = colors.ink3, fontFamily = AppFonts.text, fontSize = (14 * scale).sp)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(), color = LocalReaderColors.current.ink3, fontFamily = AppFonts.mono,
        fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp,
    )
}

/** After the last chapter: the way back to the library, and to the previous edition when there is one. */
@Composable
private fun EndMark(onBackToLibrary: () -> Unit, onPreviousEdition: (() -> Unit)?) {
    val colors = LocalReaderColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp).testTag("end-mark"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("◆", color = colors.accent, fontSize = 18.sp)
        Text(
            stringResource(R.string.end_mark), color = colors.ink3, fontFamily = AppFonts.mono, fontSize = 12.sp,
            letterSpacing = 1.6.sp,
        )
        EndButton(stringResource(R.string.end_back_to_library), "end-library", onBackToLibrary)
        if (onPreviousEdition != null) {
            EndButton(stringResource(R.string.end_previous_edition), "end-previous", onPreviousEdition)
        }
    }
}

@Composable
private fun EndButton(label: String, tag: String, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    Text(
        label, color = colors.ink, fontFamily = AppFonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}
