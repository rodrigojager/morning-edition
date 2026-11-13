package dobra.app.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dobra.app.R
import dobra.app.library.Body
import dobra.app.library.Btn
import dobra.app.library.BtnRow
import dobra.app.library.LeftOutList
import dobra.app.library.MakeRunner
import dobra.app.library.Meta
import dobra.app.library.RunState
import dobra.app.library.SectionTitle
import dobra.app.library.dollars
import dobra.app.library.failText
import dobra.app.library.missingText
import dobra.make.MakeStage
import dobra.model.Edition
import dobra.model.Magazine

/**
 * The explicit action of the format editor: the AI rewrites the items of the newest edition that are outside the
 * current format. Shown while some item is outside, or while a rewrite has something to say.
 */
@Composable
internal fun RewriteSection(magazine: Magazine, edition: Edition, maker: MakeRunner) {
    val state by remember(magazine.id, maker) { maker.reformatState(magazine.id) }.collectAsState(initial = RunState.Idle)
    val outside = remember(edition, magazine.format) { checkEdition(edition, magazine.format).items.count { !it.ok } }
    val s = state
    if (outside == 0 && s is RunState.Idle) return
    SectionTitle(stringResource(R.string.rewrite_title))
    Body(stringResource(R.string.rewrite_body))
    if (outside > 0) {
        BtnRow {
            Btn(stringResource(R.string.rewrite_action), "format-rewrite", primary = true) { maker.reformat(magazine, edition.key) }
        }
    }
    when (s) {
        is RunState.Running -> Meta(
            if (s.progress.stage == MakeStage.SAVING) stringResource(R.string.make_saving)
            else stringResource(R.string.rewrite_running, s.progress.done, s.progress.total),
            "format-rewrite-progress",
        )
        is RunState.Missing -> Body(missingText(s.what), "format-rewrite-missing")
        is RunState.Failed -> Body(failText(s), "format-rewrite-failure")
        is RunState.Done -> {
            val r = s.result
            Body(
                if (r.outside == 0) stringResource(R.string.rewrite_nothing)
                else stringResource(R.string.rewrite_done, r.rewritten.size, r.outside, dollars(r.cost.costUsd)),
                "format-rewrite-result",
            )
            LeftOutList(r.kept, "format-rewrite-kept", stringResource(R.string.rewrite_kept_title))
        }
        RunState.Idle -> Unit
    }
}
