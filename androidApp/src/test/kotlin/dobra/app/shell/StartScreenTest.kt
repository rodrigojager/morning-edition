package dobra.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real launcher activity under Robolectric and checks the first screen. */
@RunWith(AndroidJUnit4::class)
class StartScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstScreenShowsTheAppName() {
        compose.onNodeWithText("Primeira Dobra").assertIsDisplayed()
    }
}
