package dobra.app.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dobra.app.R
import dobra.app.library.Body
import dobra.app.library.Btn
import dobra.app.library.BtnRow
import dobra.app.library.Card
import dobra.app.library.Meta
import dobra.app.library.SectionTitle
import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.SourceResult
import dobra.collect.worker.WorkerProgress
import dobra.collect.worker.WorkerResult
import dobra.model.Magazine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Coletar agora" on the magazine page: starts the worker, shows its progress, then what each source answered and
 * how many records matched each keyword. Nothing is written here; the raw records wait for the next step.
 */
@Composable
fun CollectNow(magazine: Magazine, collector: MagazineCollector) {
    val scope = rememberCoroutineScope()
    var progress by remember(magazine.id) { mutableStateOf<WorkerProgress?>(null) }
    var result by remember(magazine.id) { mutableStateOf<WorkerResult?>(null) }
    var failure by remember(magazine.id) { mutableStateOf<String?>(null) }
    val running = progress != null

    BtnRow {
        Btn(stringResource(R.string.collect_now), "collect-now", primary = true) {
            if (running) return@Btn
            result = null
            failure = null
            progress = WorkerProgress(0, magazine.sources.size, magazine.sources.firstOrNull()?.name)
            scope.launch {
                try {
                    result = collector.collect(magazine) { progress = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e::class.simpleName
                } finally {
                    progress = null
                }
            }
        }
    }
    progress?.let { p ->
        Meta(stringResource(R.string.collect_running, p.done, p.total), "collect-progress")
        p.current?.let { Meta(stringResource(R.string.collect_running_source, it), "collect-progress-source") }
    }
    failure?.let { Body(stringResource(R.string.collect_result_reason, it), "collect-failure") }
    result?.let { CollectResults(magazine, it) }
}

@Composable
private fun CollectResults(magazine: Magazine, r: WorkerResult) {
    SectionTitle(stringResource(R.string.collect_done_title))
    Body(
        stringResource(R.string.collect_done_summary, r.records.size, r.results.size),
        "collect-summary",
    )
    if (r.candidates.isNotEmpty()) Meta(stringResource(R.string.collect_candidates, r.candidates.size), "collect-candidates")
    if (magazine.keywords.isEmpty()) Meta(stringResource(R.string.collect_no_keywords))
    r.results.forEach { SourceResultCard(it) }
}

@Composable
private fun SourceResultCard(s: SourceResult) {
    Card("result-${s.sourceId}") {
        Body(s.sourceName, "result-name-${s.sourceId}")
        Meta(
            collectionStateLabel(s.state) + " · " + stringResource(R.string.collect_result_counts, s.returned, s.kept),
            "result-state-${s.sourceId}",
        )
        if (s.perKeyword.isNotEmpty()) {
            val parts = s.perKeyword.entries.map { stringResource(R.string.collect_result_keyword, it.key, it.value) }
            Meta(
                parts.joinToString(" · "),
                "result-keywords-${s.sourceId}",
            )
        }
        s.refusedStatus?.let { Body(stringResource(R.string.collect_result_refused, it), "result-refused-${s.sourceId}") }
        s.reason?.let { Meta(stringResource(R.string.collect_result_reason, it), "result-reason-${s.sourceId}") }
    }
}
