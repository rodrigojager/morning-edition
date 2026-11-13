package dobra.app.edition.paged

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dobra.app.shell.LocalReaderColors
import dobra.app.turn.DragInput
import dobra.app.turn.PageTurnKindKey
import dobra.app.turn.PictureBox
import dobra.app.turn.SheetTurn
import dobra.app.turn.SheetTurnOverlay
import dobra.app.turn.TurnPage
import kotlinx.coroutines.launch
import dobra.reader.Page
import dobra.reader.Pagination
import dobra.reader.Slice
import kotlin.math.abs
import kotlin.math.roundToInt

/** The padding inside a page, around the area the pieces are laid out in. */
internal val PageHorizontalPadding: Dp = 24.dp
internal val PageVerticalPadding: Dp = 20.dp

/** The fold between the two pages of the book. */
internal val SpineWidth: Dp = 1.dp

/** The room between the two pages of the book, the spine at its middle. */
internal val SpineGutter: Dp = 28.dp

/** What the screen shows: one screen of pages of one layout. [layout] changes on every new pagination. */
internal data class ScreenKey(val layout: Int, val screen: Int)

/** The screen on display and whether a change to it slides (false: it changes at once, under a turn or a jump). */
private data class Shown(val key: ScreenKey, val slide: Boolean)

/**
 * The pages of one screen at a time, placed by [frame]. A change of one screen turns the page in 3D when [turn] is
 * given (the rolled sheet in single sheet, the page hinged on the spine in the book, on a device that can draw it) and
 * slides otherwise; with [instant] the screens change at once. A horizontal drag turns the page under the finger, or,
 * without [turn], is a swipe that asks for the next (to the left) or the previous screen (to the right). [onStep]
 * moves the screen by one.
 */
@Composable
internal fun PagedView(
    chapters: List<ChapterSpec>,
    pagination: Pagination,
    perScreen: Int,
    frame: BookFrame,
    target: ScreenKey,
    instant: Boolean,
    turn: SheetTurn<ScreenKey>?,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step by rememberUpdatedState(onStep)
    val current by rememberUpdatedState(target)
    val density = LocalDensity.current
    val paper = LocalReaderColors.current.page.toArgb()
    val scope = rememberCoroutineScope()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val screens = pagination.screenCount(perScreen)
    val page = {
        TurnPage(boxSize.width / density.density, boxSize.height / density.density, paper, frame.spineCenter / density.density)
    }

    var shown by remember { mutableStateOf(Shown(target, slide = false)) }
    SideEffect { turn?.shown = shown.key }
    LaunchedEffect(target, turn, instant) {
        val from = shown.key
        if (turn == null || instant || from == target) {
            shown = Shown(target, slide = !instant)
            return@LaunchedEffect
        }
        turn.takeClaim(target)?.let { slide ->
            shown = Shown(target, slide)
            return@LaunchedEffect
        }
        val dir = target.screen - from.screen
        val played = from.layout == target.layout && abs(dir) == 1 && boxSize != IntSize.Zero &&
            turn.play(from, target, dir, page()) { shown = Shown(target, slide = false) }
        if (!played) shown = Shown(target, slide = true)
    }
    val display = if (turn == null) Shown(target, slide = true) else shown

    val gesture = Modifier.pointerInput(turn, screens) {
        val startSlop = DragStart.toPx()
        val swipePx = SwipeLength.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var decided = false
            var drag: DragInput? = null
            var swipeDir = 0
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        drag?.release(change.uptimeMillis)
                        if (swipeDir != 0 && (down.position.x - change.position.x) * swipeDir >= swipePx && turn?.busy != true) {
                            step(swipeDir)
                        }
                        break
                    }
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (!decided && change.isConsumed) decided = true // a child (a scrolling block) took the gesture
                    if (!decided && abs(dx) > startSlop && abs(dx) > abs(dy) * 1.2f) {
                        decided = true
                        val dir = if (dx < 0) 1 else -1
                        val from = current
                        if (from.screen + dir in 0 until screens) {
                            if (turn != null && !turn.busy && boxSize != IntSize.Zero) {
                                // where the sheet was taken (how high on the page) shapes the turn, as does the drag
                                val input = DragInput(
                                    dir, (down.position.y / size.height).coerceIn(0f, 1f), density.density,
                                    change.position.x, change.position.y, down.position.x,
                                )
                                drag = input
                                val to = ScreenKey(from.layout, from.screen + dir)
                                scope.launch { turn.drag(from, to, input, page(), swipePx) { step(it) } }
                            } else {
                                swipeDir = dir
                            }
                        }
                    }
                    drag?.move(change.position.x, change.position.y, change.uptimeMillis)
                    if (drag != null || swipeDir != 0) change.consume()
                }
            } finally {
                // the gesture was taken away (or ended without a lift): the sheet goes back
                drag?.takeIf { !it.released.isCompleted }?.cancel()
            }
        }
    }
    Box(
        modifier.fillMaxSize().clipToBounds().onSizeChanged { boxSize = it }.then(gesture).testTag("pages")
            .semantics { if (turn != null) this[PageTurnKindKey] = turn.kind },
    ) {
        if (instant) {
            ScreenView(chapters, pagination, perScreen, target.screen, frame)
        } else {
            // the screen a turn is about to reach, composed out of sight for its picture
            val preparing = turn?.preparing
            if (preparing != null && preparing != display.key) {
                key(preparing) {
                    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                        PictureBox(preparing, turn.pictures, shown = false, Modifier.fillMaxSize()) {
                            ScreenView(chapters, pagination, perScreen, preparing.screen, frame)
                        }
                    }
                }
            }
            AnimatedContent(
                targetState = display,
                contentKey = { it.key },
                transitionSpec = {
                    val from = initialState.key
                    val to = targetState.key
                    if (!targetState.slide || from.layout != to.layout || abs(to.screen - from.screen) != 1) {
                        ContentTransform(EnterTransition.None, ExitTransition.None)
                    } else {
                        val dir = if (to.screen > from.screen) 1 else -1
                        slideInHorizontally(tween(SLIDE_MS)) { dir * it } togetherWith
                            slideOutHorizontally(tween(SLIDE_MS)) { -dir * it }
                    }
                },
                label = "page slide",
            ) { s ->
                PictureBox(s.key, turn?.pictures, shown = true, Modifier.fillMaxSize()) {
                    ScreenView(chapters, pagination, perScreen, s.key.screen, frame)
                }
            }
            turn?.let { SheetTurnOverlay(it) }
        }
    }
}

private const val SLIDE_MS = 320

/** How far a finger goes sideways before the page takes it as a turn (and not a tap). */
private val DragStart: Dp = 12.dp

/** How long a swipe (a drag without the 3D turn) must be to change the screen. */
private val SwipeLength: Dp = 48.dp

/** One screen: a single page, or two pages with the spine between them, where [frame] puts them. */
@Composable
private fun ScreenView(chapters: List<ChapterSpec>, pagination: Pagination, perScreen: Int, screen: Int, frame: BookFrame) {
    val colors = LocalReaderColors.current
    val first = screen * perScreen
    Box(Modifier.fillMaxSize().background(colors.page).testTag("screen-$screen")) {
        if (perScreen == 1) {
            Box(Modifier.placePx(0, frame.pageWidth)) {
                pagination.pages.getOrNull(first)?.let { PageView(it, chapters, "page") }
            }
        } else {
            Box(Modifier.placePx(frame.leftStart, frame.pageWidth)) {
                pagination.pages.getOrNull(first)?.let { PageView(it, chapters, "page-left") }
            }
            Spine(Modifier.placePx(frame.spineStart, frame.spineWidth))
            Box(Modifier.placePx(frame.rightStart, frame.pageWidth)) {
                pagination.pages.getOrNull(first + 1)?.let { PageView(it, chapters, "page-right") }
            }
        }
    }
}

/** Exactly [width] pixels wide from [x] and as tall as allowed, so a page has the width it was measured at. */
private fun Modifier.placePx(x: Int, width: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
    layout(width, constraints.maxHeight) { placeable.place(x, 0) }
}

@Composable
private fun Spine(modifier: Modifier) {
    val colors = LocalReaderColors.current
    val shade = if (colors.dark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.07f)
    Box(modifier.fillMaxHeight().testTag("spine"), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, shade, Color.Transparent))),
        )
        Box(Modifier.fillMaxHeight().width(SpineWidth).background(colors.rule))
    }
}

/** One page: the slices of its pieces, each at its place, a story's pages tagged with the story. */
@Composable
private fun PageView(page: Page, chapters: List<ChapterSpec>, tag: String) {
    val chapter = chapters[page.chapter]
    val tagged = chapter.story?.let { Modifier.testTag("chapter-${it.id}") } ?: Modifier
    Box(Modifier.fillMaxSize().testTag(tag)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(tagged)
                .padding(horizontal = PageHorizontalPadding, vertical = PageVerticalPadding),
        ) {
            page.slices.forEach { slice ->
                val spec = chapter.pieces[slice.piece]
                key(spec.key, slice.from) { SliceView(slice, spec) }
            }
        }
    }
}

/** The part of a piece a page shows: the piece laid out whole, shifted up and clipped to the slice. */
@Composable
private fun SliceView(slice: Slice, spec: PieceSpec) {
    val top = slice.top.roundToInt()
    val from = slice.from.roundToInt()
    val height = slice.height.roundToInt()
    Box(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, top) }
            .clipToBounds()
            .layout { measurable, constraints ->
                val inner = if (spec.fill) {
                    Constraints.fixed(constraints.maxWidth, height)
                } else {
                    Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
                }
                val placeable = measurable.measure(inner)
                layout(constraints.maxWidth, height) { placeable.place(0, -from) }
            },
    ) {
        spec.content()
    }
}
