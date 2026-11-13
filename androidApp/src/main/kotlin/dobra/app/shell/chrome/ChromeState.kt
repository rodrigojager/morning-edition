package dobra.app.shell.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dobra.store.Controls
import kotlinx.coroutines.delay

/** How long the controls stay after they appear, or after the last touch of them, in the hiding mode. */
const val CHROME_HIDE_MS = 4000L

/**
 * Whether the controls (top bar, pager, tab bar) are on screen. With fixed controls they always are; in the hiding
 * mode ([auto]) they leave [CHROME_HIDE_MS] after they appear and come back with a tap on the page, a scroll up or
 * the handle. In the hiding mode the page takes the whole screen and the bars are drawn over it.
 */
class ChromeState {
    /** True in the hiding mode. */
    var auto by mutableStateOf(false)
        private set

    private var shown by mutableStateOf(true)
    internal var timerNonce by mutableIntStateOf(0)
        private set

    /** The height in pixels of the tab bar while it is drawn over the page, so the pager can sit above it. */
    var tabBarPx by mutableIntStateOf(0)

    val visible: Boolean get() = !auto || shown

    internal fun setAuto(value: Boolean) {
        if (value != auto) {
            auto = value
            shown = true
            timerNonce++
        }
    }

    /** Shows the controls and restarts the countdown. */
    fun show() {
        shown = true
        timerNonce++
    }

    fun hide() {
        if (auto) shown = false
    }

    /** A tap on the page. */
    fun toggle() {
        if (!auto) return
        if (shown) hide() else show()
    }

    /** Restarts the countdown when the controls are on screen (any use of them). */
    fun touch() {
        if (auto && shown) timerNonce++
    }

    internal fun autoHides(): Boolean = auto && shown
}

/** The state of the controls for the stored [controls] choice, with its countdown running. */
@Composable
fun rememberChromeState(controls: Controls): ChromeState {
    val state = remember { ChromeState().also { it.setAuto(controls == Controls.HIDDEN) } }
    SideEffect { state.setAuto(controls == Controls.HIDDEN) }
    LaunchedEffect(state.auto, state.visible, state.timerNonce) {
        if (state.autoHides()) {
            delay(CHROME_HIDE_MS)
            state.hide()
        }
    }
    return state
}
