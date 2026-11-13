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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dobra.app.shell.LocalReaderColors
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

/**
 * The pages of one screen at a time, sliding to the next or previous one. A horizontal swipe asks for the next
 * (to the left) or the previous screen (to the right). With [instant] the screens change at once.
 */
@Composable
internal fun PagedView(
    chapters: List<ChapterSpec>,
    pagination: Pagination,
    perScreen: Int,
    pageWidth: Int,
    target: ScreenKey,
    instant: Boolean,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step by rememberUpdatedState(onStep)
    val swipe = Modifier.pointerInput(Unit) {
        var total = 0f
        val threshold = 48.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total <= -threshold) step(1) else if (total >= threshold) step(-1)
                total = 0f
            },
            onDragCancel = { total = 0f },
        ) { change, dx ->
            total += dx
            change.consume()
        }
    }
    Box(modifier.fillMaxSize().clipToBounds().then(swipe).testTag("pages")) {
        if (instant) {
            ScreenView(chapters, pagination, perScreen, target.screen, pageWidth)
        } else {
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    val from = initialState
                    val to = targetState
                    if (from.layout != to.layout || abs(to.screen - from.screen) != 1) {
                        ContentTransform(EnterTransition.None, ExitTransition.None)
                    } else {
                        val dir = if (to.screen > from.screen) 1 else -1
                        slideInHorizontally(tween(SLIDE_MS)) { dir * it } togetherWith
                            slideOutHorizontally(tween(SLIDE_MS)) { -dir * it }
                    }
                },
                label = "page slide",
            ) { key ->
                ScreenView(chapters, pagination, perScreen, key.screen, pageWidth)
            }
        }
    }
}

private const val SLIDE_MS = 320

/** One screen: a single page, or two pages with the spine between them. */
@Composable
private fun ScreenView(chapters: List<ChapterSpec>, pagination: Pagination, perScreen: Int, screen: Int, pageWidth: Int) {
    val colors = LocalReaderColors.current
    val first = screen * perScreen
    Row(Modifier.fillMaxSize().background(colors.page).testTag("screen-$screen")) {
        if (perScreen == 1) {
            Box(Modifier.widthPx(pageWidth)) {
                pagination.pages.getOrNull(first)?.let { PageView(it, chapters, "page") }
            }
        } else {
            Box(Modifier.widthPx(pageWidth)) {
                pagination.pages.getOrNull(first)?.let { PageView(it, chapters, "page-left") }
            }
            Spine(Modifier.weight(1f))
            Box(Modifier.widthPx(pageWidth)) {
                pagination.pages.getOrNull(first + 1)?.let { PageView(it, chapters, "page-right") }
            }
        }
    }
}

/** Exactly [px] wide and as tall as allowed, so a page has the width it was measured at. */
private fun Modifier.widthPx(px: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(px, constraints.maxHeight))
    layout(px, constraints.maxHeight) { placeable.place(0, 0) }
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
