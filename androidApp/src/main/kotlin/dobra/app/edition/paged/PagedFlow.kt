package dobra.app.edition.paged

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dobra.app.edition.BlockView
import dobra.app.edition.CoverContent
import dobra.app.edition.EndMark
import dobra.app.edition.ItemStoryActions
import dobra.app.edition.LocalStoryNumber
import dobra.app.edition.NoteSave
import dobra.app.edition.NotesHeader
import dobra.app.edition.TocRow
import dobra.app.edition.TocTitle
import dobra.app.edition.backstageParts
import dobra.app.edition.headOf
import dobra.app.edition.shownBlocks
import dobra.app.edition.text
import dobra.app.edition.title
import dobra.model.Edition
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.Magazine
import dobra.store.Keys
import dobra.store.MarksRepository

/** How a page may cut a piece: never (unless it is taller than a page), at the end of a text line, or between entries. */
internal enum class CutRule { WHOLE, LINES, ENTRIES }

/**
 * One piece of a chapter before it is measured: how to draw it and the rules for cutting it.
 * [fill] makes it a page of its own (the cover).
 */
internal class PieceSpec(
    val key: String,
    val rule: CutRule = CutRule.WHOLE,
    val keepWithNext: Boolean = false,
    val space: Dp = 0.dp,
    val fill: Boolean = false,
    val content: @Composable () -> Unit,
)

/** A chapter of the paged flow: its id in the reading position, the story it shows (if any) and its pieces. */
internal class ChapterSpec(val id: String, val story: Item?, val pieces: List<PieceSpec>)

/** What the pieces need from the screen: the marks and what to do on a touch. Read when they are drawn. */
internal class FlowActions(
    val marks: MarksRepository?,
    val isSaved: (String) -> Boolean,
    val onSave: (Item) -> Unit,
    val onOpenChapter: (String) -> Unit,
    val onBackToLibrary: () -> Unit,
    val onPreviousEdition: (() -> Unit)?,
)

/** Block types a page may end at a line, and those it may end between entries. Every other type stays whole. */
private val lineTypes = setOf("paragraph", "summary", "note", "list", "facts")
private val entryTypes = setOf("glossary", "factsheet", "sources", "evidence")

private fun ruleOf(type: String) = when (type) {
    in lineTypes -> CutRule.LINES
    in entryTypes -> CutRule.ENTRIES
    else -> CutRule.WHOLE
}

internal fun storyChapterId(item: Item) = "story-${item.id}"

/**
 * The edition as chapters of pieces, the same flow as the scroll: the cover, the contents, one chapter per story, the
 * short notes together, the backstage with the end mark.
 */
internal fun chaptersOf(magazine: Magazine, edition: Edition, actions: FlowActions): List<ChapterSpec> {
    val stories = edition.items.filter { it.kind == ItemKind.STORY }
    val notes = edition.items.filter { it.kind == ItemKind.NOTE }
    val format = magazine.format
    return buildList {
        add(
            ChapterSpec(
                "cover", null,
                listOf(
                    PieceSpec("cover", fill = true) {
                        CoverContent(magazine, edition, stories.firstOrNull(), Modifier.fillMaxSize(), padded = false)
                    },
                ),
            ),
        )
        add(
            ChapterSpec(
                "toc", null,
                listOf(PieceSpec("toc-title") { TocTitle() }) + stories.mapIndexed { i, item ->
                    PieceSpec("toc-${item.id}", space = if (i == 0) 12.dp else 0.dp) {
                        TocRow(i, item) { actions.onOpenChapter(storyChapterId(it)) }
                    }
                },
            ),
        )
        stories.forEachIndexed { n, item ->
            val itemKey = Keys.item(edition.key, item.id)
            val blocks = shownBlocks(item.blocks, format)
            val head = headOf(blocks)
            val body = blocks.drop(head.size)
            fun blockPiece(i: Int, b: dobra.model.Block) = PieceSpec(
                "b$i-${b.type}", ruleOf(b.type), keepWithNext = b.type == "section", space = if (i == 0) 0.dp else 16.dp,
            ) {
                CompositionLocalProvider(LocalStoryNumber provides n + 1) { BlockView(b) }
            }
            val pieces = buildList {
                head.forEachIndexed { i, b -> add(blockPiece(i, b)) }
                add(
                    PieceSpec("actions", space = 16.dp) {
                        ItemStoryActions(item, itemKey, actions.isSaved(itemKey), actions.marks) { actions.onSave(item) }
                    },
                )
                body.forEachIndexed { i, b -> add(blockPiece(head.size + i, b)) }
            }
            add(ChapterSpec(storyChapterId(item), item, pieces))
        }
        if (notes.isNotEmpty()) {
            add(
                ChapterSpec(
                    "notes", null,
                    listOf(PieceSpec("notes-header") { NotesHeader(padded = false) }) + notes.map { item ->
                        val itemKey = Keys.item(edition.key, item.id)
                        PieceSpec("note-${item.id}", space = 28.dp) {
                            Column(
                                Modifier.fillMaxWidth().testTag("chapter-${item.id}"),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                shownBlocks(item.blocks, format).forEach { BlockView(it) }
                                NoteSave(item.id, actions.isSaved(itemKey)) { actions.onSave(item) }
                            }
                        }
                    },
                ),
            )
        }
        val parts = backstageParts(edition.collection, edition.leftOut)
        add(
            ChapterSpec(
                "backstage", null,
                parts.mapIndexed { i, part -> PieceSpec("backstage-$i", space = if (i == 0) 0.dp else 8.dp) { part() } } +
                    PieceSpec("end", space = 8.dp) {
                        EndMark(actions.onBackToLibrary, actions.onPreviousEdition, padded = false)
                    },
            ),
        )
    }
}

/** A short name of the story for the pager: its kicker, or its title. */
internal fun Item.shortLabel(): String =
    blocks.firstOrNull { it.type == "title" }?.text("kicker")?.takeIf { it.isNotBlank() } ?: title()
