package dobra.app.turn

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dobra.reader.turn.TurnPose
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The thread that draws the page turn: one EGL context kept for the life of the reader (so a turn does not pay for
 * it again), and a window surface for each turn's view. The main thread hands it a pose; it draws the latest one.
 * Any failure marks the 3D turn broken ([Turn3d.markBroken]) and is reported, so the caller slides instead.
 */
internal class TurnGl {
    private val thread = HandlerThread("page-turn").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // GL thread only
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer: SheetRenderer? = null
    private var failed = false

    private val latest = AtomicReference<TurnPose?>(null)
    private val scheduled = AtomicBoolean(false)
    private val frame = Runnable {
        scheduled.set(false)
        latest.get()?.let { render(it) }
    }

    /**
     * Starts drawing into [texture] ([width] by [height] pixels) with the pictures of [scene], showing [first]. Calls
     * [onResult] on the main thread with false when the context, the surface or the pictures could not be set up.
     */
    fun attach(texture: SurfaceTexture, width: Int, height: Int, scene: TurnScene, first: TurnPose, onResult: (Boolean) -> Unit) {
        handler.post {
            val ok = try {
                ensureContext()
                releaseSurface()
                surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE) { "no window surface" }
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "cannot make the context current" }
                val r = renderer ?: SheetRenderer().also { it.init(); renderer = it }
                r.load(scene)
                r.viewport(width, height)
                latest.set(first)
                render(first)
                !failed
            } catch (_: Throwable) {
                fail()
                false
            }
            main.post { onResult(ok) }
        }
    }

    fun resize(width: Int, height: Int) {
        handler.post { renderer?.viewport(width, height) }
        latest.get()?.let { pose(it) }
    }

    /** Draws [pose] on the next turn of the GL thread; poses that arrive meanwhile replace it. */
    fun pose(pose: TurnPose) {
        latest.set(pose)
        if (scheduled.compareAndSet(false, true)) handler.post(frame)
    }

    /** Stops drawing into the current view's surface; returns once the GL thread let go of it. */
    fun detach() {
        latest.set(null)
        val done = CountDownLatch(1)
        handler.post {
            releaseSurface()
            done.countDown()
        }
        done.await(500, TimeUnit.MILLISECONDS)
    }

    /** Frees the context and ends the thread. */
    fun close() {
        latest.set(null)
        handler.post {
            try {
                releaseSurface()
                if (context != EGL14.EGL_NO_CONTEXT) {
                    renderer?.let { r ->
                        // the program and textures belong to the context: free them while it is still usable
                        runCatching {
                            val pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                            if (EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) r.release()
                            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                            EGL14.eglDestroySurface(display, pbuffer)
                        }
                    }
                    EGL14.eglDestroyContext(display, context)
                }
            } catch (_: Throwable) {
            }
            renderer = null
            context = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    private fun render(pose: TurnPose) {
        if (failed || surface == EGL14.EGL_NO_SURFACE) return
        try {
            renderer?.draw(pose)
            EGL14.eglSwapBuffers(display, surface)
        } catch (_: Throwable) {
            fail()
        }
    }

    private fun ensureContext() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "display not initialized" }
        config = choose(24) ?: choose(16) ?: error("no configuration")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "no context" }
    }

    private fun choose(depth: Int): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, configAttributes(depth), 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun releaseSurface() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
    }

    private fun fail() {
        failed = true
        Turn3d.markBroken()
    }

    companion object {
        /** A window configuration with 8 bits per colour, the given depth buffer, rendering OpenGL ES 2. */
        fun configAttributes(depth: Int): IntArray = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, depth,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
    }
}
