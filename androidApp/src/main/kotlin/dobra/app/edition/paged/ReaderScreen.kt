package dobra.app.edition.paged

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dobra.app.R
import dobra.app.edition.EditionTab
import dobra.app.edition.LocalLooks
import dobra.app.edition.chapterIdOf
import dobra.app.edition.pagesOf
import dobra.app.shell.chrome.ChromeFrame
import dobra.app.shell.chrome.ChromeState
import dobra.app.shell.chrome.rememberChromeState
import dobra.app.turn.TurnKind
import dobra.app.turn.rememberSheetTurn
import dobra.model.Edition
import dobra.model.Item
import dobra.model.Magazine
import dobra.reader.Pagination
import dobra.reader.ReadingPosition
import dobra.store.Controls
import dobra.store.Keys
import dobra.store.MarksRepository
import dobra.store.ReadingMode
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The width from which the book shows two pages side by side. */
private val BookMinWidth = 640.dp

/** The width under which the single sheet counts pages as `12/108`. */
private val ShortCountWidth = 480.dp

/** The modes a width allows, in the order the pager chip walks through them. */
internal fun modesFor(wide: Boolean): List<ReadingMode> =
    if (wide) listOf(ReadingMode.BOOK, ReadingMode.SHEET, ReadingMode.SCROLL) else listOf(ReadingMode.SHEET, ReadingMode.SCROLL)

/** The mode shown for the stored [mode] at this width: a narrow screen has no two-page book. */
internal fun shownMode(mode: ReadingMode, wide: Boolean): ReadingMode =
    if (mode == ReadingMode.BOOK && !wide) ReadingMode.SHEET else mode

private val positionSaver = listSaver<ReadingPosition, String>(
    save = { listOf(it.chapter, it.fraction.toString()) },
    restore = { ReadingPosition(it[0], it[1].toDouble()) },
)

/** True when the system setting that removes animations is on. */
@Composable
private fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The edition in the reading mode the reader chose: the book (two pages and the spine, from 640 dp wide; on a foldable
 * the spine sits on the device's fold), the single sheet or the scroll, with the pager under it. The place is a chapter and how far into it, so it is kept when the
 * mode, the width or the text size changes. [onMode] stores a new choice of the pager chip.
 */
@Composable
fun ReaderScreen(
    magazine: Magazine,
    edition: Edition,
    mode: ReadingMode,
    onMode: (ReadingMode) -> Unit,
    onBackToLibrary: () -> Unit,
    onPreviousEdition: (() -> Unit)? = null,
    marks: MarksRepository? = null,
    modifier: Modifier = Modifier,
    controls: Controls = Controls.PINNED,
    onControls: (Controls) -> Unit = {},
    chrome: ChromeState = rememberChromeState(controls),
) {
    val bars = Bars(chrome, magazine.name, controls == Controls.PINNED) {
        onControls(if (controls == Controls.PINNED) Controls.HIDDEN else Controls.PINNED)
    }
    var position by rememberSaveable(edition.key, stateSaver = positionSaver) { mutableStateOf(ReadingPosition("cover")) }
    var lastPaged by rememberSaveable { mutableStateOf(if (mode == ReadingMode.SCROLL) ReadingMode.BOOK else mode) }
    if (mode != ReadingMode.SCROLL && mode != lastPaged) lastPaged = mode
    val instant = animationsOff()

    LaunchedEffect(position.chapter, marks) {
        val id = position.chapter.removePrefix("story-")
        if (id != position.chapter) marks?.setStoryRead(Keys.item(edition.key, id))
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("reader")) {
        val wide = maxWidth >= BookMinWidth
        val shown = shownMode(mode, wide)
        val cycle = {
            val order = modesFor(wide)
            val next = order[(order.indexOf(shown) + 1) % order.size]
            onMode(if (!wide && next == ReadingMode.SHEET) lastPaged else next)
        }
        CompositionLocalProvider(LocalLooks provides magazine.looks) {
            if (shown == ReadingMode.SCROLL) {
                ScrollReader(
                    magazine, edition, position, { position = it }, instant, cycle, onBackToLibrary, onPreviousEdition, marks, bars,
                )
            } else {
                PagedReader(
                    magazine, edition, shown, narrowCount = maxWidth < ShortCountWidth, position = position,
                    onPosition = { position = it }, instant = instant, onCycle = cycle,
                    onBackToLibrary = onBackToLibrary, onPreviousEdition = onPreviousEdition, marks = marks, bars = bars,
                )
            }
        }
    }
}

@Composable
private fun PagedReader(
    magazine: Magazine,
    edition: Edition,
    mode: ReadingMode,
    narrowCount: Boolean,
    position: ReadingPosition,
    onPosition: (ReadingPosition) -> Unit,
    instant: Boolean,
    onCycle: () -> Unit,
    onBackToLibrary: () -> Unit,
    onPreviousEdition: (() -> Unit)?,
    marks: MarksRepository?,
    bars: Bars,
) {
    val chrome = bars.chrome
    val scope = rememberCoroutineScope()
    val savedKeys by remember(marks) {
        marks?.observeSaved()?.map { list -> list.map { it.key }.toSet() } ?: flowOf(emptySet())
    }.collectAsState(initial = emptySet())
    val savedState = rememberUpdatedState(savedKeys)
    val currentPosition by rememberUpdatedState(position)
    val setPosition by rememberUpdatedState(onPosition)
    val back by rememberUpdatedState(onBackToLibrary)
    val previous by rememberUpdatedState(onPreviousEdition)

    var measured by remember { mutableStateOf<Measured?>(null) }
    var layoutVersion by remember { mutableIntStateOf(0) }
    var screen by remember { mutableIntStateOf(0) }
    val perScreen = if (mode == ReadingMode.BOOK) 2 else 1

    // Opening a chapter from the contents goes through this holder, filled in once the pages exist.
    val goChapter = remember { arrayOfNulls<(String) -> Unit>(1) }
    val chapters = remember(magazine, edition, marks) {
        chaptersOf(
            magazine, edition,
            FlowActions(
                marks = marks,
                isSaved = { savedState.value.contains(it) },
                onSave = { item: Item ->
                    val key = Keys.item(edition.key, item.id)
                    scope.launch { if (key in savedState.value) marks?.unsave(key) else marks?.save(edition.key, item) }
                },
                onOpenChapter = { id -> goChapter[0]?.invoke(id) },
                onBackToLibrary = { back() },
                onPreviousEdition = onPreviousEdition?.let { { previous?.invoke() } },
            ),
        )
    }

    val pagination = remember(measured) { measured?.let { Pagination.paginate(it.flow, it.pageHeight.toFloat()) } }
    LaunchedEffect(pagination, perScreen) {
        if (pagination != null) {
            layoutVersion++
            screen = pagination.screenOf(currentPosition, perScreen)
        }
    }
    // One screen back or forward; the position follows the reader.
    val step: (Int) -> Unit = { dir ->
        pagination?.let { p ->
            val to = (screen + dir).coerceIn(0, p.screenCount(perScreen) - 1)
            if (to != screen) {
                screen = to
                setPosition(p.positionOfScreen(to, perScreen))
            }
        }
    }
    // Pages turn in 3D where the device can draw it: the single sheet rolls off, the book's page swings over the
    // spine. While a page is turning the buttons wait.
    val turn = rememberSheetTurn<ScreenKey>(enabled = !instant, kind = if (perScreen == 2) TurnKind.BOOK else TurnKind.SHEET)
    // On a foldable the book's spine sits on the device's fold.
    val fold = if (perScreen == 2) rememberVerticalFold() else null
    var areaLeft by remember { mutableIntStateOf(0) }
    // The pager's buttons bring the controls back and restart their countdown; a swipe sends them away.
    val pagerStep: (Int) -> Unit = { dir -> if (turn?.busy != true) { chrome.show(); step(dir) } }
    val swipeStep: (Int) -> Unit = { dir -> chrome.hide(); step(dir) }
    goChapter[0] = { id ->
        pagination?.let { p ->
            val c = p.chapters.indexOf(id)
            if (c >= 0) {
                screen = p.startOf(c) / perScreen
                setPosition(ReadingPosition(id, 0.0))
            }
        }
    }

    val count = pagedCount(pagination, perScreen, screen, mode, narrowCount)
    ChromeFrame(
        chrome = chrome, title = bars.title, handleText = count,
        bottom = {
            PagedPagerBar(
                pagination = pagination, perScreen = perScreen, screen = screen, mode = mode, count = count,
                chapters = chapters, position = position, onStep = pagerStep,
                onCycle = { chrome.touch(); onCycle() }, pinned = bars.pinned, onPin = bars.onPin,
            )
        },
        contentModifier = Modifier.statusBarsPadding(),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().onGloballyPositioned { areaLeft = it.positionInWindow().x.roundToInt() },
        ) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val padH = with(density) { PageHorizontalPadding.roundToPx() }
            val padV = with(density) { PageVerticalPadding.roundToPx() }
            val spinePx = with(density) { SpineGutter.roundToPx() }
            val frame = if (perScreen == 2) {
                BookFrame.book(widthPx, spinePx, fold?.let { (it.first - areaLeft)..(it.last - areaLeft) })
            } else {
                BookFrame.single(widthPx)
            }
            val measureWidth = (frame.pageWidth - 2 * padH).coerceAtLeast(1)
            val pageHeight = (heightPx - 2 * padV).coerceAtLeast(1)

            MeasureLayer(chapters, measureWidth, pageHeight) { m -> if (m != measured) measured = m }

            val fresh = measured?.let { it.width == measureWidth && it.pageHeight == pageHeight } == true
            if (pagination != null && fresh) {
                PagedView(
                    chapters, pagination, perScreen, frame, ScreenKey(layoutVersion, screen), instant, turn, onStep = swipeStep,
                )
            }
        }
    }
}

@Composable
private fun pagedCount(pagination: Pagination?, perScreen: Int, screen: Int, mode: ReadingMode, narrowCount: Boolean): String {
    val screens = pagination?.screenCount(perScreen) ?: 1
    return when {
        pagination == null -> ""
        mode == ReadingMode.BOOK -> stringResource(R.string.count_book, screen * 2 + 1, screen * 2 + 2, screens * 2)
        narrowCount -> stringResource(R.string.count_short, screen + 1, screens)
        else -> stringResource(R.string.count_sheet, screen + 1, screens)
    }
}

/** What the reader needs of the controls: their state, the top bar's title and the pin button. */
private class Bars(val chrome: ChromeState, val title: String, val pinned: Boolean, val onPin: () -> Unit)

@Composable
private fun PagedPagerBar(
    pagination: Pagination?,
    perScreen: Int,
    screen: Int,
    mode: ReadingMode,
    count: String,
    chapters: List<ChapterSpec>,
    position: ReadingPosition,
    onStep: (Int) -> Unit,
    onCycle: () -> Unit,
    pinned: Boolean,
    onPin: () -> Unit,
) {
    val screens = pagination?.screenCount(perScreen) ?: 1
    PagerBar(
        label = chapterLabel(chapters.firstOrNull { it.id == position.chapter } ?: chapters.first()),
        count = count,
        progress = if (screens > 1) screen.toFloat() / (screens - 1) else 1f,
        mode = mode,
        atStart = screen <= 0,
        atEnd = screen >= screens - 1,
        scroll = false,
        onStep = onStep,
        onCycle = onCycle,
        pinned = pinned,
        onPin = onPin,
    )
}

@Composable
private fun chapterLabel(chapter: ChapterSpec): String = when (chapter.id) {
    "cover" -> stringResource(R.string.chapter_cover)
    "toc" -> stringResource(R.string.chapter_toc)
    "notes" -> stringResource(R.string.notes_title)
    "backstage" -> stringResource(R.string.backstage_title)
    else -> chapter.story?.shortLabel().orEmpty()
}

/**
 * The scroll mode: the edition as one column, opened at the kept position. The position follows the scroll once the
 * reader moves it; the previous and next buttons jump between chapters and the counter is a percentage.
 */
@Composable
private fun ScrollReader(
    magazine: Magazine,
    edition: Edition,
    position: ReadingPosition,
    onPosition: (ReadingPosition) -> Unit,
    instant: Boolean,
    onCycle: () -> Unit,
    onBackToLibrary: () -> Unit,
    onPreviousEdition: (() -> Unit)?,
    marks: MarksRepository?,
    bars: Bars,
) {
    val chrome = bars.chrome
    val jumping = remember { BooleanArray(1) }
    val pages = remember(edition) { pagesOf(edition) }
    val ids = remember(pages) { pages.map(::chapterIdOf) }
    val chapterIds = remember(ids) { ids.distinct() }
    val start = remember { position }
    val setPosition by rememberUpdatedState(onPosition)
    val scope = rememberCoroutineScope()

    fun range(id: String): IntRange = ids.indexOf(id).let { a -> if (a < 0) 0..0 else a..ids.lastIndexOf(id) }
    val opening = remember {
        val r = range(start.chapter)
        val exact = r.first + start.fraction * (r.last - r.first + 1)
        exact
    }
    val listState = key(edition.key) { rememberLazyListState(initialFirstVisibleItemIndex = opening.toInt()) }

    LaunchedEffect(listState) {
        // A chapter drawn as one long item: move into it by the kept share of its height once it is laid out.
        val within = opening - opening.toInt()
        if (within > 0.0) {
            val height = snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == opening.toInt() }?.size
            }.filterNotNull().first()
            listState.scrollToItem(opening.toInt(), (within * height).roundToInt())
        }
        // From here on the position follows the reader's scroll, and the controls follow its direction.
        var last = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { (index, offset) ->
                if (!jumping[0] && chrome.auto) {
                    val down = index > last.first || (index == last.first && offset > last.second)
                    val up = index < last.first || (index == last.first && offset < last.second)
                    if (down) chrome.hide() else if (up) chrome.show()
                }
                last = index to offset
                val id = ids.getOrElse(index) { ids.last() }
                val r = range(id)
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 1
                val share = ((index - r.first) + offset.toDouble() / size.coerceAtLeast(1)) / (r.last - r.first + 1)
                setPosition(ReadingPosition(id, share.coerceIn(0.0, 0.999)))
            }
    }

    val index = listState.firstVisibleItemIndex
    val currentId = ids.getOrElse(index) { ids.first() }
    val c = chapterIds.indexOf(currentId)
    val total = (pages.size - 1).coerceAtLeast(1)
    val progress = (index.toFloat() / total).coerceIn(0f, 1f)
    val atEnd = !listState.canScrollForward
    val count = stringResource(R.string.count_scroll, if (atEnd) 100 else (progress * 100).roundToInt())
    ChromeFrame(
        chrome = chrome, title = bars.title, handleText = count,
        bottom = {
            PagerBar(
                label = scrollLabel(currentId, edition),
                count = count,
                progress = if (atEnd) 1f else progress,
                mode = ReadingMode.SCROLL,
                atStart = index == 0 && listState.firstVisibleItemScrollOffset == 0,
                atEnd = c >= chapterIds.size - 1,
                scroll = true,
                onStep = { dir ->
                    chrome.show()
                    val here = range(currentId).first
                    val target = if (dir < 0 && (index > here || listState.firstVisibleItemScrollOffset > 0)) currentId
                    else chapterIds.getOrNull(c + dir)
                    if (target != null) {
                        val to = range(target).first
                        scope.launch {
                            jumping[0] = true
                            try {
                                if (instant) listState.scrollToItem(to) else listState.animateScrollToItem(to)
                            } finally {
                                jumping[0] = false
                            }
                        }
                    }
                },
                onCycle = { chrome.touch(); onCycle() },
                pinned = bars.pinned,
                onPin = bars.onPin,
            )
        },
    ) {
        EditionTab(magazine, edition, onBackToLibrary, onPreviousEdition, marks, listState)
    }
}

@Composable
private fun scrollLabel(id: String, edition: Edition): String = when (id) {
    "cover" -> stringResource(R.string.chapter_cover)
    "toc" -> stringResource(R.string.chapter_toc)
    "notes" -> stringResource(R.string.notes_title)
    "backstage" -> stringResource(R.string.backstage_title)
    else -> edition.items.firstOrNull { "story-${it.id}" == id }?.shortLabel().orEmpty()
}
