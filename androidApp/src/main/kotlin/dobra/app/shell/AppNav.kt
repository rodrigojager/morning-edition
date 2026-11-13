package dobra.app.shell

import androidx.compose.runtime.Composable

/** Route names of the app. Each feature adds its route here. */
object Routes {
    const val START = "start"
}

/** Shows the screen of the current route. Today there is only the start screen. */
@Composable
fun AppNav(@Suppress("UNUSED_PARAMETER") container: AppContainer) {
    StartScreen()
}
