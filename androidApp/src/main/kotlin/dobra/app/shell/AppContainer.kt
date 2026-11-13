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

    /** The worker: runs the collectors of a magazine over the real network (timeout, size limit, one try per page). */
    val collector: dobra.collect.worker.MagazineCollector = object : dobra.collect.worker.MagazineCollector {
        override suspend fun collect(
            magazine: dobra.model.Magazine,
            onProgress: (dobra.collect.worker.WorkerProgress) -> Unit,
        ): dobra.collect.worker.WorkerResult = dobra.collect.worker.CollectionWorker(
            engine = dobra.collect.engine.CollectorEngine(dobra.app.sources.HttpFetchTransport()),
            cursors = dobra.app.sources.PrefsCursorStore(context),
            published = dobra.collect.worker.EditionLinks(store.await().editions),
        ).collect(magazine, onProgress)
    }

    private fun readSeed(): SeedContent {
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        return SeedContent(Seed.magazine(asset(Seed.MAGAZINE_FILE)), Seed.edition(asset(Seed.EDITION_FILE)))
    }
}
