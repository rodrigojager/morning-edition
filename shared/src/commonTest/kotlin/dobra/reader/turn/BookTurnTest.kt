package dobra.reader.turn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookTurnTest {
    // a wide screen: two pages of 410 dp around the fold
    private val book = BookTurn(820f, 900f)
    private val sw = book.sheetWidth
    private val h = book.height / 2f

    private fun near(expected: Float, actual: Float, tolerance: Float = 1e-3f, what: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$what expected $expected, was $actual")

    private fun stripAt(pose: BookPose) = book.strip().also { book.pose(pose, it) }

    private fun BookStrip.x(n: Int) = positions[n * 3]
    private fun BookStrip.y(n: Int) = positions[n * 3 + 1]
    private fun BookStrip.z(n: Int) = positions[n * 3 + 2]
    private fun BookStrip.topEdge() = x(segments * 2)
    private fun BookStrip.bottomEdge() = x(segments * 2 + 1)
    private fun BookStrip.edge() = (topEdge() + bottomEdge()) / 2f

    // ----- geometry -----

    @Test
    fun atProgressZeroThePageLiesFlatOnTheRight() {
        val strip = stripAt(book.at(0f))
        for (i in 0..strip.segments) {
            val x = book.hinge + sw * i / strip.segments
            near(x, strip.x(i * 2), 1e-2f, "top x of $i")
            near(x, strip.x(i * 2 + 1), 1e-2f, "bottom x of $i")
            assertEquals(h, strip.y(i * 2))
            assertEquals(-h, strip.y(i * 2 + 1))
            near(0f, strip.z(i * 2), what = "top z of $i")
            near(0f, strip.z(i * 2 + 1), what = "bottom z of $i")
        }
        near(book.width, strip.edge(), 1e-2f, "free edge")
        assertEquals(0f, strip.shadow.opacity)
    }

    @Test
    fun atProgressOneThePageLiesFlatOnTheLeft() {
        val strip = stripAt(book.at(1f))
        for (n in 0 until strip.vertexCount) {
            assertTrue(strip.x(n) <= book.hinge + 1e-2f && strip.x(n) >= book.hinge - sw - 1e-2f, "vertex $n at x=${strip.x(n)}")
            near(0f, strip.z(n), 1e-2f, "z of $n")
        }
        near(book.hinge - sw, strip.edge(), 1e-2f, "free edge")
        near(0f, strip.shadow.opacity, 1e-6f)
    }

    @Test
    fun halfwayThePageStandsOverTheFold() {
        val strip = stripAt(book.at(0.5f))
        near(book.hinge, strip.edge(), 0.03f * sw, "free edge, the bent page leaning a little")
        assertTrue(strip.z(strip.segments * 2) > 0.8f * sw, "the free edge is up towards the eye")
        near(0.3f, strip.shadow.opacity)
    }

    @Test
    fun theFoldStaysPutAndThePageKeepsItsLength() {
        listOf(0.2f, 0.5f, 0.8f).forEach { t ->
            val strip = stripAt(BookPose(t, 0.2f))
            near(book.hinge, strip.x(0), what = "top at the fold")
            near(book.hinge, strip.x(1), what = "bottom at the fold")
            for (row in 0..1) {
                var length = 0f
                for (i in 0 until strip.segments) {
                    val a = i * 2 + row
                    val b = a + 2
                    val dx = strip.x(b) - strip.x(a)
                    val dz = strip.z(b) - strip.z(a)
                    length += kotlin.math.sqrt(dx * dx + dz * dz)
                }
                near(sw, length, 0.05f, "length of row $row at $t")
            }
        }
    }

    @Test
    fun theStripIsCounterClockwiseSeenFromThePrintedFace() {
        val strip = stripAt(book.at(0f))
        val idx = strip.indices
        assertEquals(strip.segments * 6, idx.size)
        for (k in idx.indices step 3) {
            val (a, b, c) = Triple(idx[k].toInt(), idx[k + 1].toInt(), idx[k + 2].toInt())
            val cross = (strip.x(b) - strip.x(a)) * (strip.y(c) - strip.y(a)) - (strip.y(b) - strip.y(a)) * (strip.x(c) - strip.x(a))
            assertTrue(cross > 0f, "triangle ${k / 3}")
        }
    }

    // ----- pictures on the two faces -----

    @Test
    fun theFrontShowsTheRightPageAndTheBackTheLeftPageMirrored() {
        val strip = book.strip()
        for (i in 0..strip.segments) {
            val f = i.toFloat() / strip.segments
            near(0.5f + 0.5f * f, strip.frontUv[i * 4], what = "front u of $i")
            near(0.5f - 0.5f * f, strip.backUv[i * 4], what = "back u of $i")
            assertEquals(0f, strip.frontUv[i * 4 + 1], "top row from the top of the picture")
            assertEquals(1f, strip.backUv[i * 4 + 3], "bottom row from the bottom of the picture")
        }
        // a fold off the middle: the faces still start at the fold
        val off = BookTurn(800f, 900f, hinge = 420f).strip()
        near(420f / 800f, off.frontUv[0])
        near(1f, off.frontUv[off.segments * 4])
        near(420f / 800f, off.backUv[0])
        near(40f / 800f, off.backUv[off.segments * 4])
    }

    // ----- pose under a finger -----

    @Test
    fun turningForwardTheFreeEdgeKeepsTheDistanceTheFingerTravelled() {
        listOf(20f, 100f, 205f, 300f, 410f, 520f, 700f).forEach { mx ->
            val d = book.drag(1, 0f, mx, 0f)
            // laid straight, the page's edge is exactly as far from where it started as the finger
            near(book.width - mx, book.hinge + sw * cos(PI.toFloat() * d.pose.t), 0.05f, "straight edge for $mx")
            // drawn, the page bends (most halfway over) and its edge stays within 6% of the page width of the finger
            near(book.width - mx, stripAt(d.pose).edge(), 0.06f * sw, "drawn edge for $mx")
            near(d.pose.t, d.progress)
        }
        near(book.hinge, stripAt(book.drag(1, 0f, sw, 0f).pose).edge(), 0.03f * sw, "crossing the fold")
        assertEquals(0f, book.drag(1, 0f, -40f, 0f).progress, "coming back past the start counts as zero")
        assertEquals(1f, book.drag(1, 0f, 5000f, 0f).progress)
    }

    @Test
    fun bringingAPageBackTheFreeEdgeKeepsTheDistanceTheFingerTravelled() {
        assertEquals(1f, book.drag(-1, 0f, 0f, 0f).pose.t)
        listOf(50f, 200f, 410f, 600f).forEach { mx ->
            val d = book.drag(-1, 0f, mx, 0f)
            near(book.hinge - sw + mx, book.hinge + sw * cos(PI.toFloat() * d.pose.t), 0.05f, "straight edge for $mx")
            near(1f - d.pose.t, d.progress)
        }
    }

    private val low = -0.35f * book.height // the finger went down low on the page
    private val high = 0.35f * book.height

    @Test
    fun aPageTakenLowTwistsWithTheBottomLeading() {
        val d = book.drag(1, low, 0.6f * sw, 0f)
        assertTrue(d.pose.lead > 0f, "lead was ${d.pose.lead}")
        val strip = stripAt(d.pose)
        assertTrue(strip.bottomEdge() < strip.topEdge() - 5f, "bottom ${strip.bottomEdge()} ahead of top ${strip.topEdge()}")
        // bringing the page back the bottom leads too: it is further right
        val back = stripAt(book.drag(-1, low, 0.6f * sw, 0f).pose)
        assertTrue(back.bottomEdge() > back.topEdge() + 5f, "bottom ${back.bottomEdge()} ahead of top ${back.topEdge()}")
    }

    @Test
    fun aPageTakenHighTwistsWithTheTopLeading() {
        val d = book.drag(1, high, 0.6f * sw, 0f)
        assertTrue(d.pose.lead < 0f, "lead was ${d.pose.lead}")
        val strip = stripAt(d.pose)
        assertTrue(strip.topEdge() < strip.bottomEdge() - 5f, "top ${strip.topEdge()} ahead of bottom ${strip.bottomEdge()}")
        val back = stripAt(book.drag(-1, high, 0.6f * sw, 0f).pose)
        assertTrue(back.topEdge() > back.bottomEdge() + 5f, "top ${back.topEdge()} ahead of bottom ${back.bottomEdge()}")
    }

    @Test
    fun theDirectionOfTheDragTwistsToo() {
        // taken at mid-height, a drag going up lifts the bottom first, a drag going down the top
        assertTrue(book.drag(1, 0f, 200f, -80f).pose.lead > 0f)
        assertTrue(book.drag(1, 0f, 200f, 80f).pose.lead < 0f)
        near(0f, book.drag(1, 0f, 200f, 0f).pose.lead, 0f, "a straight drag at mid-height")
    }

    @Test
    fun theTwistGrowsWithTheDragAndIsBounded() {
        near(0f, book.drag(1, low, 0f, 0f).pose.lead, 0f, "no twist before the finger moved")
        val small = book.drag(1, low, 0.1f * sw, 0f).pose.lead
        val big = book.drag(1, low, 0.5f * sw, 0f).pose.lead
        assertTrue(big > small)
        listOf(low, high).forEach { y ->
            assertTrue(abs(book.drag(1, y, 300f, if (y < 0) -900f else 900f).pose.lead) <= 0.35f + 1e-6f)
        }
    }

    // ----- release -----

    @Test
    fun aReleasedPageSettlesOnTheSideItFallsTo() {
        assertEquals(BookPose(1f), book.restingPose(1, commit = true))
        assertEquals(BookPose(0f), book.restingPose(1, commit = false))
        assertEquals(BookPose(0f), book.restingPose(-1, commit = true))
        assertEquals(BookPose(1f), book.restingPose(-1, commit = false))
        val from = BookPose(0.6f, 0.2f)
        val to = book.restingPose(1, true)
        assertEquals(from, book.settle(from, to, 0f))
        val half = book.settle(from, to, 0.5f)
        near(0.8f, half.t)
        near(0.1f, half.lead)
        assertEquals(to, book.settle(from, to, 1f))
        assertEquals(160 + (460 * 0.4f).toInt(), book.settleMillis(from, to))
        // the release rule is the shared one: past the fold it completes
        assertTrue(SheetCurl.commits(book.drag(1, 0f, 1.2f * sw, 0f).progress, 0f, false))
        assertTrue(!SheetCurl.commits(book.drag(1, 0f, 0.8f * sw, 0f).progress, 0f, false))
    }

    @Test
    fun aTurnPlayedByItselfGoesFromRightToLeftUntwisted() {
        assertEquals(BookPose(0f, 0f), book.at(0f))
        assertEquals(BookPose(1f, 0f), book.at(1f))
        assertEquals(TurnSpec.BOOK_PLAY_MS, book.playMs)
        assertEquals(40, book.strip().segments)
    }
}
