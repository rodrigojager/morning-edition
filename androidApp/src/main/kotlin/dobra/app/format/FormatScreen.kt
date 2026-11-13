package dobra.app.format

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.library.Body
import dobra.app.library.Btn
import dobra.app.library.BtnRow
import dobra.app.library.Card
import dobra.app.library.Choice
import dobra.app.library.Empty
import dobra.app.library.Headline
import dobra.app.library.Kicker
import dobra.app.library.LibraryPage
import dobra.app.library.Meta
import dobra.app.library.SectionTitle
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.model.Blocks
import dobra.model.Edition
import dobra.model.Magazine
import dobra.store.Store
import kotlinx.coroutines.launch

/**
 * The format of one magazine: which block types it accepts, how each one looks, how the newest edition stands against
 * the format and the schema that results. Every change is saved at once, through the store (which raises the recipe's
 * version). [onOpenEdition] receives the key of the newest edition.
 */
@Composable
fun FormatScreen(
    store: Store,
    magazineId: String,
    onBack: () -> Unit,
    onOpenEdition: (String) -> Unit = {},
) {
    val magazines by remember(store) { store.magazines.observeAll() }.collectAsState(initial = null)
    val list = magazines
    if (list == null) {
        LibraryPage("screen-format") {}
        return
    }
    val magazine = list.firstOrNull { it.id == magazineId }
    if (magazine == null) {
        LibraryPage("screen-format") {
            Btn(stringResource(R.string.magazine_back), "back", onClick = onBack)
            Empty(stringResource(R.string.format_missing), "", "format-missing")
        }
        return
    }
    val summaries by remember(magazineId) { store.editions.observe(magazineId) }.collectAsState(initial = null)
    val newest = summaries?.firstOrNull()?.key
    val loadedEdition by produceState<Edition?>(null, newest, summaries == null) { value = newest?.let { store.editions.get(it) } }
    val edition = if (newest == null) null else loadedEdition
    val scope = rememberCoroutineScope()
    val save: (Magazine) -> Unit = { m -> scope.launch { store.magazines.save(m) } }
    FormatPage(magazine, edition, newest, summaries != null, save, onBack, onOpenEdition)
}

@Composable
private fun FormatPage(
    magazine: Magazine,
    edition: Edition?,
    editionKey: String?,
    editionsKnown: Boolean,
    save: (Magazine) -> Unit,
    onBack: () -> Unit,
    onOpenEdition: (String) -> Unit,
) {
    val res = LocalContext.current.resources
    val format = magazine.format
    val uses = remember(edition) { edition?.let { usesOf(it) }.orEmpty() }
    LibraryPage("screen-format") {
        Btn(stringResource(R.string.format_back, magazine.name), "back", onClick = onBack)
        Kicker(stringResource(R.string.format_kicker))
        Headline(stringResource(R.string.format_title))
        Body(stringResource(R.string.format_intro))

        SectionTitle(stringResource(R.string.format_blocks_title))
        Meta(stringResource(R.string.format_blocks_count, format.types.size, Blocks.all.size), "format-count")
        Blocks.all.forEach { spec ->
            val on = format.accepts(spec.id)
            Card("format-block-${spec.id}") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        blockLabel(res, spec.id),
                        color = LocalReaderColors.current.ink,
                        fontFamily = AppFonts.display,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("format-label-${spec.id}"),
                    )
                }
                if (spec.required) {
                    Meta(stringResource(R.string.format_required), "format-required-${spec.id}")
                } else {
                    BtnRow {
                        Btn(
                            stringResource(if (on) R.string.format_accepted else R.string.format_refused),
                            "format-toggle-${spec.id}",
                            primary = on,
                        ) { save(magazine.copy(format = format.with(spec.id, !on))) }
                    }
                }
                val usage = when (val n = uses[spec.id] ?: 0) {
                    0 -> stringResource(R.string.format_uses_none)
                    1 -> stringResource(R.string.format_uses_one)
                    else -> stringResource(R.string.format_uses_many, n)
                }
                Body(blockWhat(res, spec.id) + if (edition != null) " · $usage" else "")
                if (on) LookEditor(spec.id, magazine, save)
            }
        }
        BtnRow {
            Btn(stringResource(R.string.format_reset), "format-reset") {
                save(magazine.copy(format = dobra.model.Format(), looks = emptyMap()))
            }
            if (editionKey != null) Btn(stringResource(R.string.format_see_edition), "format-see-edition", primary = true) {
                onOpenEdition(editionKey)
            }
        }

        SectionTitle(stringResource(R.string.format_check_title))
        CheckSection(edition, editionsKnown, format)

        SectionTitle(stringResource(R.string.format_schema_title))
        Body(stringResource(R.string.format_schema_note))
        Reveal("format-schema", R.string.format_schema_show, R.string.format_schema_hide) {
            schemaText(format, magazine.name)
        }

        SectionTitle(stringResource(R.string.format_looks_title))
        Body(stringResource(R.string.format_looks_note))
        Reveal("format-looks", R.string.format_looks_show, R.string.format_looks_hide) {
            prettyJson(looksData(format, magazine.looks))
        }
    }
}

/** The four look choices of one accepted type, behind an "Aparência" toggle that starts open when a look is set. */
@Composable
private fun LookEditor(type: String, magazine: Magazine, save: (Magazine) -> Unit) {
    val look = magazine.looks[type]
    val set = LookKey.entries.any { it.of(look) != null }
    var open by rememberSaveable(type) { mutableStateOf(set) }
    BtnRow { Btn(stringResource(R.string.format_look_open), "look-open-$type") { open = !open } }
    if (!open) return
    LookKey.entries.forEach { key ->
        Column(Modifier.padding(top = 2.dp)) {
            Meta(stringResource(key.label))
            BtnRow {
                val picked = key.of(look) ?: ""
                key.options.forEach { (value, label) ->
                    Choice(stringResource(label), picked == value, "look-$type-${key.id}-${value.ifEmpty { "default" }}") {
                        save(magazine.copy(looks = magazine.looks.with(type, key, value)))
                    }
                }
            }
        }
    }
}

/** The edition against the format: the count, the items outside it by name, or the note that there is nothing. */
@Composable
private fun CheckSection(edition: Edition?, editionsKnown: Boolean, format: dobra.model.Format) {
    val res = LocalContext.current.resources
    if (edition == null) {
        if (editionsKnown) Empty(stringResource(R.string.format_check_none_title), stringResource(R.string.format_check_none_body), "format-check-none")
        return
    }
    val check = remember(edition, format) { checkEdition(edition, format) }
    Card("format-check") {
        Text(
            stringResource(R.string.format_check_summary, check.inside, check.total),
            color = LocalReaderColors.current.ink,
            fontFamily = AppFonts.display,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("format-check-summary"),
        )
        check.items.filter { !it.ok }.forEach { c ->
            val parts = mutableListOf<String>()
            if (c.off.isNotEmpty()) {
                parts += res.getString(
                    R.string.format_check_off,
                    c.title,
                    c.off.joinToString(", ") { "“${blockLabel(res, it)}”" },
                )
            }
            if (c.faults.isNotEmpty()) {
                parts += res.getString(R.string.format_check_faults, c.title, c.faults.joinToString("; ") { explain(res, it) })
            }
            Body(parts.joinToString(" "), "format-check-item-${c.id}")
        }
        if (check.items.all { it.ok }) Body(stringResource(R.string.format_check_all_fine))
        else if (check.items.any { it.off.isNotEmpty() }) Meta(stringResource(R.string.format_check_note))
    }
}

/** A mono text block that is shown or hidden by a button; [tag] names the text, `<tag>-toggle` the button. */
@Composable
private fun Reveal(tag: String, show: Int, hide: Int, text: () -> String) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    BtnRow { Btn(stringResource(if (open) hide else show), "$tag-toggle") { open = !open } }
    if (open) {
        Text(
            text(),
            color = LocalReaderColors.current.ink2,
            fontFamily = AppFonts.mono,
            fontSize = 11.sp,
            modifier = Modifier.testTag(tag),
        )
    }
}
