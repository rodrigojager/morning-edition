package dobra.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dobra.app.library.MakeRunner
import dobra.app.library.RunState
import dobra.app.shell.AppContainer
import dobra.make.MakeProgress
import dobra.store.Store

/** What the background job needs: the same runner as "Gerar edição agora", the store and the schedule's records. */
class WorkDeps(val runner: MakeRunner, val store: suspend () -> Store, val generation: Generation)

/** Where the job finds its dependencies: the app's one container. Tests replace [deps]. */
object EditionWork {
    @Volatile
    var deps: (Context) -> WorkDeps = { context ->
        val container = AppContainer.of(context)
        WorkDeps(container.maker, { container.store.await() }, container.generation)
    }
}

/**
 * The periodic job of one magazine: makes its edition exactly as "Gerar edição agora" does ([MakeRunner.run]). Making an
 * edition means many fetches and AI calls and may pass WorkManager's ten-minute limit, so the job runs as a long-running
 * (foreground, data sync) job with a progress notification; when the system does not allow starting it in the foreground,
 * it goes on as a plain job. The outcome is recorded for the "Geração de edições" screen, and a made edition is announced
 * with "Edição pronta", which opens it.
 */
class EditionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(MAGAZINE) ?: return Result.failure()
        val deps = EditionWork.deps(applicationContext)
        val magazine = deps.store().magazines.get(id) ?: return Result.success()
        val notices = EditionNotices(applicationContext)
        show(notices, magazine, null)
        var shown: Pair<Any, Int>? = null
        val state = deps.runner.run(magazine) { p ->
            val step = p.stage to p.done
            if (step != shown) {
                shown = step
                show(notices, magazine, p)
            }
        }
        val run = when (state) {
            is RunState.Done -> {
                val edition = state.result.edition
                if (edition != null) {
                    notices.ready(magazine, edition)
                    LastRun(deps.generation.stamp(), LastOutcome.MADE, edition.key)
                } else {
                    LastRun(deps.generation.stamp(), LastOutcome.NOTHING, detail = state.result.problem?.name.orEmpty())
                }
            }
            is RunState.Missing -> LastRun(deps.generation.stamp(), LastOutcome.MISSING_SETUP, detail = state.what.name)
            is RunState.Failed -> LastRun(deps.generation.stamp(), LastOutcome.FAILED, detail = state.detail)
            // A run of this magazine was already on (started from the screen): it records nothing here.
            RunState.Idle, is RunState.Running -> return Result.success()
        }
        deps.generation.record(id, run)
        return Result.success()
    }

    private fun show(notices: EditionNotices, magazine: dobra.model.Magazine, progress: MakeProgress?) {
        setProgressAsync(
            workDataOf(
                STAGE to (progress?.stage?.name ?: ""),
                DONE to (progress?.done ?: 0),
                TOTAL to (progress?.total ?: 0),
            ),
        )
        runCatching { setForegroundAsync(notices.progress(magazine, progress)) }
    }

    companion object {
        const val MAGAZINE = "magazine"
        const val STAGE = "stage"
        const val DONE = "done"
        const val TOTAL = "total"
    }
}
