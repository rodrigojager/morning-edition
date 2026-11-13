package dobra.app.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dobra.app.work.EditionNotices

/**
 * The single activity: takes the process's [AppContainer], starts the background editions' schedule and hands the
 * container to [AppNav]. An "Edição pronta" notification starts it with the key of the edition to open.
 */
class MainActivity : ComponentActivity() {
    private var toOpen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer.of(applicationContext)
        container.startSchedules()
        if (savedInstanceState == null) toOpen = intent?.getStringExtra(EditionNotices.EXTRA_OPEN_EDITION)
        setContent {
            AppNav(container, open = toOpen, onOpened = { toOpen = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EditionNotices.EXTRA_OPEN_EDITION)?.let { toOpen = it }
    }
}
