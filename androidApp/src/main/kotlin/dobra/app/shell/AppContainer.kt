package dobra.app.shell

import android.content.Context
import dobra.seed.Seed
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

/**
 * The one place that creates and hands out the app's long-lived objects (repositories, the worker, the AI jobs).
 * No dependency-injection framework: each feature adds its object here as a property and screens receive the
 * container from [AppNav].
 */
class AppContainer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The local store, opened on first use; the demonstration magazine and edition are imported on the first start. */
    val store: Deferred<Store> = scope.async(start = CoroutineStart.LAZY) {
        openStore(File(context.filesDir, "store").path, seed = ::readSeed)
    }

    /** AI providers, their sealed keys, the usage ledger and the one way of calling a model. */
    val ai: dobra.app.ai.AiSetup by lazy { dobra.app.ai.AiSetup.create(context) }

    /**
     * The worker: runs the collectors of a magazine over the real network (timeout, size limit, one try per page).
     * After each run the sources' health is brought up to date; a source that has just become "being repaired" is
     * handed to [desk], which asks the AI to adapt its collector.
     */
    val collector: dobra.collect.worker.MagazineCollector = object : dobra.collect.worker.MagazineCollector {
        override suspend fun collect(
            magazine: dobra.model.Magazine,
            onProgress: (dobra.collect.worker.WorkerProgress) -> Unit,
        ): dobra.collect.worker.WorkerResult = dobra.collect.health.HealthWatch(
            dobra.collect.worker.CollectionWorker(
                engine = dobra.collect.engine.CollectorEngine(dobra.app.sources.HttpFetchTransport()),
                cursors = dobra.app.sources.PrefsCursorStore(context),
                published = dobra.collect.worker.EditionLinks(store.await().editions),
            ),
            dobra.collect.health.HealthMonitor(sourceBook, healthRecords),
            onRepair = { m, s, reason -> desk.repair(m, s, reason) },
        ).collect(magazine, onProgress)
    }

    /** The recipes' sources, changed one at a time; the sources' health records and the saved copies of their pages. */
    private val sourceBook by lazy { dobra.collect.health.StoreSourceBook { store.await().magazines } }
    private val healthRecords by lazy { dobra.app.library.PrefsHealthStore(context) }
    private val pageCopies by lazy { dobra.app.library.FileFixtureStore(File(context.filesDir, "collector-pages")) }

    /** The AI writing and repairing collectors (job "Escrever coletores"), run in the app's scope. */
    val desk: dobra.app.library.CollectorDesk by lazy {
        dobra.app.library.CollectorDesk(
            scope,
            work = {
                dobra.ai.collector.SourceSetup(
                    writer = dobra.ai.collector.CollectorWriter(ai.gateway),
                    book = sourceBook,
                    fixtures = pageCopies,
                    health = healthRecords,
                    live = dobra.app.sources.HttpFetchTransport(),
                    now = { java.time.Instant.now().toString() },
                    model = { dobra.ai.collector.SourceSetup.modelOf(ai.registry) },
                )
            },
            missing = { dobra.ai.collector.SourceSetup.missing(ai.registry) },
        )
    }

    /** "Gerar edição agora" and the format editor's rewrite, run in the app's scope so they survive a change of tab. */
    val maker: dobra.app.library.MakeRunner by lazy {
        dobra.app.library.MakeRunner(scope, { store.await() }, ai, collector, pages = dobra.app.library.HttpPageReader())
    }

    private fun readSeed(): SeedContent {
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        return SeedContent(Seed.magazine(asset(Seed.MAGAZINE_FILE)), Seed.edition(asset(Seed.EDITION_FILE)))
    }
}
