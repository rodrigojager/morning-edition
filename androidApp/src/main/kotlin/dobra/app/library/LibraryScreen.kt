package dobra.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.app.shell.PlaceholderScreen
import dobra.model.Blocks
import dobra.model.CadenceKind
import dobra.model.Magazine
import dobra.model.Source
import dobra.store.EditionSummary
import dobra.store.Store
import kotlinx.coroutines.launch

private const val LIST = "list"
private const val NEW = "form:new"

/**
 * The Biblioteca tab: the reader's magazines, and inside the tab the page of one magazine (recipe, sources and
 * editions) with the forms to create and edit them. [onOpenEdition] receives the key of the edition to read.
 */
@Composable
fun LibraryScreen(
    store: Store?,
    collector: dobra.collect.worker.MagazineCollector? = null,
    maker: MakeRunner? = null,
    desk: CollectorDesk? = null,
    onOpenEdition: (String) -> Unit = {},
) {
    if (store == null) {
        PlaceholderScreen(stringResource(R.string.library_placeholder), "screen-library")
        return
    }
    var page by rememberSaveable { mutableStateOf(LIST) }
    val go: (String) -> Unit = { page = it }
    when {
        page == LIST -> LibraryList(store, go)
        page == NEW -> MagazineForm(store, null, go)
        page.startsWith("form:") -> MagazineForm(store, page.removePrefix("form:"), go)
        page.startsWith("prompt:") -> PromptForm(store, page.removePrefix("prompt:"), go)
        page.startsWith("src:") -> SourceForm(store, page.removePrefix("src:"), go, desk)
        page.startsWith("fmt:") -> page.removePrefix("fmt:").let { id ->
            dobra.app.format.FormatScreen(store, id, onBack = { go("mag:$id") }, onOpenEdition = onOpenEdition, maker = maker)
        }
        else -> MagazinePage(store, page.removePrefix("mag:"), go, onOpenEdition, collector, maker, desk)
    }
}

// ---- the list ----

@Composable
private fun LibraryList(store: Store, go: (String) -> Unit) {
    val magazines by remember(store) { store.magazines.observeAll() }.collectAsState(initial = null)
    val saved by remember(store) { store.marks.observeSaved() }.collectAsState(initial = emptyList())
    LibraryPage("screen-library") {
        Kicker(stringResource(R.string.library_kicker))
        Headline(stringResource(R.string.library_title))
        Body(stringResource(R.string.library_intro))
        val list = magazines
        if (list != null && list.isEmpty()) {
            Empty(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body), "library-empty")
        }
        list.orEmpty().forEach { m ->
            MagazineCard(store, m, saved.count { it.key.startsWith(m.id + "/") }, go)
        }
        Btn(stringResource(R.string.library_new), "new-magazine", primary = true) { go(NEW) }
    }
}

@Composable
private fun MagazineCard(store: Store, m: Magazine, savedCount: Int, go: (String) -> Unit) {
    val editions by remember(m.id) { store.editions.observe(m.id) }.collectAsState(initial = emptyList())
    val unread by remember(m.id) { store.unread.observe(m.id) }.collectAsState(initial = 0)
    Card("mag-card-${m.id}") {
        Kicker(cadenceText(m))
        Text(
            m.name,
            color = LocalReaderColors.current.ink,
            fontFamily = AppFonts.display,
            fontSize = 24.sp,
            modifier = Modifier.testTag("mag-name-${m.id}"),
        )
        if (m.about.isNotBlank()) Body(m.about)
        Chips(m.topics.take(8).map { it.name }, "mag-topics-${m.id}")
        Meta(countsLine(editions.size, unread, savedCount, m.sources.size), "mag-counts-${m.id}")
        BtnRow { Btn(stringResource(R.string.library_open_page), "mag-open-${m.id}") { go("mag:${m.id}") } }
    }
}

@Composable
private fun cadenceText(m: Magazine): String = stringResource(
    if (m.cadence.kind == CadenceKind.WEEKLY) R.string.cadence_weekly else R.string.cadence_daily,
    m.cadence.hour,
)

@Composable
private fun countsLine(editions: Int, unread: Int, saved: Int, sources: Int?): String {
    val parts = mutableListOf(
        countOf(editions, R.string.count_edition_one, R.string.count_edition_many),
        if (unread > 0) stringResource(R.string.count_unread, unread) else stringResource(R.string.count_unread_none),
        countOf(saved, R.string.count_saved_one, R.string.count_saved_many),
    )
    if (sources != null) parts += countOf(sources, R.string.count_source_one, R.string.count_source_many)
    return parts.joinToString(" · ")
}

// ---- one magazine ----

@Composable
private fun MagazinePage(
    store: Store,
    id: String,
    go: (String) -> Unit,
    onOpenEdition: (String) -> Unit,
    collector: dobra.collect.worker.MagazineCollector?,
    maker: MakeRunner?,
    desk: CollectorDesk?,
) {
    val magazines by remember(store) { store.magazines.observeAll() }.collectAsState(initial = null)
    val list = magazines
    if (list == null) {
        LibraryPage("screen-magazine") {}
        return
    }
    val m = list.firstOrNull { it.id == id }
    if (m == null) {
        LibraryPage("screen-magazine-missing") {
            Btn(stringResource(R.string.magazine_back), "back") { go(LIST) }
            Headline(stringResource(R.string.missing_title))
            Body(stringResource(R.string.missing_body))
        }
        return
    }
    val scope = rememberCoroutineScope()
    val editions by remember(id) { store.editions.observe(id) }.collectAsState(initial = emptyList())
    val unread by remember(id) { store.unread.observe(id) }.collectAsState(initial = 0)
    val saved by remember(store) { store.marks.observeSaved() }.collectAsState(initial = emptyList())
    var ticks by remember { mutableStateOf(0) }
    val readKeys by produceState(emptySet<String>(), editions, ticks) {
        value = editions.map { it.key }.filter { store.marks.isEditionRead(it) }.toSet()
    }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    LibraryPage("screen-magazine") {
        Btn(stringResource(R.string.magazine_back), "back") { go(LIST) }
        Headline(m.name)
        if (m.about.isNotBlank()) Body(m.about)
        Meta(
            countsLine(editions.size, unread, saved.count { it.key.startsWith(m.id + "/") }, m.sources.size),
            "magazine-counts",
        )
        SectionTitle(stringResource(R.string.editions_title))
        Btn(stringResource(R.string.prompt_edit), "edit-prompt") { go("prompt:${m.id}") }
        if (maker != null) MakeNow(m, maker, onOpenEdition)
        if (editions.isEmpty()) {
            Empty(stringResource(R.string.editions_none_title), stringResource(R.string.editions_none_body), "editions-empty")
        }
        editions.forEach { e ->
            EditionCard(
                e = e,
                read = e.key in readKeys,
                confirming = confirm == "ed:${e.key}",
                onOpen = { onOpenEdition(e.key) },
                onToggle = { scope.launch { store.marks.setEditionRead(e.key, e.key !in readKeys); ticks++ } },
                onAsk = { confirm = "ed:${e.key}" },
                onYes = {
                    confirm = null
                    scope.launch { store.editions.delete(e.key); ticks++ }
                },
                onNo = { confirm = null },
            )
        }

        BtnRow {
            Btn(stringResource(R.string.magazine_edit), "edit-magazine") { go("form:${m.id}") }
            Btn(stringResource(R.string.magazine_delete), "delete-magazine") { confirm = "mag" }
        }
        if (confirm == "mag") {
            ConfirmCard(
                stringResource(R.string.confirm_magazine_title, m.name),
                stringResource(R.string.confirm_magazine_body),
                onYes = {
                    confirm = null
                    scope.launch { store.magazines.delete(m.id) }
                    go(LIST)
                },
                onNo = { confirm = null },
            )
        }

        SectionTitle(stringResource(R.string.magazine_recipe))
        Recipe(m, onFormat = { go("fmt:${m.id}") })

        SectionTitle(stringResource(R.string.sources_title))
        Meta(stringResource(R.string.recipe_version, m.version), "recipe-version")
        Body(stringResource(R.string.collector_sources_hint))
        if (m.sources.isEmpty()) {
            Empty(stringResource(R.string.sources_none_title), stringResource(R.string.sources_none_body), "sources-empty")
        }
        m.sources.forEach { s ->
            SourceRow(m, s, desk) {
                scope.launch { store.magazines.save(m.copy(sources = m.sources.filter { it.id != s.id })) }
            }
        }
        BtnRow { Btn(stringResource(R.string.source_add), "add-source", primary = true) { go("src:${m.id}") } }
        if (collector != null) dobra.app.sources.CollectNow(m, collector)
    }
}

@Composable
private fun languageLabel(code: String): String = when (code) {
    "pt-BR" -> stringResource(R.string.language_ptbr)
    "en" -> stringResource(R.string.language_en)
    "es" -> stringResource(R.string.language_es)
    else -> code
}

@Composable
private fun toneLabel(tone: String): String = when (tone) {
    "didatico" -> stringResource(R.string.tone_didatico)
    "equilibrado" -> stringResource(R.string.tone_equilibrado)
    "direto" -> stringResource(R.string.tone_direto)
    else -> tone
}

@Composable
private fun Recipe(m: Magazine, onFormat: () -> Unit) {
    val none = stringResource(R.string.recipe_none)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Meta(stringResource(R.string.recipe_subjects))
        if (m.topics.isEmpty()) Body(none, "recipe-subjects") else Chips(m.topics.map { it.name }, "recipe-subjects")
        Meta(stringResource(R.string.recipe_keywords))
        if (m.keywords.isEmpty()) Body(none, "recipe-keywords") else Chips(m.keywords, "recipe-keywords")
        Meta(stringResource(R.string.recipe_ai))
        val line = stringResource(R.string.recipe_ai_line, languageLabel(m.language), toneLabel(m.tone).lowercase())
        Body(
            line + " " + m.prompt.ifBlank { stringResource(R.string.recipe_ai_no_prompt) },
            "recipe-ai",
        )
        Meta(stringResource(R.string.recipe_format))
        Body(
            stringResource(R.string.recipe_format_line, m.format.types.size, Blocks.all.size),
            "recipe-format",
        )
        val link = stringResource(R.string.recipe_format_link)
        ClickableText(
            text = AnnotatedString(link),
            style = TextStyle(
                color = LocalReaderColors.current.accent,
                fontFamily = AppFonts.text,
                fontSize = 16.sp,
                textDecoration = TextDecoration.Underline,
            ),
            modifier = Modifier.testTag("recipe-format-link"),
            onClick = { onFormat() },
        )
        Meta(stringResource(R.string.recipe_cadence))
        Body(cadenceText(m), "recipe-cadence")
    }
}

@Composable
private fun SourceRow(m: Magazine, s: Source, desk: CollectorDesk?, onRemove: () -> Unit) {
    Card("source-${s.id}") {
        Text(s.name, color = LocalReaderColors.current.ink, fontFamily = AppFonts.text, fontSize = 17.sp)
        Meta(s.url)
        Body(
            if (s.liked.isBlank()) stringResource(R.string.source_looking_for_none)
            else stringResource(R.string.source_looking_for, s.liked),
        )
        SourceCollectorPart(m, s, desk)
        BtnRow { Btn(stringResource(R.string.source_remove), "remove-source-${s.id}", onClick = onRemove) }
    }
}

@Composable
private fun EditionCard(
    e: EditionSummary,
    read: Boolean,
    confirming: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onAsk: () -> Unit,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    Card("edition-${e.key}") {
        Kicker(
            stringResource(R.string.edition_line, e.number, e.date) + " · " +
                stringResource(if (read) R.string.edition_read else R.string.edition_unread),
        )
        Meta(
            countOf(e.storyIds.size, R.string.count_stories_one, R.string.count_stories_many) + " · " +
                stringResource(R.string.edition_made_with, e.recipeVersion),
            "edition-meta-${e.key}",
        )
        BtnRow {
            Btn(stringResource(R.string.edition_open), "edition-open-${e.key}", primary = true, onClick = onOpen)
            Btn(
                stringResource(if (read) R.string.edition_mark_unread else R.string.edition_mark_read),
                "edition-toggle-${e.key}",
                onClick = onToggle,
            )
            Btn(stringResource(R.string.edition_delete), "edition-delete-${e.key}", onClick = onAsk)
        }
        if (confirming) {
            ConfirmCard(
                stringResource(R.string.confirm_edition_title, e.number),
                stringResource(R.string.confirm_edition_body),
                onYes = onYes,
                onNo = onNo,
            )
        }
    }
}

// ---- forms ----

@Composable
private fun PromptForm(store: Store, id: String, go: (String) -> Unit) {
    val magazine by produceState<Magazine?>(null, id) { value = store.magazines.get(id) }
    val current = magazine ?: return
    var prompt by rememberSaveable(id) { mutableStateOf(current.prompt) }
    val scope = rememberCoroutineScope()
    LibraryPage("screen-prompt-form") {
        Headline(stringResource(R.string.prompt_edit), 24)
        Body(stringResource(R.string.form_prompt_hint))
        Field(stringResource(R.string.form_prompt), prompt, "field-prompt", { prompt = it }, singleLine = false)
        BtnRow {
            Btn(stringResource(R.string.form_save), "prompt-save", primary = true) {
                scope.launch {
                    store.magazines.get(id)?.let { store.magazines.save(it.copy(prompt = prompt)) }
                    go("mag:$id")
                }
            }
            Btn(stringResource(R.string.form_cancel), "prompt-cancel") { go("mag:$id") }
        }
    }
}

private val DraftSaver = listSaver<RecipeDraft, Any>(
    save = { listOf(it.name, it.about, it.subjects, it.keywords, it.language, it.tone, it.prompt, it.weekly, it.hour) },
    restore = {
        RecipeDraft(
            it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as String,
            it[5] as String, it[6] as String, it[7] as Boolean, it[8] as String,
        )
    },
)

/** Creates a magazine ([id] null) or edits one. Saving an edit raises the recipe's version in the store. */
@Composable
private fun MagazineForm(store: Store, id: String?, go: (String) -> Unit) {
    val loaded by produceState<Magazine?>(null, id) { value = id?.let { store.magazines.get(it) } }
    if (id != null && loaded == null) {
        LibraryPage("screen-magazine-form") {}
        return
    }
    val base = loaded
    var draft by rememberSaveable(id, stateSaver = DraftSaver) {
        mutableStateOf(base?.let { RecipeDraft.of(it) } ?: RecipeDraft())
    }
    var tried by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val back = if (id == null) LIST else "mag:$id"
    LibraryPage("screen-magazine-form") {
        Headline(stringResource(if (id == null) R.string.form_new_title else R.string.form_edit_title), 24)
        Field(stringResource(R.string.form_name), draft.name, "field-name", { draft = draft.copy(name = it) })
        if (tried && draft.name.isBlank()) Meta(stringResource(R.string.form_error_name), "form-error-name")
        Field(stringResource(R.string.form_about), draft.about, "field-about", { draft = draft.copy(about = it) }, singleLine = false)
        Field(stringResource(R.string.form_subjects), draft.subjects, "field-subjects", { draft = draft.copy(subjects = it) })
        Field(stringResource(R.string.form_keywords), draft.keywords, "field-keywords", { draft = draft.copy(keywords = it) })
        Meta(stringResource(R.string.form_keywords_hint))
        Meta(stringResource(R.string.form_language))
        FlowChoices {
            listOf("pt-BR", "en", "es").forEach { code ->
                Choice(languageLabel(code), draft.language == code, "field-language-$code") { draft = draft.copy(language = code) }
            }
        }
        Meta(stringResource(R.string.form_tone))
        FlowChoices {
            listOf("didatico", "equilibrado", "direto").forEach { tone ->
                Choice(toneLabel(tone), draft.tone == tone, "field-tone-$tone") { draft = draft.copy(tone = tone) }
            }
        }
        Field(stringResource(R.string.form_prompt), draft.prompt, "field-prompt", { draft = draft.copy(prompt = it) }, singleLine = false)
        Meta(stringResource(R.string.form_prompt_hint))
        Meta(stringResource(R.string.form_cadence))
        FlowChoices {
            Choice(stringResource(R.string.form_cadence_daily), !draft.weekly, "field-cadence-daily") { draft = draft.copy(weekly = false) }
            Choice(stringResource(R.string.form_cadence_weekly), draft.weekly, "field-cadence-weekly") { draft = draft.copy(weekly = true) }
        }
        Field(stringResource(R.string.form_hour), draft.hour, "field-hour", { draft = draft.copy(hour = it) }, numeric = true)
        if (tried && draft.hourValue == null) Meta(stringResource(R.string.form_error_hour), "form-error-hour")
        BtnRow {
            Btn(stringResource(R.string.form_save), "form-save", primary = true) {
                if (!draft.valid) {
                    tried = true
                } else {
                    scope.launch {
                        val taken = store.magazines.all().map { it.id }
                        store.magazines.save(draft.applyTo(base, uniqueId(slugOf(draft.name), taken)))
                        go(back)
                    }
                }
            }
            Btn(stringResource(R.string.form_cancel), "form-cancel") { go(back) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChoices(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/**
 * Adds a source to a magazine: an address and what the reader liked there. With a [desk], the app then copies the page
 * and asks the AI for the source's collector at once.
 */
@Composable
private fun SourceForm(store: Store, id: String, go: (String) -> Unit, desk: CollectorDesk?) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var liked by rememberSaveable { mutableStateOf("") }
    var refused by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LibraryPage("screen-source-form") {
        Headline(stringResource(R.string.source_form_title), 24)
        Field(stringResource(R.string.source_form_name), name, "source-field-name", { name = it })
        Field(stringResource(R.string.source_form_url), url, "source-field-url", { url = it; refused = false })
        Field(stringResource(R.string.source_form_liked), liked, "source-field-liked", { liked = it }, singleLine = false)
        if (refused) Meta(stringResource(R.string.source_form_error_url), "source-error")
        BtnRow {
            Btn(stringResource(R.string.form_save), "source-save", primary = true) {
                if (!isWebAddress(url)) {
                    refused = true
                } else {
                    scope.launch {
                        val m = store.magazines.get(id) ?: return@launch
                        val added = newSource(m, name, url, liked)
                        store.magazines.save(m.copy(sources = m.sources + added))
                        desk?.write(id, added.id)
                    }
                    go("mag:$id")
                }
            }
            Btn(stringResource(R.string.form_cancel), "source-cancel") { go("mag:$id") }
        }
    }
}
