package dobra.app.ask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.ai.ask.Conversation
import dobra.ai.ask.StoryReference
import dobra.ai.provider.AiException
import dobra.ai.provider.AiJob
import dobra.ai.provider.Effort
import dobra.app.R
import dobra.app.ai.AiProvidersScreen
import dobra.app.ai.AiSetup
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import dobra.model.Item
import kotlinx.coroutines.launch
import java.util.Locale

/** Opens the "Perguntar à IA" panel for a story (the item and its key). Null where no AI is wired. */
val LocalAskOpener = staticCompositionLocalOf<((Item, String) -> Unit)?> { null }

/** Wraps the app: provides the opener to every story's button and draws the panel over the content when open. */
@Composable
fun AskHost(ai: AiSetup?, content: @Composable () -> Unit) {
    var target by remember { mutableStateOf<Pair<Item, String>?>(null) }
    val opener: ((Item, String) -> Unit)? = if (ai == null) null else { item, key -> target = item to key }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAskOpener provides opener) { content() }
        val open = target
        if (ai != null && open != null) {
            AskPanel(ai, open.first, open.second, onClose = { target = null })
        }
    }
}

/**
 * The panel: choose provider, model and thinking effort, ask, and read the conversation kept with the story.
 * Shows what was sent (the size of the reference) and what the last question cost.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskPanel(ai: AiSetup, item: Item, itemKey: String, onClose: () -> Unit) {
    val colors = LocalReaderColors.current
    var showProviders by remember { mutableStateOf(false) }
    if (showProviders) {
        BackHandler { showProviders = false }
        Box(Modifier.fillMaxSize().background(colors.bg).testTag("ask-providers")) {
            AiProvidersScreen(ai, onBack = { showProviders = false })
        }
        return
    }
    BackHandler(onBack = onClose)
    val settings by ai.registry.state.collectAsState()
    val choice = settings.choices[AiJob.ANSWER]?.takeIf { c -> settings.providers.any { it.id == c.providerId } }
    val reference = remember(item) { StoryReference.of(item) }
    var conversation by remember(itemKey) { mutableStateOf(ai.conversations.load(itemKey)) }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val suggestions = listOf(R.string.ask_suggestion_1, R.string.ask_suggestion_2, R.string.ask_suggestion_3)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("ask-panel"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Chip(stringResource(R.string.ask_close), false, "ask-close", onClose)
        Text(stringResource(R.string.ask_title), color = colors.ink, fontFamily = AppFonts.display, fontSize = 28.sp)

        if (settings.providers.isEmpty()) {
            Text(
                stringResource(R.string.ask_missing_provider), color = colors.ink, fontSize = 15.sp,
                modifier = Modifier.testTag("ask-missing"),
            )
            Chip(stringResource(R.string.ask_open_providers), true, "ask-open-providers") { showProviders = true }
            return@Column
        }

        Label(stringResource(R.string.ask_provider_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.providers.forEach { p ->
                Chip(p.name, choice?.providerId == p.id, "ask-provider-${p.id}") {
                    ai.registry.selectProvider(AiJob.ANSWER, p.id)
                }
            }
            Chip(stringResource(R.string.ask_open_providers), false, "ask-open-providers") { showProviders = true }
        }
        if (choice != null) {
            Label(stringResource(R.string.ask_model_label))
            TextBox(choice.model, "ask-model", singleLine = true) { ai.registry.selectModel(AiJob.ANSWER, it.trim()) }
            Label(stringResource(R.string.ask_effort_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Effort.AUTO, Effort.LOW, Effort.MEDIUM, Effort.HIGH, choice.effort).distinct().forEach { e ->
                    Chip(stringResource(effortLabel(e)), choice.effort == e, "ask-effort-${e.name}") {
                        ai.registry.choose(AiJob.ANSWER, choice.copy(effort = e))
                    }
                }
            }
            if (choice.model.isBlank()) {
                Text(stringResource(R.string.ask_missing_model), color = colors.accent, fontSize = 14.sp, modifier = Modifier.testTag("ask-missing-model"))
            }
        }

        conversation.messages.forEachIndexed { i, m ->
            Column(
                Modifier.fillMaxWidth().background(if (m.fromReader) colors.page else colors.bg, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.rule, RoundedCornerShape(10.dp)).padding(12.dp).testTag("ask-msg-$i"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(if (m.fromReader) R.string.ask_you else R.string.ask_ai),
                    color = colors.ink3, fontFamily = AppFonts.mono, fontSize = 11.sp,
                )
                Text(m.text, color = colors.ink, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEachIndexed { i, id ->
                val text = stringResource(id)
                Chip(text, false, "ask-suggest-$i") { question = text }
            }
        }
        Label(stringResource(R.string.ask_input_label))
        TextBox(question, "ask-input", singleLine = false) { question = it }
        error?.let { Text(stringResource(it), color = colors.accent, fontSize = 14.sp, modifier = Modifier.testTag("ask-error")) }
        val ready = choice != null && choice.model.isNotBlank() && question.isNotBlank() && !busy
        Chip(stringResource(if (busy) R.string.ask_sending else R.string.ask_send), ready, "ask-send") {
            if (!ready) return@Chip
            val asked = question
            busy = true
            error = null
            scope.launch {
                try {
                    conversation = ai.ask.ask(itemKey, item, asked)
                    question = ""
                } catch (e: AiException) {
                    error = when (e) {
                        is AiException.KeyRefused -> R.string.ask_error_refused
                        is AiException.RateLimited -> R.string.ask_error_rate
                        is AiException.Unreachable -> R.string.ask_error_unreachable
                        else -> R.string.ask_error_other
                    }
                }
                busy = false
            }
        }

        SentSection(reference, conversation)
    }
}

@Composable
private fun SentSection(reference: StoryReference, conversation: Conversation) {
    val colors = LocalReaderColors.current
    val last = conversation.messages.lastOrNull { !it.fromReader }
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.rule, RoundedCornerShape(10.dp)).padding(12.dp).testTag("ask-sent"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(stringResource(R.string.ask_sent_title).uppercase())
        Text(
            stringResource(R.string.ask_sent_reference, reference.size, reference.links.size),
            color = colors.ink, fontSize = 14.sp, modifier = Modifier.testTag("ask-sent-reference"),
        )
        Text(stringResource(R.string.ask_sent_never), color = colors.ink2, fontSize = 13.sp)
        if (last != null) {
            val cost = if (last.costUsd > 0.0) String.format(Locale("pt", "BR"), "US$ %.4f", last.costUsd)
            else stringResource(R.string.ask_cost_unknown)
            Text(
                stringResource(R.string.ask_sent_last, last.model, last.inputTokens, last.outputTokens, cost),
                color = colors.ink, fontSize = 14.sp, modifier = Modifier.testTag("ask-sent-cost"),
            )
        }
    }
}

private fun effortLabel(effort: Effort) = when (effort) {
    Effort.LOW -> R.string.ask_effort_low
    Effort.MEDIUM -> R.string.ask_effort_medium
    Effort.HIGH -> R.string.ask_effort_high
    else -> dobra.app.ai.effortLabel(effort)
}

@Composable
private fun Label(text: String) {
    Text(text, color = LocalReaderColors.current.ink3, fontFamily = AppFonts.mono, fontSize = 12.sp)
}

@Composable
private fun Chip(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalReaderColors.current
    Text(
        text = text,
        color = if (on) colors.accentInk else colors.ink,
        fontFamily = AppFonts.mono,
        fontSize = 13.sp,
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 44.dp)
            .background(if (on) colors.accent else colors.page, RoundedCornerShape(999.dp))
            .border(1.dp, if (on) colors.accent else colors.rule, RoundedCornerShape(999.dp))
            .selectable(selected = on, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun TextBox(value: String, tag: String, singleLine: Boolean, onChange: (String) -> Unit) {
    val colors = LocalReaderColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = colors.ink, fontSize = 15.sp, fontFamily = if (singleLine) AppFonts.mono else AppFonts.text),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 44.dp else 84.dp)
            .background(colors.page, RoundedCornerShape(8.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}
