package dobra.app.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dobra.app.R
import dobra.make.MakeProblem
import dobra.make.MakeProgress
import dobra.make.MakeResult
import dobra.make.MakeStage
import dobra.make.MissingSetup
import dobra.model.LeftOut
import dobra.model.Magazine
import java.util.Locale

/**
 * "Gerar edição agora" on the magazine page: starts the run, shows its progress (collecting, choosing, writing,
 * saving), then the new edition with a way to open it, or what is missing or went wrong, and what was left out.
 */
@Composable
internal fun MakeNow(magazine: Magazine, runner: MakeRunner, onOpenEdition: (String) -> Unit) {
    val state by remember(magazine.id, runner) { runner.makeState(magazine.id) }.collectAsState(initial = RunState.Idle)
    Body(stringResource(R.string.make_hint))
    BtnRow { Btn(stringResource(R.string.make_now), "make-now", primary = true) { runner.make(magazine) } }
    when (val s = state) {
        is RunState.Running -> {
            Meta(progressText(s.progress), "make-progress")
            s.progress.current?.let { Meta(stringResource(R.string.make_current, it), "make-progress-current") }
        }
        is RunState.Missing -> Body(missingText(s.what), "make-missing")
        is RunState.Failed -> Body(failText(s), "make-failure")
        is RunState.Done -> MakeDone(s.result, onOpenEdition)
        RunState.Idle -> Unit
    }
}

@Composable
private fun MakeDone(r: MakeResult, onOpenEdition: (String) -> Unit) {
    val edition = r.edition
    if (edition != null) {
        Card("make-result") {
            Kicker(stringResource(R.string.make_done_title, edition.number))
            Meta(
                stringResource(
                    R.string.make_done_counts,
                    countOf(edition.items.size, R.string.count_stories_one, R.string.count_stories_many),
                    countOf(r.leftOut.size, R.string.make_left_one, R.string.make_left_many),
                    dollars(r.cost.costUsd),
                    r.cost.calls,
                ),
                "make-result-counts",
            )
            BtnRow { Btn(stringResource(R.string.make_open), "make-open", primary = true) { onOpenEdition(edition.key) } }
        }
    } else {
        Body(
            stringResource(
                when (r.problem) {
                    MakeProblem.NOTHING_COLLECTED -> R.string.make_nothing_collected
                    MakeProblem.SELECTION_UNREADABLE -> R.string.make_selection_unreadable
                    MakeProblem.REFUSED_BY_STORE -> R.string.make_refused_by_store
                    else -> R.string.make_nothing_written
                },
            ),
            "make-no-edition",
        )
        if (r.cost.calls > 0) Meta(stringResource(R.string.make_cost, dollars(r.cost.costUsd), r.cost.calls), "make-cost")
    }
    LeftOutList(r.leftOut, "make-left")
}

/** What was left out, one line per item with its reason; [tag] names the lines `<tag>-<index>`. */
@Composable
internal fun LeftOutList(list: List<LeftOut>, tag: String, title: String = stringResource(R.string.make_left_title)) {
    if (list.isEmpty()) return
    SectionTitle(title)
    list.forEachIndexed { i, l -> Body(stringResource(R.string.make_left_line, l.title, l.reason), "$tag-$i") }
}

@Composable
internal fun progressText(p: MakeProgress): String = when (p.stage) {
    MakeStage.COLLECTING -> stringResource(R.string.make_collecting, p.done, p.total)
    MakeStage.CHOOSING -> stringResource(R.string.make_choosing)
    MakeStage.WRITING -> stringResource(R.string.make_writing, p.done, p.total)
    MakeStage.SAVING -> stringResource(R.string.make_saving)
}

@Composable
internal fun missingText(what: MissingSetup): String = stringResource(
    when (what) {
        MissingSetup.NO_PROVIDER -> R.string.make_missing_provider
        MissingSetup.NO_CHOICE -> R.string.make_missing_choice
        MissingSetup.NO_MODEL -> R.string.make_missing_model
        MissingSetup.NO_KEY -> R.string.make_missing_key
    },
)

@Composable
internal fun failText(f: RunState.Failed): String = when (f.reason) {
    FailReason.KEY_REFUSED -> stringResource(R.string.make_fail_key)
    FailReason.RATE_LIMITED -> stringResource(R.string.make_fail_rate)
    FailReason.UNREACHABLE -> stringResource(R.string.make_fail_unreachable)
    FailReason.OTHER -> stringResource(R.string.make_fail_other, f.detail)
}

/** A cost in US dollars, written the Brazilian way (comma for decimals). */
internal fun dollars(value: Double): String = String.format(Locale.forLanguageTag("pt-BR"), "%.4f", value)
