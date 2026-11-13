package dobra.app.work

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dobra.app.settings.SettingsScreen
import dobra.app.shell.AppTheme
import dobra.model.Cadence
import dobra.model.CadenceKind
import dobra.model.Magazine
import dobra.store.DeviceSettings
import dobra.store.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class GenerationScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var wm: WorkManager
    private lateinit var generation: Generation

    /** Thursday 13 November 2026, 08:30. */
    private val now = ZonedDateTime.of(2026, 11, 13, 8, 30, 0, 0, ZoneId.of("America/Sao_Paulo"))
    private val daily = Magazine("ia", "Primeira Dobra", cadence = Cadence(CadenceKind.DAILY, 7))
    private val weekly = Magazine("livros", "Estante", cadence = Cadence(CadenceKind.WEEKLY, 9, weekday = 1))

    @Before
    fun setUp() {
        context.getSharedPreferences("generation", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
        generation = Generation(context, { wm }, { now })
        generation.sync(listOf(daily, weekly))
    }

    private fun tag(t: String) = compose.onNodeWithTag(t)
    private fun waitFor(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun theSettingsRouteShowsTheTwoOptionsAndPerMagazineTheNextEditionAndTheLastRun() {
        generation.record("ia", LastRun("2026-11-13T07:02-03:00", LastOutcome.MADE, "ia/2026-11-13"))
        generation.record("livros", LastRun("2026-11-10T09:01-03:00", LastOutcome.FAILED, detail = "sem rede"))
        generation.notifyAsked = true
        compose.setContent {
            AppTheme(Theme.PAPER, 1f) {
                var settings by androidx.compose.runtime.remember { mutableStateOf(DeviceSettings()) }
                SettingsScreen(settings, { settings = it }, generation = generation, magazines = listOf(daily, weekly))
            }
        }
        tag("settings-generation").performScrollTo().performClick()
        waitFor("screen-generation")

        tag("generation-wifi").assertIsOff()
        tag("generation-charging").assertIsOff()
        tag("generation-next-ia").assertTextEquals("Próxima edição: 14/11 às 07:00")
        tag("generation-next-livros").assertTextEquals("Próxima edição: 16/11 às 09:00")
        tag("generation-last-ia").assertTextEquals("Última: 13/11 às 07:02 · edição pronta")
        tag("generation-last-livros").assertTextEquals("Última: 10/11 às 09:01 · falhou (sem rede)")

        tag("generation-wifi").performScrollTo().performClick()
        tag("generation-charging").performScrollTo().performClick()
        tag("generation-wifi").assertIsOn()
        tag("generation-charging").assertIsOn()
        val job = wm.getWorkInfosForUniqueWork(Generation.nameOf("ia")).get().single { !it.state.isFinished }
        assertEquals(NetworkType.UNMETERED, job.constraints.requiredNetworkType)
        assertTrue(job.constraints.requiresCharging())

        // A new run is shown as soon as it is recorded.
        generation.record("ia", LastRun("2026-11-14T07:03-03:00", LastOutcome.NOTHING))
        compose.waitUntil(5_000) {
            runCatching { tag("generation-last-ia").assertTextEquals("Última: 14/11 às 07:03 · nada novo para publicar") }.isSuccess
        }
    }

    @Test
    fun thePermissionToNotifyIsAskedOnlyTheFirstTimeItIsNeeded() {
        var asks = 0
        var shown by mutableStateOf(true)
        compose.setContent {
            AppTheme(Theme.PAPER, 1f) {
                if (shown) GenerationScreen(generation, listOf(daily), onBack = {}, askNotify = { asks++ })
            }
        }
        waitFor("screen-generation")
        compose.waitUntil(5_000) { asks == 1 }

        // Leaving and opening the screen again does not ask again.
        shown = false
        compose.waitForIdle()
        shown = true
        waitFor("screen-generation")
        compose.waitForIdle()
        assertEquals(1, asks)
        assertTrue(generation.notifyAsked)
    }

    @Test
    fun withThePermissionAlreadyGrantedNothingIsAsked() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var asks = 0
        compose.setContent {
            AppTheme(Theme.PAPER, 1f) { GenerationScreen(generation, listOf(daily), onBack = {}, askNotify = { asks++ }) }
        }
        waitFor("screen-generation")
        compose.waitForIdle()
        assertEquals(0, asks)
    }
}
