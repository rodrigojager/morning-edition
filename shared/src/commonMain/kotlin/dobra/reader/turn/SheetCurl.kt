package dobra.reader.turn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A pose of the single sheet: everything past a slanted line wraps half a turn around a cylinder and then lies flat,
 * back side up, on top of the rest. The line passes through ([fx] from the left edge, [fy] from mid-height, up
 * positive) with slant [a] in radians; with `a > 0` the bottom of the sheet is further along than the top.
 */
data class CurlPose(val fx: Float, val fy: Float, val a: Float)

/** A pose under a finger and how far the turn has gone ([progress], 0 to 1), which the release rule reads. */
data class DragPose(val pose: CurlPose, val progress: Float)

/** One point of the sheet in a pose: where it is (x from the left edge, y from mid-height, z towards the eye) and the
 *  light on its printed face and on its blank back. */
data class SheetPoint(val x: Float, val y: Float, val z: Float, val front: Float, val back: Float)

/** The soft shadow the turned part casts: a band centred on the line, [width] across and [height] long. */
data class SheetShadow(val x: Float, val y: Float, val rotation: Float, val width: Float, val height: Float, val opacity: Float)

/**
 * The geometry of the single sheet turn of a page [width] by [height] (density-independent pixels), ported from the
 * prototype's `turn.js` (`curlAt`, `poseCurl`, `pastLine`, `curlDrag`, `dragEnd`). Pure functions: they return vertex
 * positions and shades, the drawing is the platform's.
 */
class SheetCurl(val width: Float, val height: Float) {
    /** The radius of the roll. */
    val radius: Float = (width * 0.085f).coerceIn(20f, 120f)

    /** How far the line travels in a whole turn: from the right edge to where the roll has left the screen. */
    val span: Float = width + 1.6f * radius

    private val halfTurn = PI.toFloat() * radius

    /** The pose a turn played by itself goes through at [t] (0: the sheet lies flat, 1: it has left the screen); the
     *  bottom corner lifts first. */
    fun curlAt(t: Float): CurlPose = CurlPose(width - t * span, 0f, 0.2f * sin(PI.toFloat() * t))

    /** Where the point ([x] from the left edge, [y] from mid-height) of the flat sheet is in pose [s], and its light. */
    fun point(s: CurlPose, x: Float, y: Float): SheetPoint {
        val ca = cos(s.a)
        val sa = sin(s.a)
        val dx = x - s.fx
        val dy = y - s.fy
        val u = dx * ca - dy * sa // distance past the line
        val v = dx * sa + dy * ca // position along it
        val off: Float
        val z: Float
        val front: Float
        val back: Float
        if (u <= 0f) { // still flat: full light, except the soft shadow the turned part casts just beyond its edge
            off = u
            z = 0f
            val beyond = s.fx + (halfTurn - u) * ca + v * sa - width
            front = if (beyond <= 0f) 0.86f else 0.86f + 0.14f * min(1f, beyond / (radius + 14f))
            back = front
        } else if (u < halfTurn) { // on the roll: the printed face dims as it turns away, the back brightens as it comes up
            val th = u / radius
            val c = cos(th)
            off = radius * sin(th)
            z = radius * (1f - c)
            front = 0.6f + 0.4f * max(0f, c)
            back = 0.62f + 0.35f * max(0f, -c)
        } else { // already turned: flat again, back side up, evenly lit
            off = halfTurn - u
            z = 2f * radius
            front = 0.6f
            back = 0.97f
        }
        return SheetPoint(s.fx + off * ca + v * sa, s.fy + v * ca - off * sa, z, front, back)
    }

    /** Fills [mesh] with the sheet in pose [s] and sets its shadow. */
    fun pose(s: CurlPose, mesh: SheetMesh) {
        val h = height / 2f
        val gx = mesh.gridX
        val gy = mesh.gridY
        for (i in 0..gx) {
            val x = width * i / gx
            for (j in 0..gy) {
                val n = i * (gy + 1) + j
                val p = point(s, x, -h + height * j / gy)
                mesh.positions[n * 3] = p.x
                mesh.positions[n * 3 + 1] = p.y
                mesh.positions[n * 3 + 2] = p.z
                mesh.front[n] = p.front
                mesh.back[n] = p.back
            }
        }
        mesh.shadow = shadow(s)
    }

    /** The shadow of pose [s]: none while the roll is off the screen, fading in as the leading corner lifts. */
    fun shadow(s: CurlPose): SheetShadow {
        val h = height / 2f
        val ca = cos(s.a)
        val sa = sin(s.a)
        val lifted = max(0f, (width - s.fx) * ca + max((s.fy - h) * sa, (s.fy + h) * sa)) // how far the leading corner is past the line
        val opacity = 0.34f * min(1f, lifted / radius) * ((s.fx + 1.6f * radius) / radius).coerceIn(0f, 1f)
        return SheetShadow(s.fx, s.fy, -s.a, 2f * (radius + 50f), height * 2f, opacity)
    }

    /** How far a point must be past the line for the roll to have carried it back by [moved]. */
    fun pastLine(moved: Float): Float {
        if (moved >= halfTurn) return (moved + halfTurn) / 2f
        var lo = 0f
        var hi = halfTurn
        repeat(22) {
            val mid = (lo + hi) / 2f
            if (mid - radius * sin(mid / radius) < moved) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    /** How far back the roll carries a point that is [past] the line: the travel [pastLine] inverts. */
    fun travel(past: Float): Float = when {
        past <= 0f -> 0f
        past < halfTurn -> past - radius * sin(past / radius)
        else -> 2f * past - halfTurn
    }

    /**
     * The pose under a finger. [dir] is 1 turning forward, -1 bringing the previous sheet back; [y] is where the finger
     * went down (from mid-height, up positive); [mx] how far it has gone in the direction of the turn and [my] how far
     * down the screen. Turning forward, the finger holds the free edge at the height where it touched and that point
     * follows it; the slant comes from the direction of the drag and from how high or low the sheet was taken.
     * Bringing a sheet back, the roll itself follows the finger.
     */
    fun drag(dir: Int, y: Float, mx: Float, my: Float): DragPose {
        val h = height / 2f
        val m = max(0f, mx)
        val grow = min(1f, m / (0.35f * width))
        if (dir > 0) {
            val a = (0.8f * atan2(-my, max(m, 1f)) - 0.25f * y / h).coerceIn(-0.5f, 0.5f) * grow
            val u0 = pastLine(m / cos(a))
            return DragPose(CurlPose(width - u0 * cos(a), y + u0 * sin(a), a), min(1f, m / width))
        }
        val fx = min(width, m - 1.6f * radius)
        val a = (0.8f * atan2(my, max(m, 1f)) + 0.25f * y / h).coerceIn(-0.5f, 0.5f) * grow *
            min(1f, (width - fx) / (0.3f * width))
        return DragPose(CurlPose(fx, y, a), min(1f, m / span))
    }

    /** Where a released turn of direction [dir] lands: flat on the screen, or gone past the left edge. */
    fun restingPose(dir: Int, commit: Boolean): CurlPose {
        val end = if (commit == dir > 0) 1f else 0f
        return curlAt(end)
    }

    /** How long the sheet takes from pose [from] to the resting pose [to] once let go, in milliseconds. */
    fun settleMillis(from: CurlPose, to: CurlPose): Int = (160f + 460f * abs(to.fx - from.fx) / span).toInt()

    /** The pose at [k] (0 to 1, eased) of the way from the pose under the finger to the resting pose: straight along,
     *  losing the slant. */
    fun settle(from: CurlPose, to: CurlPose, k: Float): CurlPose =
        CurlPose(from.fx + (to.fx - from.fx) * k, from.fy, from.a * (1f - k))

    companion object {
        /** A flick faster than this (dp per millisecond, in the direction of the turn) completes the turn. */
        const val FLICK_SPEED: Float = 0.35f

        /** A finger moving back faster than this (dp per millisecond) brings the sheet back, wherever it is. */
        const val BACK_SPEED: Float = 0.15f

        /**
         * The release rule: the sheet falls to the side it is leaning to. Once its free edge crossed the middle
         * ([progress] over one half) it completes, before that it goes back; a quick flick decides by its own
         * direction instead. [velocity] is in the direction of the turn; a [cancelled] gesture always goes back.
         */
        fun commits(progress: Float, velocity: Float, cancelled: Boolean): Boolean =
            !cancelled && progress > 0.02f && (velocity > FLICK_SPEED || (velocity > -BACK_SPEED && progress > 0.5f))

        /** The curve of a turn played by itself. */
        fun easeInOut(p: Float): Float =
            if (p < 0.5f) 4f * p * p * p else 1f - cube(-2f * p + 2f) / 2f

        /** The curve of a sheet let go. */
        fun easeOut(p: Float): Float = 1f - cube(1f - p)

        private fun cube(x: Float) = x * x * x
    }
}

/**
 * The single sheet as a grid of [gridX] by [gridY] cells: positions (x from the left edge, y from mid-height, z), the
 * light on each face per vertex, the texture coordinates (v from the top of the picture, as image rows go) and the
 * triangles. Vertex `n = i * (gridY + 1) + j`, column i from the left, row j from the bottom.
 */
class SheetMesh(val gridX: Int = TurnSpec.GRID_X, val gridY: Int = TurnSpec.GRID_Y) {
    val vertexCount: Int = (gridX + 1) * (gridY + 1)
    val positions: FloatArray = FloatArray(vertexCount * 3)
    val front: FloatArray = FloatArray(vertexCount)
    val back: FloatArray = FloatArray(vertexCount)
    val uv: FloatArray = FloatArray(vertexCount * 2).also { uv ->
        for (i in 0..gridX) for (j in 0..gridY) {
            val n = i * (gridY + 1) + j
            uv[n * 2] = i.toFloat() / gridX
            uv[n * 2 + 1] = 1f - j.toFloat() / gridY
        }
    }

    /** Two triangles per cell, counter-clockwise seen from the printed face. */
    val indices: ShortArray = ShortArray(gridX * gridY * 6).also { idx ->
        var k = 0
        for (i in 0 until gridX) for (j in 0 until gridY) {
            val bl = i * (gridY + 1) + j
            val tl = bl + 1
            val br = bl + gridY + 1
            val tr = br + 1
            for (v in intArrayOf(tl, bl, tr, bl, br, tr)) idx[k++] = v.toShort()
        }
    }

    var shadow: SheetShadow = SheetShadow(0f, 0f, 0f, 0f, 0f, 0f)
}

/**
 * The finger's speed over the last instants, in the direction of the turn, as the release rule wants it; a finger that
 * stopped before lifting has none.
 */
class ReleaseSpeed(private val windowMs: Long = 120) {
    private val xs = ArrayList<Float>()
    private val ts = ArrayList<Long>()

    /** The finger is at [x] (dp) at [timeMs]. */
    fun add(x: Float, timeMs: Long) {
        xs.add(x)
        ts.add(timeMs)
        while (xs.size > 2 && timeMs - ts[0] > windowMs) {
            xs.removeAt(0)
            ts.removeAt(0)
        }
    }

    /** dp per millisecond in direction [dir] (1: towards the left), when the finger lifts at [nowMs]. */
    fun speed(dir: Int, nowMs: Long): Float {
        if (xs.size < 2) return 0f
        val last = xs.size - 1
        if (nowMs - ts[last] >= windowMs) return 0f
        return (xs[0] - xs[last]) * dir / max(1L, ts[last] - ts[0]).toFloat()
    }
}
