package dobra.app.turn

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import dobra.reader.turn.CurlPose
import dobra.reader.turn.ReleaseSpeed
import dobra.reader.turn.SheetCurl
import dobra.reader.turn.TurnSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** The page the turn plays on: its size in density-independent pixels and the colour of its paper. */
internal class TurnPage(val widthDp: Float, val heightDp: Float, val paper: Int)

/** How a finger let go: its speed in the direction of the turn (dp per ms), or the gesture was aborted. */
internal class Release(val velocity: Float, val cancelled: Boolean)

/**
 * A finger turning a sheet, fed by the gesture and read by the turn. [dir] is 1 turning forward (the finger goes left),
 * -1 bringing the previous sheet back; [yShare] is where the finger went down, as a share of the page height from the
 * top. Positions are in pixels; [density] converts them to the geometry's unit.
 */
internal class DragInput(val dir: Int, val yShare: Float, private val density: Float, private val x0: Float, private val y0: Float, private val downX: Float) {
    /** How far the finger went in the direction of the turn and down the screen since the drag started (dp). */
    var along = 0f
        private set
    var down = 0f
        private set

    /** How far the finger went in the direction of the turn since it touched (pixels): the swipe rule reads it. */
    var travelPx = 0f
        private set

    private val speed = ReleaseSpeed()
    val released = CompletableDeferred<Release>()

    fun move(x: Float, y: Float, timeMs: Long) {
        along = (x0 - x) * dir / density
        down = (y - y0) / density
        travelPx = (downX - x) * dir
        speed.add(x / density, timeMs)
    }

    fun release(timeMs: Long) {
        released.complete(Release(speed.speed(dir, timeMs), cancelled = false))
    }

    fun cancel() {
        released.complete(Release(0f, cancelled = true))
    }
}

/**
 * The 3D turn of the single sheet over screens identified by keys [K]. [play] turns a sheet by itself (a button),
 * [drag] under a finger. Both make the pictures of the two screens, show the GL view over the pager, change the
 * screen under it once the view shows the first frame, and take the view away once the screen under it matches the
 * last frame. When something is missing (a picture, the GL view) they report it and the caller slides.
 *
 * The screen changes the turn makes itself (a drag) are announced with a claim, so the caller does not play them again.
 */
@Stable
internal class SheetTurn<K>(private val density: Float) {
    val pictures = PagePictures<K>()

    /** A turn is running: further steps wait until it ends. */
    var busy by mutableStateOf(false)
        private set

    /** The screen composed out of sight for its picture, while a turn prepares. */
    var preparing by mutableStateOf<K?>(null)
        private set

    internal var overlay by mutableStateOf<Overlay?>(null)
        private set

    /** The screen the caller shows now, kept up to date by the caller. */
    var shown: K? = null

    private var claim: Pair<K, Boolean>? = null
    private var gl: TurnGl? = null

    /** When the change to [key] was made by the turn itself: whether it should slide (the drag fell back to a swipe). */
    fun takeClaim(key: K): Boolean? {
        val c = claim?.takeIf { it.first == key } ?: return null
        claim = null
        return c.second
    }

    /** Turns from screen [from] to [to] in direction [dir] by itself; [swap] changes the screen under the turn.
     *  False when the turn could not be shown (the caller slides). */
    suspend fun play(from: K, to: K, dir: Int, page: TurnPage, swap: () -> Unit): Boolean {
        if (busy) return false
        busy = true
        try {
            val curl = SheetCurl(page.widthDp, page.heightDp)
            val t0 = if (dir > 0) 0f else 1f
            val gl = open(from, to, dir, page, curl.curlAt(t0)) ?: return false
            swap()
            animate(TurnSpec.PLAY_MS) { k -> gl.pose(curl.curlAt(t0 + (1f - 2f * t0) * SheetCurl.easeInOut(k))) }
            frames(2)
            return true
        } finally {
            close()
        }
    }

    /**
     * Turns under a finger from [from] to [to]: the sheet follows [input] until it is let go, then completes or goes
     * back by the release rule. [step] moves the screen by one in a direction. Without the 3D view the drag is a swipe:
     * once [swipePx] long when let go, it steps with a slide.
     */
    suspend fun drag(from: K, to: K, input: DragInput, page: TurnPage, swipePx: Float, step: (Int) -> Unit) {
        busy = true
        try {
            val dir = input.dir
            val curl = SheetCurl(page.widthDp, page.heightDp)
            val y = (0.5f - input.yShare) * page.heightDp
            val gl = open(from, to, dir, page, curl.curlAt(if (dir > 0) 0f else 1f))
            if (gl == null) {
                val r = input.released.await()
                if (!r.cancelled && input.travelPx >= swipePx) {
                    claim = to to true
                    step(dir)
                }
                return
            }
            claim = to to false
            step(dir)
            var last = curl.drag(dir, y, input.along, input.down)
            gl.pose(last.pose)
            while (!input.released.isCompleted) {
                withFrameNanos { }
                last = curl.drag(dir, y, input.along, input.down)
                gl.pose(last.pose)
            }
            val r = input.released.await()
            val commit = SheetCurl.commits(last.progress, r.velocity, r.cancelled)
            val rest = curl.restingPose(dir, commit)
            val held = last.pose
            animate(curl.settleMillis(held, rest)) { k -> gl.pose(curl.settle(held, rest, SheetCurl.easeOut(k))) }
            if (!commit) {
                claim = from to false
                step(-dir)
                for (n in 0 until 30) {
                    if (shown == from) break
                    withFrameNanos { }
                }
            }
            frames(2)
        } finally {
            close()
        }
    }

    fun dispose() {
        overlay = null
        gl?.close()
        gl = null
    }

    /** Makes the two pictures and shows the GL view at [first]; null when either step failed. */
    private suspend fun open(from: K, to: K, dir: Int, page: TurnPage, first: CurlPose): TurnGl? {
        val scale = TurnSpec.pictureScale(density)
        preparing = to
        val fromPicture = pictures.take(from, scale) ?: return null
        val toPicture = pictures.take(to, scale)
        preparing = null
        if (toPicture == null) {
            fromPicture.recycle()
            return null
        }
        // the turn is always "a to b", played forwards or backwards: a is printed on the sheet, b lies under it
        val scene = if (dir > 0) TurnScene(toPicture, fromPicture, page.paper, page.widthDp, page.heightDp)
        else TurnScene(fromPicture, toPicture, page.paper, page.widthDp, page.heightDp)
        val gl = gl ?: TurnGl().also { gl = it }
        val shown = CompletableDeferred<Boolean>()
        overlay = Overlay(gl, scene, first, shown)
        val ok = withTimeoutOrNull(OPEN_TIMEOUT_MS) { shown.await() } ?: false
        if (!ok) {
            overlay = null
            return null
        }
        frames(1) // the view composites its first frame before the screen under it changes
        return gl
    }

    private fun close() {
        overlay = null
        preparing = null
        busy = false
    }

    private suspend fun frames(n: Int) = repeat(n) { withFrameNanos { } }

    /** Calls [draw] on every frame for [ms] milliseconds with the share of the time gone, ending at exactly 1. */
    private suspend fun animate(ms: Int, draw: (Float) -> Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val p = ((now - start) / 1_000_000f / ms.coerceAtLeast(1)).coerceAtMost(1f)
            draw(p)
            if (p >= 1f) break
        }
    }

    /** One showing of the GL view: it reports through [shown] once its first frame arrived (or that it failed). */
    internal class Overlay(val gl: TurnGl, val scene: TurnScene, val first: CurlPose, val shown: CompletableDeferred<Boolean>) {
        private var attached = false
        private var frameSeen = false

        fun listener() = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                gl.attach(texture, width, height, scene, first) { ok ->
                    if (!ok) shown.complete(false)
                    attached = true
                    if (frameSeen) shown.complete(true)
                }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                gl.resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                gl.detach()
                shown.complete(false)
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                frameSeen = true
                if (attached) shown.complete(true)
            }
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 1500L
    }
}

/**
 * The turn of the single sheet when this device can draw it in 3D and the reader allows it ([LocalPageTurn3d]); null
 * otherwise, and the pages slide. [enabled] is false outside the single sheet or with animations off.
 */
@Composable
internal fun <K> rememberSheetTurn(enabled: Boolean): SheetTurn<K>? {
    val context = LocalContext.current
    val allowed = LocalPageTurn3d.current
    val density = LocalDensity.current.density
    if (!(enabled && allowed && Turn3d.available(context))) return null
    val turn = remember(density) { SheetTurn<K>(density) }
    DisposableEffect(turn) { onDispose { turn.dispose() } }
    return turn
}

/** The GL view of a running turn, exactly over the pager; nothing between turns. */
@Composable
internal fun SheetTurnOverlay(turn: SheetTurn<*>) {
    val overlay = turn.overlay ?: return
    key(overlay) {
        AndroidView(
            factory = { context -> TextureView(context).apply { surfaceTextureListener = overlay.listener() } },
            modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
        )
    }
}
