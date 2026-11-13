package dobra.reader

import dobra.model.Block
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginationTest {
    private val line = 26f

    /** A story the way the app measures it: one piece per block, paragraphs cut at their lines, entries at their edges. */
    private val story: List<Block> = buildList {
        add(block("title", "Uma auditoria de segurança em seis fases"))
        add(block("summary", "x".repeat(300)))
        repeat(3) { s ->
            add(block("section", "Seção $s"))
            repeat(4) { p -> add(block("paragraph", "y".repeat(120 + 97 * ((s * 4 + p) % 7)))) }
            if (s == 1) add(block("image", "z"))
            if (s == 2) add(block("glossary", "w".repeat(400)))
        }
        add(block("code", "c".repeat(200)))
        add(block("sources", "s".repeat(250)))
    }

    private fun block(type: String, text: String) = Block(type, mapOf("text" to JsonPrimitive(text)))

    private fun pieceOf(block: Block): Piece {
        val text = block.fields["text"].toString().length
        return when (block.type) {
            "paragraph", "summary" -> {
                val lines = (text / 40) + 1
                Piece(lines * line, cuts = (1 until lines).map { it * line }, spaceBefore = 16f)
            }
            "glossary", "sources" -> {
                val entries = (text / 60) + 1
                Piece(entries * 70f, cuts = (1 until entries).map { it * 70f }, spaceBefore = 16f)
            }
            "section" -> Piece(38f, keepWithNext = true, spaceBefore = 16f)
            "image" -> Piece(260f, spaceBefore = 16f)
            "code" -> Piece(180f, spaceBefore = 16f)
            else -> Piece(90f, spaceBefore = 16f)
        }
    }

    private val chapterPieces = story.map(::pieceOf)

    private fun flow() = listOf(
        FlowChapter("cover", listOf(Piece(500f, fill = true))),
        FlowChapter("toc", List(8) { Piece(60f) }),
        FlowChapter("story", chapterPieces),
        FlowChapter("notes", List(6) { Piece(140f, spaceBefore = 20f) }),
        FlowChapter("backstage", emptyList()),
    )

    @Test
    fun pagesPutTogetherGiveTheBlocksInOrderWithNothingLostOrRepeated() {
        for (height in listOf(180f, 240f, 320f, 500f, 777f, 1200f)) {
            val pagination = Pagination.paginate(flow(), height)
            val c = pagination.chapters.indexOf("story")
            val slices = pagination.pages.filter { it.chapter == c }.flatMap { it.slices }

            // Joined end to end, the slices of each piece cover it from 0 to its height exactly once.
            val joined = mutableListOf<String>()
            var expectFrom = 0f
            var current = -1
            slices.forEach { s ->
                if (s.piece != current) {
                    assertEquals(current + 1, s.piece, "pieces in order at height $height")
                    if (current >= 0) assertEquals(chapterPieces[current].height, expectFrom, "piece $current finished")
                    current = s.piece
                    expectFrom = 0f
                    joined += story[s.piece].type
                }
                assertEquals(expectFrom, s.from, "no gap or overlap inside piece ${s.piece} at height $height")
                assertTrue(s.to > s.from)
                expectFrom = s.to
            }
            assertEquals(chapterPieces.lastIndex, current)
            assertEquals(chapterPieces.last().height, expectFrom)
            assertEquals(story.map { it.type }, joined)

            // Nothing overflows a page, and slices on a page do not overlap.
            pagination.pages.forEach { page ->
                var bottom = 0f
                page.slices.forEach { s ->
                    assertTrue(s.top >= bottom - 0.01f)
                    bottom = s.top + s.height
                }
                if (pagination.chapters[page.chapter] != "cover") assertTrue(bottom <= height + 0.01f)
            }
        }
    }

    @Test
    fun theRulesReadersNotice() {
        val height = 320f
        val pagination = Pagination.paginate(flow(), height)
        val c = pagination.chapters.indexOf("story")
        val pages = pagination.pages.withIndex().filter { it.value.chapter == c }

        // A section title is never the last thing on a page.
        pages.forEach { (_, page) ->
            val last = page.slices.last()
            if (pagination.pages.last() != page) assertTrue(story[last.piece].type != "section", "section left alone")
        }
        // A piece without cuts that fits on a page is never cut.
        val whole = story.indices.filter { chapterPieces[it].cuts.isEmpty() && chapterPieces[it].height <= height }
        whole.forEach { i -> assertEquals(1, pages.sumOf { (_, p) -> p.slices.count { it.piece == i } }, "piece $i cut") }
        // A cut piece ends on one of its cuts.
        pages.flatMap { it.value.slices }.filter { it.to < chapterPieces[it.piece].height }.forEach {
            assertTrue(it.to in chapterPieces[it.piece].cuts)
        }
        // Every chapter starts on a new page, and an empty chapter still has its page.
        pagination.chapters.indices.forEach { ch ->
            val start = pagination.startOf(ch)
            assertEquals(ch, pagination.pages[start].chapter)
            assertTrue(start == 0 || pagination.pages[start - 1].chapter == ch - 1)
            assertTrue(pagination.spanOf(ch) >= 1)
        }
        // The cover takes a page of its own.
        assertEquals(1, pagination.spanOf(0))
    }

    @Test
    fun aPieceTallerThanAPageWithoutCutsIsCutAtThePageFoot() {
        val pagination = Pagination.paginate(listOf(FlowChapter("a", listOf(Piece(50f), Piece(250f)))), 100f)
        val slices = pagination.pages.map { p -> p.slices.map { it.piece to it.to } }
        assertEquals(listOf(listOf(0 to 50f), listOf(1 to 100f), listOf(1 to 200f), listOf(1 to 250f)), slices)
    }

    @Test
    fun thePositionLandsInTheSameChapterAfterThePageSizeChanges() {
        val heights = listOf(180f, 240f, 320f, 500f, 777f, 1200f)
        for (from in heights) for (perFrom in listOf(1, 2)) {
            val before = Pagination.paginate(flow(), from)
            for (screen in 0 until before.screenCount(perFrom)) {
                val position = before.positionOfScreen(screen, perFrom)
                assertTrue(position.fraction in 0.0..<1.0)
                for (to in heights) for (perTo in listOf(1, 2)) {
                    val after = Pagination.paginate(flow(), to)
                    val page = after.pageOf(position)
                    assertEquals(position.chapter, after.chapters[after.pages[page].chapter], "from $from/$perFrom to $to/$perTo")
                    // the chapter is on the screen that holds the page
                    val s = after.screenOf(position, perTo)
                    val onScreen = (s * perTo until minOf(after.pageCount, s * perTo + perTo)).map { after.chapters[after.pages[it].chapter] }
                    assertTrue(position.chapter in onScreen)
                }
            }
        }
    }

    @Test
    fun thePositionKeepsTheShareOfTheWayIntoTheChapter() {
        val small = Pagination.paginate(flow(), 180f)
        val c = small.chapters.indexOf("story")
        val span = small.spanOf(c)
        assertTrue(span >= 6)
        val middle = small.positionOfScreen(small.startOf(c) + span / 2, 1)
        assertEquals("story", middle.chapter)
        val large = Pagination.paginate(flow(), 500f)
        val page = large.pageOf(middle)
        val share = (page - large.startOf(c)).toDouble() / large.spanOf(c)
        assertTrue(share in 0.25..0.75, "share $share")
        // the first screen of a chapter stands for its start
        assertEquals(ReadingPosition("story", 0.0), small.positionOfScreen(small.startOf(c), 1))
        assertEquals(0, large.pageOf(ReadingPosition("missing", 0.5)))
    }

    @Test
    fun aScreenIsNamedByTheChapterStartingOnIt() {
        val flow = listOf(
            FlowChapter("a", listOf(Piece(300f))),
            FlowChapter("b", listOf(Piece(100f))),
            FlowChapter("c", listOf(Piece(100f))),
        )
        val p = Pagination.paginate(flow, 100f)
        // pages: a a a b c -> book screens: [a a] [a b] [c]
        assertEquals(5, p.pageCount)
        assertEquals(3, p.screenCount(2))
        assertEquals("b", p.positionOfScreen(1, 2).chapter)
        assertEquals(ReadingPosition("b", 0.0), p.positionOfScreen(1, 2))
        assertEquals(1, p.pageOf(ReadingPosition("a", 0.5)))
        assertEquals(0, p.screenOf(ReadingPosition("a", 0.5), 2))
        assertEquals(1, p.screenOf(ReadingPosition("a", 0.7), 2))
    }
}
