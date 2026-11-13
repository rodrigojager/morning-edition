package dobra.app.edition.paged

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * Where the parts of a screen go, in pixels from the left of the pages' area: one page [pageWidth] wide, or two pages
 * of that width with the spine between them (from [spineStart], [spineWidth] wide). Both pages have the same width, so
 * the pagination of a spread fits either page.
 */
internal data class BookFrame(
    val pageWidth: Int,
    val leftStart: Int,
    val spineStart: Int,
    val spineWidth: Int,
    val rightStart: Int,
) {
    /** The middle of the spine: the fold the book's page turns over. */
    val spineCenter: Float get() = spineStart + spineWidth / 2f

    companion object {
        /** One page over the whole [width]. */
        fun single(width: Int) = BookFrame(width, 0, width, 0, width)

        /**
         * Two pages in [width] pixels with a spine of at least [gutter] pixels. On a vertical [fold] of the device
         * (its left and right edges, pixels from the area's left) the spine covers the fold, as wide as the fold if
         * the fold is wider, so no text runs across it; otherwise, or when the fold would leave a page less than a
         * quarter of the width, the spine sits in the middle.
         */
        fun book(width: Int, gutter: Int, fold: IntRange? = null): BookFrame {
            if (fold != null && fold.first >= 0 && fold.last <= width && fold.first <= fold.last) {
                val spine = maxOf(gutter, fold.last - fold.first)
                val start = (fold.first + fold.last - spine) / 2
                val page = minOf(start, width - start - spine)
                if (page >= width / 4) return BookFrame(page, start - page, start, spine, start + spine)
            }
            val page = (width - gutter) / 2
            val spine = width - 2 * page
            return BookFrame(page, 0, page, spine, page + spine)
        }
    }
}

/**
 * The device's vertical fold as its left and right edges in window pixels, from Jetpack WindowManager; null without a
 * fold, with a horizontal one, or outside an activity.
 */
@Composable
internal fun rememberVerticalFold(): IntRange? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return null
    val fold by produceState<IntRange?>(null, activity) {
        try {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                value = info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
                    ?.bounds?.let { it.left..it.right }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            value = null // no window information on this device: the spine stays in the middle
        }
    }
    return fold
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
