package dobra.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dobra.app.R
import dobra.collect.health.HealthRecord
import dobra.make.MissingSetup
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.SourceHealth
import kotlinx.coroutines.flow.flowOf

/**
 * The collector part of a source on the magazine page: its state in words (with the reason when something is wrong)
 * and, when the AI can help, the action that asks it to write or adapt the collector. After a collector is accepted,
 * the keywords the AI suggested are offered; only those the reader picks go into the recipe.
 */
@Composable
internal fun SourceCollectorPart(m: Magazine, s: Source, desk: CollectorDesk?) {
    val state by remember(m.id, s.id, desk) { desk?.state(m.id, s.id) ?: flowOf(DeskState.Idle) }
        .collectAsState(initial = DeskState.Idle)
    val record by produceState(HealthRecord(), desk, s, state) {
        value = desk?.record(m.id, s.id) ?: HealthRecord()
    }
    val working = state as? DeskState.Working

    Meta(stateLabel(s, state, record), "source-state-${s.id}")
    reasonOf(s, state, record)?.let { Body(stringResource(R.string.collector_reason, it), "source-reason-${s.id}") }
    if (desk == null || working != null) return

    when (val st = state) {
        is DeskState.Missing -> Body(collectorMissingText(st.what), "collector-missing-${s.id}")
        is DeskState.Failed -> Body(collectorFailText(st.reason, st.detail), "collector-failed-${s.id}")
        is DeskState.Ready -> if (st.suggested.isEmpty()) {
            Body(stringResource(R.string.collector_ready), "collector-ready-${s.id}")
        } else {
            Suggestions(s, st.suggested, onKeep = { desk.keep(m.id, s.id, st.suggested, it) }, onSkip = { desk.dismiss(m.id, s.id) })
        }
        else -> Unit
    }
    if (s.collector == null) {
        val again = record.note != null || state is DeskState.NotReady || state is DeskState.Unavailable
        BtnRow {
            Btn(
                stringResource(if (again) R.string.collector_write_again else R.string.collector_write),
                "write-collector-${s.id}",
                primary = !again,
            ) { desk.write(m.id, s.id) }
        }
    } else if (s.health == SourceHealth.REPAIRING) {
        BtnRow {
            Btn(stringResource(R.string.collector_repair), "repair-collector-${s.id}") {
                desk.repair(m.id, s.id, record.note.orEmpty())
            }
        }
    }
}

@Composable
private fun stateLabel(s: Source, state: DeskState, record: HealthRecord): String = stringResource(
    when {
        state is DeskState.Working -> if (state.repair) R.string.collector_state_adapting else R.string.collector_state_writing
        state is DeskState.Unavailable -> R.string.collector_state_unavailable
        s.collector == null -> when {
            record.refusals > 0 && s.health == SourceHealth.UNAVAILABLE -> R.string.collector_state_unavailable
            state is DeskState.NotReady || record.note != null -> R.string.collector_state_not_ready
            else -> R.string.collector_state_none
        }
        s.health == SourceHealth.WORKING -> R.string.collector_state_working
        s.health == SourceHealth.REPAIRING -> R.string.collector_state_repairing
        else -> R.string.collector_state_unavailable
    },
)

/** Why the source is not working, when there is something to say. */
private fun reasonOf(s: Source, state: DeskState, record: HealthRecord): String? = when {
    state is DeskState.Working -> null
    state is DeskState.NotReady -> state.reason
    state is DeskState.Unavailable -> state.reason
    s.collector != null && s.health == SourceHealth.WORKING -> null
    else -> record.note
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(s: Source, words: List<String>, onKeep: (List<String>) -> Unit, onSkip: () -> Unit) {
    var picked by remember(words) { mutableStateOf(emptySet<String>()) }
    Card("collector-suggestions-${s.id}") {
        Kicker(stringResource(R.string.collector_suggest_title))
        Body(stringResource(R.string.collector_suggest_body))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.forEachIndexed { i, w ->
                Choice(w, w in picked, "suggest-${s.id}-$i") { picked = if (w in picked) picked - w else picked + w }
            }
        }
        BtnRow {
            Btn(stringResource(R.string.collector_suggest_keep), "keep-keywords-${s.id}", primary = true) {
                onKeep(words.filter { it in picked })
            }
            Btn(stringResource(R.string.collector_suggest_skip), "skip-keywords-${s.id}", onClick = onSkip)
        }
    }
}

@Composable
private fun collectorMissingText(what: MissingSetup): String = stringResource(
    when (what) {
        MissingSetup.NO_PROVIDER -> R.string.collector_missing_provider
        MissingSetup.NO_CHOICE -> R.string.collector_missing_choice
        MissingSetup.NO_MODEL -> R.string.collector_missing_model
        MissingSetup.NO_KEY -> R.string.collector_missing_key
    },
)

@Composable
private fun collectorFailText(reason: FailReason, detail: String): String = when (reason) {
    FailReason.KEY_REFUSED -> stringResource(R.string.collector_fail_key)
    FailReason.RATE_LIMITED -> stringResource(R.string.collector_fail_rate)
    FailReason.UNREACHABLE -> stringResource(R.string.collector_fail_unreachable)
    FailReason.OTHER -> stringResource(R.string.collector_fail_other, detail)
}
