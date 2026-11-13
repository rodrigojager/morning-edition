package dobra.app.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dobra.app.R
import dobra.app.library.Body
import dobra.app.library.Card
import dobra.app.library.Headline
import dobra.app.library.Kicker
import dobra.app.library.LibraryPage
import dobra.app.library.Meta
import dobra.app.library.countOf
import dobra.app.shell.PlaceholderScreen
import dobra.model.CollectionLine
import dobra.model.CollectionState
import dobra.model.Edition
import dobra.model.Magazine

/**
 * The Fontes tab: the collection record of the edition being read, one line per source with what it brought and
 * how it ended. Without an edition the tab says so.
 */
@Composable
fun SourcesScreen(magazine: Magazine? = null, edition: Edition? = null) {
    if (magazine == null && edition == null) {
        PlaceholderScreen(stringResource(R.string.sources_placeholder), "screen-sources")
        return
    }
    LibraryPage("screen-sources") {
        Kicker(stringResource(R.string.fontes_kicker))
        Headline(stringResource(R.string.fontes_title))
        if (edition == null) {
            Body(stringResource(R.string.sources_no_edition), "sources-no-edition")
            return@LibraryPage
        }
        Meta(
            stringResource(R.string.sources_edition_line, edition.number, edition.date, magazine?.name.orEmpty()),
            "sources-edition",
        )
        Body(stringResource(R.string.sources_intro))
        if (edition.collection.isEmpty()) Body(stringResource(R.string.sources_none_in_record), "sources-no-record")
        edition.collection.forEachIndexed { index, line -> CollectionLineCard(index, line) }
    }
}

@Composable
private fun CollectionLineCard(index: Int, line: CollectionLine) {
    Card("collection-line-$index") {
        Body(line.source, "collection-source-$index")
        Meta(
            collectionStateLabel(line.state) + " · " +
                countOf(line.records, R.string.sources_line_records_one, R.string.sources_line_records_many),
            "collection-state-$index",
        )
        if (!line.note.isNullOrBlank()) Body(line.note!!, "collection-note-$index")
    }
}

@Composable
internal fun collectionStateLabel(state: CollectionState): String = stringResource(
    when (state) {
        CollectionState.ANSWERED -> R.string.collect_state_answered
        CollectionState.PARTIAL -> R.string.collect_state_partial
        CollectionState.FAILED -> R.string.collect_state_failed
    },
)
