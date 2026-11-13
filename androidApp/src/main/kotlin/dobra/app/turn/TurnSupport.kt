package dobra.app.turn

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the reader may use the 3D page turn at all. The app leaves it on and lets [Turn3d] decide; a screen test
 * switches it off to see the slide.
 */
val LocalPageTurn3d = staticCompositionLocalOf { true }

/**
 * Whether this device can draw the 3D page turn: OpenGL ES 2 reported by the system and an EGL display that offers a
 * window configuration for it. Robolectric (and a device without that support) answers no, and the pages slide. A
 * turn that fails on the way (a context that cannot be made, a shader that does not compile) marks it broken for the
 * rest of the process, so the next turns slide at once.
 */
object Turn3d {
    @Volatile
    private var broken = false

    @Volatile
    private var probed: Boolean? = null

    /** A screen test's answer in place of the device's (null: ask the device). Only for tests that never play a turn:
     *  the GL view itself still cannot show without OpenGL. */
    @Volatile
    internal var assumeForTests: Boolean? = null

    fun available(context: Context): Boolean {
        assumeForTests?.let { return it }
        if (broken) return false
        return probed ?: probe(context).also { probed = it }
    }

    fun markBroken() {
        broken = true
    }

    private fun probe(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val es = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        es >= 0x20000 && eglOffersEs2()
    } catch (_: Throwable) {
        false
    }

    /** The default display can be initialized and has a configuration that renders OpenGL ES 2 into a window. The
     *  display is left initialized (initializing it again is harmless, terminating it could disturb other users). */
    private fun eglOffersEs2(): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY) ?: return false
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, TurnGl.configAttributes(depth = 16), 0, configs, 0, 1, count, 0)
        return ok && count[0] > 0 && configs[0] != null
    }
}
