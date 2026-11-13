package dobra.app.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** The single activity: builds the [AppContainer] once and hands it to [AppNav]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer()
        setContent {
            AppTheme {
                AppNav(container)
            }
        }
    }
}
