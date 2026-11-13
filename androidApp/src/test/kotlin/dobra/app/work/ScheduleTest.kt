package dobra.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dobra.model.Cadence
import dobra.model.CadenceKind
import dobra.model.Magazine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ScheduleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var wm: WorkManager
    private lateinit var generation: Generation

    /** Thursday 13 November 2026, 08:30 in São Paulo. */
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
    }

    private fun live(id: String): List<WorkInfo> =
        wm.getWorkInfosForUniqueWork(Generation.nameOf(id)).get().filter { !it.state.isFinished }

    private fun jobOf(id: String): WorkInfo = live(id).single()

    /** A replaced or cancelled job either stays as cancelled or is pruned at once; either way it will not run. */
    private fun gone(id: java.util.UUID): Boolean = wm.getWorkInfoById(id).get()?.state?.let { it == WorkInfo.State.CANCELLED } ?: true

    @Test
    fun oneJobPerMagazineReplacedWhenTheCadenceOrHourChangesAndCancelledWhenTheMagazineIsDeleted() {
        generation.sync(listOf(daily, weekly))
        assertEquals(2, wm.getWorkInfosByTag(Generation.TAG).get().count { !it.state.isFinished })
        val first = jobOf("ia")
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(24L * 3600 * 1000, first.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(7L * 24 * 3600 * 1000, jobOf("livros").periodicityInfo!!.repeatIntervalMillis)

        // The same magazines again: the jobs are kept.
        generation.sync(listOf(daily, weekly))
        assertEquals(first.id, jobOf("ia").id)

        // The hour changes: the job is replaced.
        generation.sync(listOf(daily.copy(cadence = Cadence(CadenceKind.DAILY, 6)), weekly))
        val byHour = jobOf("ia")
        assertNotEquals(first.id, byHour.id)
        assertTrue(gone(first.id))

        // The cadence changes: replaced again, now weekly.
        generation.sync(listOf(daily.copy(cadence = Cadence(CadenceKind.WEEKLY, 6, weekday = 5)), weekly))
        val byCadence = jobOf("ia")
        assertNotEquals(byHour.id, byCadence.id)
        assertEquals(7L * 24 * 3600 * 1000, byCadence.periodicityInfo!!.repeatIntervalMillis)

        // The magazine is deleted: its job is cancelled, the other stays.
        val other = jobOf("livros")
        generation.sync(listOf(daily.copy(cadence = Cadence(CadenceKind.WEEKLY, 6, weekday = 5))))
        assertTrue(live("livros").isEmpty())
        assertTrue(gone(other.id))
        assertEquals(1, wm.getWorkInfosByTag(Generation.TAG).get().count { !it.state.isFinished })
    }

    @Test
    fun theJobsConstraintsFollowTheTwoOptionsOfTheScreen() {
        generation.sync(listOf(daily))
        jobOf("ia").constraints.let {
            assertEquals(NetworkType.CONNECTED, it.requiredNetworkType)
            assertFalse(it.requiresCharging())
        }
        generation.setOptions(GenerationOptions(wifiOnly = true, chargingOnly = true))
        jobOf("ia").constraints.let {
            assertEquals(NetworkType.UNMETERED, it.requiredNetworkType)
            assertTrue(it.requiresCharging())
        }
        // The options are kept for the next start of the app.
        assertEquals(GenerationOptions(true, true), Generation(context, { wm }, { now }).options.value)
        generation.setOptions(GenerationOptions(wifiOnly = false, chargingOnly = true))
        assertEquals(NetworkType.CONNECTED, jobOf("ia").constraints.requiredNetworkType)
    }

    @Test
    fun theFirstRunWaitsForTheCadencesHour() {
        // 08:30 is past 07:00, so the daily edition is due tomorrow at 07:00; the weekly one on Monday at 09:00.
        assertEquals(now.plusDays(1).withHour(7).withMinute(0), generation.nextDue(daily))
        assertEquals(ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, now.zone), generation.nextDue(weekly))
        // Before the hour on the same day, it is due today.
        assertEquals(now.withHour(9).withMinute(0), Generation.nextAt(Cadence(CadenceKind.DAILY, 9), now))
    }
}
