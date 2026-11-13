package dobra.reader.turn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SheetCurlTest {
    private val curl = SheetCurl(384f, 700f)
    private val r = curl.radius
    private val halfTurn = PI.toFloat() * r

    private fun near(expected: Float, actual: Float, tolerance: Float = 1e-3f, what: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$what expected $expected, was $actual")

    private fun meshAt(pose: CurlPose) = SheetMesh().also { curl.pose(pose, it) }

    // ----- geometry -----

    @Test
    fun atProgressZeroEveryPointIsWhereTheFlatPageHasIt() {
        val mesh = meshAt(curl.curlAt(0f))
        val gx = mesh.gridX
        val gy = mesh.gridY
        for (i in 0..gx) for (j in 0..gy) {
            val n = i * (gy + 1) + j
            near(curl.width * i / gx, mesh.positions[n * 3], what = "x of ($i,$j)")
            near(-curl.height / 2 + curl.height * j / gy, mesh.positions[n * 3 + 1], what = "y of ($i,$j)")
            near(0f, mesh.positions[n * 3 + 2], what = "z of ($i,$j)")
        }
        assertEquals(0f, mesh.shadow.opacity)
    }

    @Test
    fun atProgressOneEveryPointIsBeyondTheLeftEdge() {
        val mesh = meshAt(curl.curlAt(1f))
        for (n in 0 until mesh.vertexCount) {
            assertTrue(mesh.positions[n * 3] < 0f, "vertex $n is at x=${mesh.positions[n * 3]}")
        }
        assertEquals(0f, mesh.shadow.opacity, "no shadow once the roll left the screen")
    }

    @Test
    fun theMeshHasTheConfiguredDensity() {
        val mesh = SheetMesh()
        assertEquals(TurnSpec.GRID_X, mesh.gridX)
        assertEquals(TurnSpec.GRID_Y, mesh.gridY)
        assertEquals(73 * 25, mesh.vertexCount)
        assertEquals(72 * 24 * 6, mesh.indices.size)
        assertTrue(mesh.indices.all { it in 0 until mesh.vertexCount })
    }

    @Test
    fun aPointPastTheRollLiesFlatAtTwiceTheRadius() {
        listOf(CurlPose(200f, 0f, 0f), CurlPose(150f, 40f, 0.3f), CurlPose(260f, -30f, -0.4f)).forEach { pose ->
            // a point 10 dp past the end of the half turn, measured across the line
            val past = halfTurn + 10f
            val x = pose.fx + past * kotlin.math.cos(pose.a)
            val y = pose.fy - past * kotlin.math.sin(pose.a)
            val p = curl.point(pose, x, y)
            near(2 * r, p.z, what = "z")
            // it lies back over the line, as far before it as it was past the roll
            near(pose.fx - 10f * kotlin.math.cos(pose.a), p.x, 1e-2f, "x")
        }
    }

    @Test
    fun pointsBeforeTheLineStayWhereTheyAre() {
        val pose = CurlPose(250f, 10f, 0.2f)
        val p = curl.point(pose, 40f, 100f)
        near(40f, p.x, 1e-3f)
        near(100f, p.y, 1e-3f)
        assertEquals(0f, p.z)
    }

    @Test
    fun pastLineIsTheInverseOfTheTravelItProduces() {
        listOf(0.5f, 5f, 20f, r, 2 * r, halfTurn - 1f, halfTurn, halfTurn + 1f, 300f, 700f).forEach { past ->
            near(past, curl.pastLine(curl.travel(past)), 1e-2f, "pastLine(travel($past))")
        }
        listOf(0.2f, 3f, 30f, 90f, 200f, 500f).forEach { moved ->
            near(moved, curl.travel(curl.pastLine(moved)), 1e-2f, "travel(pastLine($moved))")
        }
    }

    @Test
    fun theRadiusFollowsTheWidthWithinItsBounds() {
        near(384f * 0.085f, SheetCurl(384f, 700f).radius)
        assertEquals(20f, SheetCurl(100f, 700f).radius)
        assertEquals(120f, SheetCurl(2000f, 700f).radius)
    }

    // ----- light -----

    @Test
    fun thePrintedFaceAndTheBlankBackAreLitSeparately() {
        val pose = CurlPose(200f, 0f, 0f)
        // on the roll, a quarter turn and three quarters of a turn in
        val rising = curl.point(pose, 200f + r * PI.toFloat() / 4, 0f)
        val falling = curl.point(pose, 200f + r * 3 * PI.toFloat() / 4, 0f)
        assertTrue(rising.front > falling.front, "the printed face dims as it turns away")
        assertTrue(falling.back > rising.back, "the back brightens as it comes up")
        assertTrue(rising.front != rising.back && falling.front != falling.back)
        // flat before the line, both faces get the same light
        val flat = curl.point(pose, 20f, 0f)
        assertEquals(flat.front, flat.back)
    }

    @Test
    fun theBackIsEvenlyLitOnceItLiesFlat() {
        val pose = CurlPose(180f, 20f, 0.25f)
        val turned = listOf(10f, 40f, 90f).map { extra ->
            val past = halfTurn + extra
            curl.point(pose, pose.fx + past * kotlin.math.cos(pose.a), pose.fy - past * kotlin.math.sin(pose.a) + 30f * extra / 90f)
        }
        turned.forEach {
            assertEquals(0.97f, it.back)
            assertEquals(0.6f, it.front)
        }
        val mesh = meshAt(CurlPose(100f, 0f, 0f))
        val flatBacks = (0 until mesh.vertexCount).filter { mesh.positions[it * 3 + 2] == 2 * r }.map { mesh.back[it] }
        assertTrue(flatBacks.size > 100, "much of the sheet lies turned")
        flatBacks.forEach { near(0.97f, it) }
    }

    // ----- pose under a finger -----

    private val low = -0.35f * curl.height // the finger went down low on the page
    private val high = 0.35f * curl.height

    @Test
    fun aTouchLowWithAnUpwardDragGivesAPositiveSlant() {
        val d = curl.drag(1, low, 150f, -40f)
        assertTrue(d.pose.a > 0f, "slant was ${d.pose.a}")
    }

    @Test
    fun aTouchHighWithADownwardDragGivesANegativeSlant() {
        val d = curl.drag(1, high, 150f, 40f)
        assertTrue(d.pose.a < 0f, "slant was ${d.pose.a}")
    }

    @Test
    fun aStraightDragAtMidHeightHasNoSlant() {
        listOf(1f, 60f, 200f, 384f).forEach { mx ->
            val d = curl.drag(1, 0f, mx, 0f)
            near(0f, d.pose.a, 0f, "slant")
            near(0f, d.pose.fy, 0f, "height")
        }
    }

    @Test
    fun turningForwardTheFreeEdgeFollowsTheFinger() {
        listOf(30f, 120f, 250f).forEach { mx ->
            val d = curl.drag(1, 0f, mx, 0f)
            // the right edge, taken at mid height, has been carried back by the finger's travel
            val edge = curl.point(d.pose, curl.width, 0f)
            near(curl.width - mx, edge.x, 0.05f, "edge for $mx")
            near(mx / curl.width, d.progress)
        }
        assertEquals(0f, curl.drag(1, 0f, -50f, 0f).progress, "coming back past the start counts as zero")
    }

    @Test
    fun bringingASheetBackTheRollFollowsTheFinger() {
        val start = curl.drag(-1, 0f, 0f, 0f)
        near(-1.6f * r, start.pose.fx)
        assertEquals(0f, start.progress)
        val mid = curl.drag(-1, 0f, 200f, 0f)
        near(200f - 1.6f * r, mid.pose.fx)
        near(200f / curl.span, mid.progress)
        val end = curl.drag(-1, 0f, 2000f, 0f)
        near(curl.width, end.pose.fx)
        assertEquals(1f, end.progress)
    }

    // ----- release -----

    @Test
    fun theTurnCompletesOnceTheFreeEdgeCrossedTheMiddle() {
        assertTrue(SheetCurl.commits(0.55f, 0f, false))
        assertTrue(SheetCurl.commits(0.9f, -0.1f, false))
        assertFalse(SheetCurl.commits(0.45f, 0f, false))
    }

    @Test
    fun aFlickInTheDirectionOfTheTurnCompletesIt() {
        assertTrue(SheetCurl.commits(0.1f, 0.36f, false))
        assertFalse(SheetCurl.commits(0.1f, 0.34f, false))
    }

    @Test
    fun theSheetGoesBackWhenNeitherHoldsOrWhenTheFingerMovedBackQuickly() {
        assertFalse(SheetCurl.commits(0.3f, 0.2f, false))
        assertFalse(SheetCurl.commits(0.8f, -0.16f, false))
        assertTrue(SheetCurl.commits(0.8f, -0.14f, false))
        assertFalse(SheetCurl.commits(0.01f, 1f, false), "a sheet barely lifted stays")
    }

    @Test
    fun aCancelledGestureAlwaysGoesBack() {
        assertFalse(SheetCurl.commits(0.9f, 1f, true))
        assertFalse(SheetCurl.commits(0.6f, 0f, true))
    }

    @Test
    fun aReleasedSheetSettlesFlatOrGone() {
        near(curl.width, curl.restingPose(1, commit = false).fx)
        near(-1.6f * r, curl.restingPose(1, commit = true).fx)
        near(curl.width, curl.restingPose(-1, commit = true).fx)
        near(-1.6f * r, curl.restingPose(-1, commit = false).fx)
        val from = CurlPose(100f, 30f, 0.3f)
        val to = curl.restingPose(1, true)
        assertEquals(from, curl.settle(from, to, 0f))
        val done = curl.settle(from, to, 1f)
        near(to.fx, done.fx)
        assertEquals(0f, done.a)
        assertEquals(160 + (460 * abs(to.fx - from.fx) / curl.span).toInt(), curl.settleMillis(from, to))
    }

    @Test
    fun theReleaseSpeedReadsTheLastInstants() {
        val speed = ReleaseSpeed()
        // the finger goes left 3 dp per ms
        (0..10).forEach { speed.add(300f - 30f * it, 1000L + 10L * it) }
        near(3f, speed.speed(1, 1105L))
        near(-3f, speed.speed(-1, 1105L))
        assertEquals(0f, speed.speed(1, 1300L), "a finger that stopped before lifting has no speed")
    }

    @Test
    fun theCurvesRunFromZeroToOne() {
        listOf(SheetCurl::easeInOut, SheetCurl::easeOut).forEach { f ->
            assertEquals(0f, f(0f))
            near(1f, f(1f))
        }
        near(0.5f, SheetCurl.easeInOut(0.5f))
    }

    @Test
    fun thePictureScaleCapsTheDensity() {
        assertEquals(1f, TurnSpec.pictureScale(1.5f))
        assertEquals(1f, TurnSpec.pictureScale(2f))
        near(2f / 3f, TurnSpec.pictureScale(3f))
    }
}
