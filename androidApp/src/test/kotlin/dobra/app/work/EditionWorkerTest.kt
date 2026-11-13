package dobra.app.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.HttpRequest
import dobra.ai.provider.HttpResponse
import dobra.ai.provider.HttpTransport
import dobra.ai.provider.JobChoice
import dobra.ai.provider.MemoryBlobStorage
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.SecretCipher
import dobra.ai.provider.UsageLedger
import dobra.app.ai.AiSetup
import dobra.app.edition.EditionFixture
import dobra.app.library.MakeRunner
import dobra.app.library.RunState
import dobra.app.shell.MainActivity
import dobra.collect.engine.RawRecord
import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.SourceResult
import dobra.collect.worker.WorkerProgress
import dobra.collect.worker.WorkerResult
import dobra.model.CollectionState
import dobra.model.Magazine
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

private object PlainCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(sealed: ByteArray) = sealed
}

/** Recorded answers in the OpenAI chat completions format, replayed in order. Nothing reaches a network. */
private class Replay(answers: List<String>) : HttpTransport {
    private val queue = answers.toMutableList()
    val sent = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        synchronized(sent) { sent += request }
        val content = synchronized(queue) { queue.removeAt(0) }
        val body = buildJsonObject {
            put("id", "chatcmpl-test")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject { put("prompt_tokens", 1000); put("completion_tokens", 500) })
        }
        return HttpResponse(200, emptyMap(), body.toString())
    }
}

@RunWith(AndroidJUnit4::class)
class EditionWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("dobra-work-test").toFile()
    private val store: Store = runBlocking { openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) } }
    private val id = EditionFixture.magazine.id
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = ZonedDateTime.of(2026, 11, 14, 7, 2, 0, 0, ZoneId.of("America/Sao_Paulo"))
    private lateinit var generation: Generation
    private val foreground = mutableListOf<ForegroundInfo>()
    private val progress = mutableListOf<Data>()

    private val tool = RawRecord(
        "acme/agent-guard", "https://github.com/acme/agent-guard", "2026-11-14",
        "agent-guard bloqueia comandos perigosos que um agente de código tenta rodar. Licença MIT.",
    )

    private val collector = object : MagazineCollector {
        var asked = 0
        override suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit): WorkerResult {
            asked++
            onProgress(WorkerProgress(0, 1, "GitHub Trending"))
            onProgress(WorkerProgress(1, 1, null))
            val result = SourceResult(
                "github-trending", "GitHub Trending", CollectionState.ANSWERED, 12, 1, mapOf("agentes" to 1), listOf(tool), emptyList(),
            )
            return WorkerResult(magazine.id, listOf(result))
        }
    }

    private val selection = """{"keep": [{"records": ["r1"], "kind": "story"}], "leftOut": []}"""

    private val answer = buildJsonObject {
        put("id", "agent-guard")
        put("kind", "story")
        put("blocks", buildJsonArray {
            add(buildJsonObject { put("type", "title"); put("kicker", "Segurança"); put("text", "Um guarda de permissões para agentes") })
            add(buildJsonObject { put("type", "paragraph"); put("text", "O que está no texto coletado.") })
            add(buildJsonObject {
                put("type", "sources")
                put("links", buildJsonArray { add(buildJsonObject { put("label", "Fonte"); put("url", tool.link) }) })
            })
        })
    }.toString()

    private fun ai(transport: Replay, provider: Boolean = true): AiSetup {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, PlainCipher)
        val ledger = UsageLedger(storage)
        if (provider) {
            val p = registry.add("Teste", ProviderKind.OPENAI_STYLE, "https://api.example.test/v1", "sk-fake-test-0000", 1.0, 4.0)
            registry.choose(AiJob.REWRITE, JobChoice(p.id, "model-rewrite"))
        }
        return AiSetup(registry, ledger, AiGateway(registry, transport, ledger))
    }

    @Before
    fun setUp() {
        context.getSharedPreferences("generation", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        generation = Generation(context, { WorkManager.getInstance(context) }, { now })
    }

    @After
    fun close() {
        scope.cancel()
        store.close()
        root.deleteRecursively()
    }

    private fun runWorker(runner: MakeRunner): ListenableWorker.Result {
        EditionWork.deps = { WorkDeps(runner, { store }, generation) }
        val worker = TestListenableWorkerBuilder<EditionWorker>(context)
            .setInputData(Data.Builder().putString(EditionWorker.MAGAZINE, id).build())
            .setForegroundUpdater(object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo) =
                    CallbackToFutureAdapter.getFuture<Void?> { synchronized(foreground) { foreground += info }; it.set(null) }
            })
            .setProgressUpdater(object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data) =
                    CallbackToFutureAdapter.getFuture<Void?> { synchronized(progress) { progress += data }; it.set(null) }
            })
            .build()
        return runBlocking { worker.doWork() }
    }

    private fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun theJobMakesTheEditionTheSameWayAsMakeNowAndAnnouncesItOpeningThatEdition() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val transport = Replay(listOf(selection, answer))
        val runner = MakeRunner(scope, { store }, ai(transport), collector, today = { "2026-11-14" })
        val before = runBlocking { store.editions.list(id) }.size

        assertEquals(ListenableWorker.Result.success(), runWorker(runner))

        // The same making as "Gerar edição agora": the runner's state for the magazine ends with the stored edition.
        val state = runBlocking { runner.makeState(id).first() }
        assertTrue(state is RunState.Done)
        val key = "$id/2026-11-14"
        assertNotNull(runBlocking { store.editions.get(key) })
        assertEquals(before + 1, runBlocking { store.editions.list(id) }.size)
        assertEquals(1, collector.asked)
        assertEquals(2, transport.sent.size)

        // While it ran: a foreground notification with the progress.
        val first = foreground.first().notification
        assertEquals("Gerando edição · ${EditionFixture.magazine.name}", first.title())
        assertEquals("Começando…", first.text())
        assertTrue(foreground.any { it.notification.text() == "Coletando… 0 de 1 fontes" })
        assertTrue(progress.any { it.getString(EditionWorker.STAGE) == "COLLECTING" })

        // When it is ready: "Edição pronta", which opens that edition.
        val ready = posted().single { it.title() == "Edição pronta" }
        val intent = shadowOf(ready.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(key, intent.getStringExtra(EditionNotices.EXTRA_OPEN_EDITION))

        val last = generation.lastRun(id)!!
        assertEquals(LastOutcome.MADE, last.outcome)
        assertEquals(key, last.editionKey)
    }

    @Test
    fun withoutAProviderTheJobCallsNothingAndRecordsWhatIsMissing() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val transport = Replay(emptyList())
        val runner = MakeRunner(scope, { store }, ai(transport, provider = false), collector)

        assertEquals(ListenableWorker.Result.success(), runWorker(runner))

        assertEquals(0, collector.asked)
        assertTrue(transport.sent.isEmpty())
        assertEquals(LastOutcome.MISSING_SETUP, generation.lastRun(id)!!.outcome)
        assertTrue(posted().none { it.title() == "Edição pronta" })
    }

    @Test
    fun withoutPermissionToNotifyTheEditionIsStillMadeButNothingIsPosted() {
        val runner = MakeRunner(scope, { store }, ai(Replay(listOf(selection, answer))), collector, today = { "2026-11-14" })

        assertEquals(ListenableWorker.Result.success(), runWorker(runner))

        assertNotNull(runBlocking { store.editions.get("$id/2026-11-14") })
        assertNull(posted().firstOrNull { it.title() == "Edição pronta" })
    }
}
