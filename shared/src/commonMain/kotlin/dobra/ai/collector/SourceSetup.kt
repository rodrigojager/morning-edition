package dobra.ai.collector

import dobra.ai.provider.AiJob
import dobra.ai.provider.ProviderRegistry
import dobra.collect.engine.FetchTransport
import dobra.collect.health.HealthRecord
import dobra.collect.health.HealthStore
import dobra.collect.health.SourceBook
import dobra.make.MissingSetup
import dobra.model.CollectorInfo
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth

/** What asking the AI for a source's collector came to. */
sealed interface SetupOutcome {
    /** The collector was accepted and the source is working. [suggested] are the AI's keywords not in the recipe yet. */
    data class Ready(val source: Source, val suggested: List<String>) : SetupOutcome

    /** No collector passed the acceptance run (or the page could not be read); the source keeps what it had. */
    data class NotReady(val source: Source, val reason: String) : SetupOutcome

    /** The site refuses automated reading: the source is now unavailable. */
    data class Unavailable(val source: Source, val reason: String) : SetupOutcome

    /** The magazine or the source no longer exists. */
    data object Gone : SetupOutcome
}

/** The collector work the screens ask for; [SourceSetup] is the real one. */
interface CollectorWork {
    /** A source was added (or has no collector yet): saves a copy of its page and asks the AI for a collector. */
    suspend fun write(magazineId: String, sourceId: String): SetupOutcome

    /** A source is being repaired: saves a fresh copy and asks the AI to adapt the collector to it. */
    suspend fun repair(magazineId: String, sourceId: String, reason: String): SetupOutcome

    /** Adds to the recipe only those of [chosen] that were among the [offered] suggestions. */
    suspend fun keepKeywords(magazineId: String, offered: List<String>, chosen: List<String>): Magazine?

    /** What the app remembers of the source: its last reason (why it is not ready, being repaired or unavailable). */
    suspend fun record(magazineId: String, sourceId: String): HealthRecord
}

/**
 * Adding and repairing a source (PLAN-collectors, "Adding a source" and "Health"): fetch the page once and save the
 * copy, have the AI write the collector, accept it only after the acceptance run, then record it in the recipe. A
 * repair keeps the old collector until the adapted one passes. A site that refuses automated reading becomes
 * unavailable at once, with no retry.
 */
class SourceSetup(
    private val writer: CollectorWriter,
    private val book: SourceBook,
    private val fixtures: FixtureStore,
    private val health: HealthStore,
    /** The real network, used only to copy the page and, in the warm-up run, the site's other pages it reads. */
    private val live: FetchTransport,
    private val now: () -> String,
    /** The model of the collector-writing job, recorded with the script. */
    private val model: () -> String = { "" },
    /** Lets the warm-up run fetch pages the copy lacks; tests turn it off so only saved pages are read. */
    private val warmUp: Boolean = true,
) : CollectorWork {

    override suspend fun write(magazineId: String, sourceId: String): SetupOutcome = work(magazineId, sourceId, null)

    override suspend fun repair(magazineId: String, sourceId: String, reason: String): SetupOutcome =
        work(magazineId, sourceId, reason)

    override suspend fun keepKeywords(magazineId: String, offered: List<String>, chosen: List<String>): Magazine? {
        val allowed = offered.map { it.trim().lowercase() }.toSet()
        return book.addKeywords(magazineId, chosen.filter { it.trim().lowercase() in allowed })
    }

    override suspend fun record(magazineId: String, sourceId: String): HealthRecord = health.get(magazineId, sourceId)

    private suspend fun work(magazineId: String, sourceId: String, repairReason: String?): SetupOutcome {
        val magazine = book.magazine(magazineId) ?: return SetupOutcome.Gone
        val source = magazine.sources.firstOrNull { it.id == sourceId } ?: return SetupOutcome.Gone
        val record = health.get(magazineId, sourceId)

        val fixture = when (val copy = PageCopier.copy(source.url, live, now())) {
            is PageCopy.Refused -> {
                health.put(magazineId, sourceId, record.copy(refusals = record.refusals + 1, note = copy.reason))
                val changed = book.update(magazineId, sourceId) { it.copy(health = SourceHealth.UNAVAILABLE) }
                    ?: return SetupOutcome.Gone
                return SetupOutcome.Unavailable(changed, copy.reason)
            }
            is PageCopy.Failed -> return notReady(magazineId, source, record, copy.reason)
            is PageCopy.Saved -> copy.fixture
        }
        val version = (source.collector?.version ?: 0) + 1
        val key = fixtureKey(magazineId, sourceId, version)
        fixtures.put(key, fixture)

        val previous = source.collector?.takeIf { repairReason != null }
        val brief = CollectorBrief(fixture, source.liked, magazine.keywords, previous?.script, repairReason)
        return when (val outcome = writer.write(brief, live.takeIf { warmUp })) {
            is WriteOutcome.NotReady -> notReady(magazineId, source, record, outcome.reason)
            is WriteOutcome.Accepted -> {
                fixtures.put(key, outcome.acceptance.fixture)
                val info = CollectorInfo(outcome.script, version, now(), model())
                val accepted = book.update(magazineId, sourceId) {
                    it.copy(collector = info, fixture = key, health = SourceHealth.WORKING)
                } ?: return SetupOutcome.Gone
                health.put(magazineId, sourceId, HealthRecord(hadRecords = true))
                source.fixture?.takeIf { it != key }?.let { fixtures.delete(it) }
                val known = (book.magazine(magazineId)?.keywords ?: magazine.keywords).map { it.lowercase() }.toSet()
                SetupOutcome.Ready(accepted, outcome.suggested.filter { it.lowercase() !in known })
            }
        }
    }

    private suspend fun notReady(magazineId: String, source: Source, record: HealthRecord, reason: String): SetupOutcome {
        health.put(magazineId, source.id, record.copy(note = reason))
        return SetupOutcome.NotReady(source, reason)
    }

    companion object {
        fun fixtureKey(magazineId: String, sourceId: String, version: Int) = "$magazineId/$sourceId/$version"

        /** Null when the collector-writing job can call its model; otherwise what is missing. */
        fun missing(registry: ProviderRegistry): MissingSetup? {
            val settings = registry.state.value
            if (settings.providers.isEmpty()) return MissingSetup.NO_PROVIDER
            val choice = settings.choices[AiJob.WRITE_COLLECTORS] ?: return MissingSetup.NO_CHOICE
            if (settings.providers.none { it.id == choice.providerId }) return MissingSetup.NO_CHOICE
            if (choice.model.isBlank()) return MissingSetup.NO_MODEL
            if (runCatching { registry.key(choice.providerId) }.getOrNull() == null) return MissingSetup.NO_KEY
            return null
        }

        /** The model chosen for the collector-writing job, or "" when none. */
        fun modelOf(registry: ProviderRegistry): String = registry.state.value.choices[AiJob.WRITE_COLLECTORS]?.model.orEmpty()
    }
}
