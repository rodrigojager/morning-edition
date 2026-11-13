package dobra.app.turn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurnSupportTest {
    @Test
    fun robolectricCannotDrawTheTurnSoThePagesSlide() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(Turn3d.available(context))
    }

    @Test
    fun theDragReadsTheFingerInTheGeometrysUnit() {
        // density 2: 2 pixels per dp; the drag started at x=600 after the finger touched at x=620
        val input = DragInput(dir = 1, yShare = 0.8f, density = 2f, x0 = 600f, y0 = 300f, downX = 620f)
        input.move(400f, 340f, 1000L)
        assertEquals(100f, input.along)
        assertEquals(20f, input.down)
        assertEquals(220f, input.travelPx)

        val back = DragInput(dir = -1, yShare = 0.5f, density = 2f, x0 = 100f, y0 = 0f, downX = 80f)
        back.move(300f, 0f, 1000L)
        assertEquals(100f, back.along)
        assertEquals(220f, back.travelPx)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLiftAfterAQuickMoveIsAFlickAndAnAbortedGestureIsACancel() {
        val input = DragInput(dir = 1, yShare = 0.5f, density = 1f, x0 = 300f, y0 = 0f, downX = 300f)
        (0..10).forEach { input.move(300f - 6f * it, 0f, 1000L + 10L * it) }
        input.release(1105L)
        val r = input.released.getCompleted()
        assertEquals(0.6f, r.velocity, 1e-4f)
        assertFalse(r.cancelled)

        val aborted = DragInput(dir = 1, yShare = 0.5f, density = 1f, x0 = 0f, y0 = 0f, downX = 0f)
        aborted.cancel()
        assertTrue(aborted.released.getCompleted().cancelled)
    }
}
