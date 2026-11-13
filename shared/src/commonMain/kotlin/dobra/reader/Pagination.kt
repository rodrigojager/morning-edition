package dobra.reader

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where the reader is: a chapter and how far into it, from 0 (its first page) to below 1. It survives a new layout
 * (another mode, width or text size) because it names no page.
 */
data class ReadingPosition(val chapter: String, val fraction: Double = 0.0)

/**
 * One measured piece of a chapter, as the app laid it out at the page width: a block, a row of the contents, the
 * buttons of a story. Heights are in any unit the caller likes (the app uses pixels), the same for every piece.
 *
 * @property cuts offsets inside the piece where a page may end it (line bottoms of a paragraph, the edges of glossary
 *   entries or source links). A piece without cuts is never cut when it fits on a page.
 * @property keepWithNext the piece stays on the page of what follows it (a section title).
 * @property spaceBefore the gap above the piece, dropped when the piece starts a page.
 * @property fill the piece takes a whole page of its own whatever its height (the cover).
 */
data class Piece(
    val height: Float,
    val cuts: List<Float> = emptyList(),
    val keepWithNext: Boolean = false,
    val spaceBefore: Float = 0f,
    val fill: Boolean = false,
)

/** A chapter of the flow with its pieces in reading order. */
data class FlowChapter(val id: String, val pieces: List<Piece>)

/** The part [from] to [to] of piece [piece] (an index in its chapter), drawn at [top] on its page. */
data class Slice(val piece: Int, val from: Float, val to: Float, val top: Float) {
    val height: Float get() = to - from
}

/** One page: the index of its chapter in the flow and the slices it shows, top to bottom. */
data class Page(val chapter: Int, val slices: List<Slice>)

/**
 * The flow cut into pages of one height. Every chapter starts on a new page and has at least one page.
 * Pages are grouped into screens of one page (single sheet) or two (book).
 */
class Pagination(val chapters: List<String>, val pages: List<Page>) {
    private val starts: IntArray = IntArray(chapters.size) { c -> pages.indexOfFirst { it.chapter == c } }

    val pageCount: Int get() = pages.size

    fun startOf(chapter: Int): Int = starts[chapter]

    /** How many pages the chapter takes. */
    fun spanOf(chapter: Int): Int = (starts.getOrNull(chapter + 1) ?: pages.size) - starts[chapter]

    fun screenCount(perScreen: Int): Int = max(1, (pages.size + perScreen - 1) / perScreen)

    /** The chapter that names a screen: the first one starting on it, otherwise the one still running. */
    fun chapterOfScreen(screen: Int, perScreen: Int): Int {
        val first = screen * perScreen
        val last = min(pages.size, first + perScreen) - 1
        for (p in first..last) {
            val c = pages[p].chapter
            if (starts[c] == p) return c
        }
        return pages.getOrNull(first)?.chapter ?: 0
    }

    /** The position a screen stands for: its naming chapter and how far into that chapter its first page is. */
    fun positionOfScreen(screen: Int, perScreen: Int): ReadingPosition {
        val c = chapterOfScreen(screen, perScreen)
        val page = max(starts[c], screen * perScreen)
        return ReadingPosition(chapters[c], (page - starts[c]).toDouble() / spanOf(c))
    }

    /** The page a position lands on: inside its chapter, the share [ReadingPosition.fraction] of the way in. */
    fun pageOf(position: ReadingPosition): Int {
        val c = chapters.indexOf(position.chapter)
        if (c < 0) return 0
        val span = spanOf(c)
        val offset = floor(position.fraction.coerceIn(0.0, 1.0) * span + 1e-6).toInt()
        return starts[c] + offset.coerceIn(0, span - 1)
    }

    fun screenOf(position: ReadingPosition, perScreen: Int): Int = pageOf(position) / perScreen

    companion object {
        /**
         * Fills pages of [pageHeight] with the [flow], piece by piece. A piece that fits goes whole; one that does not
         * is ended at its last cut that fits, or moved to the next page when it has none; a piece taller than a page
         * with no usable cut is cut at the page's foot. A piece kept with the next one moves to the next page when the
         * first part of the next one would not fit after it.
         */
        fun paginate(flow: List<FlowChapter>, pageHeight: Float): Pagination {
            require(pageHeight > 0f) { "pageHeight must be positive" }
            val pages = mutableListOf<Page>()
            flow.forEachIndexed { c, chapter -> pages += paginateChapter(c, chapter.pieces, pageHeight) }
            return Pagination(flow.map { it.id }, pages)
        }

        private fun paginateChapter(c: Int, pieces: List<Piece>, pageHeight: Float): List<Page> {
            val pages = mutableListOf<Page>()
            var slices = mutableListOf<Slice>()
            var y = 0f
            fun newPage() {
                pages += Page(c, slices)
                slices = mutableListOf()
                y = 0f
            }

            var i = 0
            var from = 0f
            while (i < pieces.size) {
                val piece = pieces[i]
                if (piece.fill) {
                    if (slices.isNotEmpty()) newPage()
                    slices += Slice(i, 0f, piece.height, 0f)
                    newPage()
                    i++; from = 0f
                    continue
                }
                val gap = if (slices.isEmpty() || from > 0f) 0f else piece.spaceBefore
                val room = pageHeight - y - gap
                val rest = piece.height - from
                if (rest <= room + EPS) {
                    if (piece.keepWithNext && from == 0f && slices.isNotEmpty()) {
                        val next = pieces.getOrNull(i + 1)
                        if (next != null && !next.fill && firstPart(next) > room - rest - next.spaceBefore + EPS) {
                            newPage()
                            continue
                        }
                    }
                    slices += Slice(i, from, piece.height, y + gap)
                    y += gap + rest
                    i++; from = 0f
                    continue
                }
                val cut = piece.cuts.lastOrNull { it > from + EPS && it - from <= room + EPS }
                when {
                    cut != null -> {
                        slices += Slice(i, from, cut, y + gap)
                        from = cut
                        newPage()
                    }
                    slices.isNotEmpty() -> newPage()
                    else -> {
                        val end = from + pageHeight
                        slices += Slice(i, from, end, 0f)
                        from = end
                        newPage()
                    }
                }
            }
            if (slices.isNotEmpty() || pages.isEmpty()) newPage()
            return pages
        }

        /** The smallest part of a piece that can open a page: up to its first cut, or all of it. */
        private fun firstPart(piece: Piece): Float = piece.cuts.firstOrNull { it > EPS } ?: piece.height

        private const val EPS = 0.01f
    }
}
