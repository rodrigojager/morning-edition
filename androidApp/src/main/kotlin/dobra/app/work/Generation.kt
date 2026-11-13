package dobra.app.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dobra.model.Cadence
import dobra.model.CadenceKind
import dobra.model.Magazine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** The two conditions the reader allows background editions under. */
data class GenerationOptions(val wifiOnly: Boolean = false, val chargingOnly: Boolean = false)

/** How the last background run of a magazine went. */
enum class LastOutcome { MADE, NOTHING, MISSING_SETUP, FAILED }

data class LastRun(val at: String, val outcome: LastOutcome, val editionKey: String? = null, val detail: String = "")

/**
 * One periodic WorkManager job per magazine, on the magazine's cadence (daily or weekly, at its hour), under the
 * conditions the reader allows. [sync] is called with the current magazines whenever they change: a job is kept as long
 * as its cadence and conditions are the same, replaced when either changes, and cancelled when its magazine is gone.
 * The system may delay the job (battery saving, Doze); that is accepted.
 */
class Generation(
    context: Context,
    private val workManager: () -> WorkManager,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("generation", Context.MODE_PRIVATE)
    private val _options = MutableStateFlow(readOptions())
    val options: StateFlow<GenerationOptions> = _options.asStateFlow()
    private val _runs = MutableStateFlow(0)

    /** Changes every time a run is recorded, so screens can read [lastRun] again. */
    val runs: StateFlow<Int> = _runs.asStateFlow()
    private var known: List<Magazine> = emptyList()

    fun setOptions(options: GenerationOptions) {
        prefs.edit().putBoolean(WIFI, options.wifiOnly).putBoolean(CHARGING, options.chargingOnly).apply()
        _options.value = options
        sync(known)
    }

    /** Makes the scheduled jobs match [magazines]. */
    @Synchronized
    fun sync(magazines: List<Magazine>) {
        known = magazines
        val wm = workManager()
        val options = _options.value
        val wanted = magazines.associateBy { it.id }
        for (info in wm.getWorkInfosByTag(TAG).get()) {
            if (info.state.isFinished) continue
            val id = info.tags.firstOrNull { it.startsWith(MAGAZINE_TAG) }?.removePrefix(MAGAZINE_TAG) ?: continue
            if (id !in wanted) wm.cancelUniqueWork(nameOf(id))
        }
        for (magazine in magazines) {
            val signature = signatureOf(magazine.cadence, options)
            val current = wm.getWorkInfosForUniqueWork(nameOf(magazine.id)).get().firstOrNull { !it.state.isFinished }
            val same = current != null && (SIGNATURE_TAG + signature) in current.tags
            val period = if (magazine.cadence.kind == CadenceKind.WEEKLY) 7L else 1L
            val request = PeriodicWorkRequestBuilder<EditionWorker>(period, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(magazine.cadence).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(constraintsOf(options))
                .setInputData(Data.Builder().putString(EditionWorker.MAGAZINE, magazine.id).build())
                .addTag(TAG)
                .addTag(MAGAZINE_TAG + magazine.id)
                .addTag(SIGNATURE_TAG + signature)
                .build()
            val policy = if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            wm.enqueueUniquePeriodicWork(nameOf(magazine.id), policy, request)
        }
    }

    /** When the next edition of [magazine] is due (the system may run it later). */
    fun nextDue(magazine: Magazine): ZonedDateTime = nextAt(magazine.cadence, now())

    fun lastRun(magazineId: String): LastRun? {
        val raw = prefs.getString(LAST + magazineId, null) ?: return null
        val parts = raw.split('\u0001')
        if (parts.size < 4) return null
        val outcome = runCatching { LastOutcome.valueOf(parts[1]) }.getOrNull() ?: return null
        return LastRun(parts[0], outcome, parts[2].ifEmpty { null }, parts[3])
    }

    fun record(magazineId: String, run: LastRun) {
        val raw = listOf(run.at, run.outcome.name, run.editionKey.orEmpty(), run.detail.replace('\u0001', ' ')).joinToString("\u0001")
        prefs.edit().putString(LAST + magazineId, raw).apply()
        _runs.value += 1
    }

    fun stamp(): String = now().truncatedTo(ChronoUnit.MINUTES).toOffsetDateTime().toString()

    /** Whether the permission to notify was already asked once (it is asked only when first needed). */
    var notifyAsked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) { prefs.edit().putBoolean(ASKED, value).apply() }

    private fun readOptions() = GenerationOptions(prefs.getBoolean(WIFI, false), prefs.getBoolean(CHARGING, false))

    private fun delayUntil(cadence: Cadence): Duration {
        val from = now()
        return Duration.between(from, nextAt(cadence, from))
    }

    companion object {
        const val TAG = "edition-job"
        private const val MAGAZINE_TAG = "magazine:"
        private const val SIGNATURE_TAG = "cadence:"
        private const val WIFI = "wifi_only"
        private const val CHARGING = "charging_only"
        private const val LAST = "last:"
        private const val ASKED = "notify_asked"

        fun nameOf(magazineId: String) = "edition-$magazineId"

        fun constraintsOf(options: GenerationOptions): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (options.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(options.chargingOnly)
            .build()

        internal fun signatureOf(cadence: Cadence, options: GenerationOptions): String =
            "${cadence.kind.name.lowercase()}-${cadence.hour}-${cadence.weekday ?: 0}-" +
                "${if (options.wifiOnly) "wifi" else "any"}-${if (options.chargingOnly) "charging" else "battery"}"

        /** The next moment strictly after [from] at the cadence's hour (and weekday, ISO 1 = Monday, for weekly). */
        fun nextAt(cadence: Cadence, from: ZonedDateTime): ZonedDateTime {
            val hour = cadence.hour.coerceIn(0, 23)
            var at = from.truncatedTo(ChronoUnit.HOURS).withHour(hour)
            if (cadence.kind == CadenceKind.WEEKLY) {
                val day = DayOfWeek.of((cadence.weekday ?: 1).coerceIn(1, 7))
                at = at.with(TemporalAdjusters.nextOrSame(day))
                if (!at.isAfter(from)) at = at.plusWeeks(1)
            } else if (!at.isAfter(from)) {
                at = at.plusDays(1)
            }
            return at
        }
    }
}
