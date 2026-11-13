package dobra.reader.turn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A pose of the book's page: [t] how far it has swung over the fold (0: lying on the right, 1: landed on the left) and
 * [lead] its twist. With `lead > 0` the bottom edge is further along than the top, which is what happens when the page
 * is taken by a bottom corner.
 */
data class BookPose(val t: Float, val lead: Float = 0f) : TurnPose

/**
 * The geometry of the book turn on a screen [width] by [height] (density-independent pixels) whose fold is [hinge]
 * from the left edge, ported from the prototype's `turn.js` (`poseBook`, `bookDrag`, `dragEnd`): the right page is a
 * strip hinged on the fold that bends as it swings over, its top and bottom rows at slightly different angles for the
 * twist. Pure functions: they return vertex positions and shades, the drawing is the platform's.
 */
class BookTurn(val width: Float, val height: Float, val hinge: Float = width / 2f) : TurnMotion<BookPose> {
    /** The width of the turning page: from the fold to the right edge. */
    val sheetWidth: Float = width - hinge

    override val playMs: Int get() = TurnSpec.BOOK_PLAY_MS

    override fun at(t: Float): BookPose = BookPose(t, 0f)

    /** A strip for this screen, its texture coordinates set (see [BookStrip]). */
    fun strip(segments: Int = TurnSpec.BOOK_SEGMENTS): BookStrip = BookStrip(width, hinge, segments)

    /** Fills [strip] with the page in pose [p] and sets its shadow. */
    fun pose(p: BookPose, strip: BookStrip) {
        val pi = PI.toFloat()
        val s = sin(pi * p.t)
        val bend = 0.85f * s
        val seg = strip.segments
        val ds = sheetWidth / seg
        val h = height / 2f
        val tt = (pi * p.t - p.lead * s).coerceIn(0f, pi)
        val tb = (pi * p.t + p.lead * s).coerceIn(0f, pi)
        var xt = hinge
        var zt = 0f
        var xb = hinge
        var zb = 0f
        var edge = hinge
        val pos = strip.positions
        for (i in 0..seg) {
            val w = bend * (2f * i / seg - 1f)
            val pt = tt + w
            val pb = tb + w
            val top = i * 2
            val bottom = top + 1
            pos[top * 3] = xt
            pos[top * 3 + 1] = h
            pos[top * 3 + 2] = zt
            pos[bottom * 3] = xb
            pos[bottom * 3 + 1] = -h
            pos[bottom * 3 + 2] = zb
            strip.shade[top] = 0.68f + 0.32f * abs(cos(pt))
            strip.shade[bottom] = 0.68f + 0.32f * abs(cos(pb))
            edge = (xt + xb) / 2f
            xt += cos(pt) * ds
            zt += sin(pt) * ds
            xb += cos(pb) * ds
            zb += sin(pb) * ds
        }
        val lo = min(edge, hinge) - 30f
        val hi = max(edge, hinge) + 30f
        strip.shadow = SheetShadow((lo + hi) / 2f, 0f, 0f, hi - lo, height, 0.3f * s)
    }

    /**
     * The pose under a finger: the free edge of the page keeps the distance the finger has travelled (the page laid
     * straight, its edge at `hinge + sheetWidth * cos(PI * t)`, is [mx] from where it started), forward and back. The
     * twist comes from the direction of the drag and from how high or low the page was taken.
     */
    override fun drag(dir: Int, y: Float, mx: Float, my: Float): DragPose<BookPose> {
        val c = (mx / sheetWidth).coerceIn(0f, 2f)
        val h = height / 2f
        val pi = PI.toFloat()
        val t = if (dir > 0) acos(1f - c) / pi else acos(c - 1f) / pi
        val progress = if (dir > 0) t else 1f - t
        val lead = dir * (0.5f * atan2(-my, max(mx, 1f)) - 0.3f * y / h).coerceIn(-0.35f, 0.35f) *
            min(1f, max(0f, mx) / (0.3f * sheetWidth))
        return DragPose(BookPose(t, lead), progress)
    }

    override fun restingPose(dir: Int, commit: Boolean): BookPose = BookPose(if (commit == dir > 0) 1f else 0f, 0f)

    override fun settleMillis(from: BookPose, to: BookPose): Int = (160f + 460f * abs(to.t - from.t)).toInt()

    /** Straight on to the resting side, losing the twist on the way. */
    override fun settle(from: BookPose, to: BookPose, k: Float): BookPose =
        BookPose(from.t + (to.t - from.t) * k, from.lead + (to.lead - from.lead) * k)
}

/**
 * The book's turning page as a strip of [segments] cells from the fold to the free edge, two rows of vertices: vertex
 * `2 * i` on the top edge, `2 * i + 1` on the bottom edge, column i from the fold. Positions are x from the left edge
 * of the screen, y from mid-height (up positive) and z towards the eye; [shade] is the light on the page (both faces).
 *
 * Both faces take their picture from a whole screen [width] wide whose fold is at [hinge]: the printed face shows the
 * part right of the fold ([frontUv], the right page of the screen being left), the back shows the part left of the
 * fold, mirrored about it ([backUv], the left page of the screen reached). v runs from the top of the picture.
 */
class BookStrip(width: Float, hinge: Float, val segments: Int = TurnSpec.BOOK_SEGMENTS) {
    val vertexCount: Int = (segments + 1) * 2
    val positions: FloatArray = FloatArray(vertexCount * 3)
    val shade: FloatArray = FloatArray(vertexCount)
    val frontUv: FloatArray = FloatArray(vertexCount * 2)
    val backUv: FloatArray = FloatArray(vertexCount * 2)

    init {
        val sheet = width - hinge
        for (i in 0..segments) {
            val f = i.toFloat() / segments
            val uf = (hinge + f * sheet) / width
            val ub = (hinge - f * sheet) / width
            for ((n, v) in listOf(i * 2 to 0f, i * 2 + 1 to 1f)) {
                frontUv[n * 2] = uf
                frontUv[n * 2 + 1] = v
                backUv[n * 2] = ub
                backUv[n * 2 + 1] = v
            }
        }
    }

    /** Two triangles per cell, counter-clockwise seen from the printed face. */
    val indices: ShortArray = ShortArray(segments * 6).also { idx ->
        var k = 0
        for (i in 0 until segments) {
            val a = i * 2
            for (v in intArrayOf(a, a + 1, a + 2, a + 1, a + 3, a + 2)) idx[k++] = v.toShort()
        }
    }

    var shadow: SheetShadow = SheetShadow(0f, 0f, 0f, 0f, 0f, 0f)
}
