package dobra.app.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dobra.ai.provider.AiException
import dobra.ai.provider.AiJob
import dobra.ai.provider.Effort
import dobra.ai.provider.JobChoice
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderPresets
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import kotlinx.coroutines.launch

/** "Provedores de IA": add providers with their keys, see only the last four characters, choose who does each job. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiProvidersScreen(ai: AiSetup, onBack: () -> Unit) {
    val colors = LocalReaderColors.current
    val settings by ai.registry.state.collectAsState()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("screen-ai"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Chip(stringResource(R.string.ai_back), false, "ai-back", onBack)
        Text(stringResource(R.string.ai_route), color = colors.ink, fontFamily = AppFonts.display, fontSize = 28.sp)

        Label(stringResource(R.string.ai_added_title))
        if (settings.providers.isEmpty()) {
            Text(stringResource(R.string.ai_none), color = colors.ink2, fontSize = 14.sp)
        }
        settings.providers.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = colors.ink, fontSize = 16.sp, modifier = Modifier.testTag("ai-provider-${p.id}"))
                    Text(
                        stringResource(R.string.ai_key_last4, p.keyLast4),
                        color = colors.ink3,
                        fontFamily = AppFonts.mono,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("ai-last4-${p.id}"),
                    )
                }
                Chip(stringResource(R.string.ai_remove), false, "ai-remove-${p.id}") { ai.registry.remove(p.id) }
            }
        }

        AddProviderForm(ai)

        Label(stringResource(R.string.ai_jobs_title))
        AiJob.entries.forEach { job -> JobSection(ai, job) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderForm(ai: AiSetup) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var priceIn by remember { mutableStateOf("") }
    var priceOut by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProviderKind.OPENAI_STYLE) }
    Label(stringResource(R.string.ai_add_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderPresets.all.forEach { preset ->
            Chip(preset.name, false, "ai-preset-${preset.name.lowercase()}") {
                name = preset.name
                url = preset.baseUrl
                kind = preset.kind
            }
        }
        Chip(stringResource(R.string.ai_preset_other), false, "ai-preset-other") {
            name = ""
            url = ""
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.ai_kind_openai), kind == ProviderKind.OPENAI_STYLE, "ai-kind-openai") { kind = ProviderKind.OPENAI_STYLE }
        Chip(stringResource(R.string.ai_kind_anthropic), kind == ProviderKind.ANTHROPIC, "ai-kind-anthropic") { kind = ProviderKind.ANTHROPIC }
    }
    Field(stringResource(R.string.ai_field_name), name, "ai-name") { name = it }
    Field(stringResource(R.string.ai_field_url), url, "ai-url", KeyboardType.Uri) { url = it }
    Field(stringResource(R.string.ai_field_key), key, "ai-key", KeyboardType.Password, PasswordVisualTransformation()) { key = it }
    Field(stringResource(R.string.ai_field_price_in), priceIn, "ai-price-in", KeyboardType.Decimal) { priceIn = it }
    Field(stringResource(R.string.ai_field_price_out), priceOut, "ai-price-out", KeyboardType.Decimal) { priceOut = it }
    Text(stringResource(R.string.ai_key_hint), color = LocalReaderColors.current.ink3, fontSize = 12.sp)
    Chip(stringResource(R.string.ai_add), true, "ai-add") {
        if (name.isBlank() || url.isBlank() || key.isBlank()) return@Chip
        ai.registry.add(
            name, kind, url, key,
            priceIn.replace(',', '.').toDoubleOrNull() ?: 0.0,
            priceOut.replace(',', '.').toDoubleOrNull() ?: 0.0,
        )
        name = ""; url = ""; key = ""; priceIn = ""; priceOut = ""
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobSection(ai: AiSetup, job: AiJob) {
    val colors = LocalReaderColors.current
    val settings by ai.registry.state.collectAsState()
    val choice = settings.choices[job]
    val scope = rememberCoroutineScope()
    var listed by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<Int?>(null) }
    val tag = "ai-job-${job.name}"
    Text(stringResource(jobLabel(job)), color = colors.ink, fontSize = 16.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.providers.forEach { p ->
            Chip(p.name, choice?.providerId == p.id, "$tag-provider-${p.id}") {
                listed = emptyList()
                ai.registry.choose(job, JobChoice(p.id, choice?.model.orEmpty(), choice?.effort ?: Effort.MEDIUM))
            }
        }
    }
    if (choice != null) {
        Field(stringResource(R.string.ai_job_model), choice.model, "$tag-model") {
            ai.registry.choose(job, choice.copy(model = it.trim()))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Effort.entries.forEach { e ->
                Chip(stringResource(effortLabel(e)), choice.effort == e, "$tag-effort-${e.name}") {
                    ai.registry.choose(job, choice.copy(effort = e))
                }
            }
        }
        Chip(stringResource(R.string.ai_job_list_models), false, "$tag-list") {
            scope.launch {
                error = null
                try {
                    listed = ai.gateway.models(choice.providerId)
                } catch (e: AiException) {
                    error = when (e) {
                        is AiException.KeyRefused -> R.string.ai_error_refused
                        is AiException.RateLimited -> R.string.ai_error_rate
                        else -> R.string.ai_error_other
                    }
                }
            }
        }
        error?.let { Text(stringResource(it), color = colors.accent, fontSize = 13.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listed.forEach { id ->
                Chip(id, choice.model == id, "$tag-pick-$id") { ai.registry.choose(job, choice.copy(model = id)) }
            }
        }
    }
}

private fun jobLabel(job: AiJob) = when (job) {
    AiJob.WRITE_COLLECTORS -> R.string.ai_job_write_collectors
    AiJob.REWRITE -> R.string.ai_job_rewrite
    AiJob.ANSWER -> R.string.ai_job_answer
}

private fun effortLabel(effort: Effort) = when (effort) {
    Effort.LOW -> R.string.ai_effort_low
    Effort.MEDIUM -> R.string.ai_effort_medium
    Effort.HIGH -> R.string.ai_effort_high
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
private fun Field(
    label: String,
    value: String,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Text,
    transform: VisualTransformation = VisualTransformation.None,
    onChange: (String) -> Unit,
) {
    val colors = LocalReaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = colors.ink3, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = transform,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = TextStyle(color = colors.ink, fontSize = 15.sp, fontFamily = AppFonts.mono),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .testTag(tag)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(colors.page, RoundedCornerShape(8.dp))
                .border(1.dp, colors.rule, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}
