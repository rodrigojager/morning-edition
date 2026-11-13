package dobra.app.ai

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dobra.ai.provider.AiException
import dobra.ai.provider.AiJob
import dobra.ai.provider.Effort
import dobra.ai.provider.ModelOption
import dobra.ai.provider.ProviderPreset
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderPresets
import dobra.app.R
import dobra.app.shell.AppFonts
import dobra.app.shell.LocalReaderColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Account connections and per-job model controls; catalog requests are shared by the three jobs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiProvidersScreen(ai: AiSetup, onBack: () -> Unit) {
    val colors = LocalReaderColors.current
    val settings by ai.registry.state.collectAsState()
    val authStatus = ai.chatGpt?.status?.collectAsState()?.value
    val catalogs = remember(settings.providers, authStatus?.account, authStatus?.busy) {
        mutableStateMapOf<String, CatalogState>()
    }
    var refresh by remember { mutableStateOf(0) }
    var configuring by remember { mutableStateOf(false) }
    BackHandler(enabled = configuring) { configuring = false }
    LaunchedEffect(settings.providers, authStatus?.account, authStatus?.busy, refresh) {
        settings.providers.forEach { provider ->
            if (provider.kind != ProviderKind.CHATGPT_OAUTH || authStatus?.busy != true) launch {
                catalogs[provider.id] = CatalogState(loading = true)
                catalogs[provider.id] = try {
                    CatalogState(models = ai.gateway.modelOptions(provider.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    CatalogState(error = when (e) {
                        is AiException.KeyRefused -> R.string.ai_error_refused
                        is AiException.RateLimited -> R.string.ai_error_rate
                        else -> R.string.ai_error_other
                    })
                } catch (_: Exception) {
                    CatalogState(error = R.string.ai_error_other)
                }
            }
        }
    }
    if (configuring) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(20.dp).testTag("ai-model-settings"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Chip(stringResource(R.string.ai_back), false, "ai-model-settings-back") { configuring = false }
            Text(stringResource(R.string.ai_models_settings), color = colors.ink,
                fontFamily = AppFonts.display, fontSize = 28.sp)
            Hint(R.string.ai_jobs_hint)
            Chip(stringResource(R.string.ai_job_list_models), false, "ai-refresh-models") { refresh++ }
            AiJob.entries.forEach { job ->
                JobSection(ai, job, catalogs[settings.choices[job]?.providerId] ?: CatalogState())
            }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(20.dp).testTag("screen-ai"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Chip(stringResource(R.string.ai_back), false, "ai-back", onBack)
        Text(stringResource(R.string.ai_route), color = colors.ink, fontFamily = AppFonts.display, fontSize = 28.sp)
        Hint(R.string.ai_intro)
        ChatGptCard(ai) { configuring = true }
        KeyServiceCard(ai, ProviderPresets.claude, "claude", R.drawable.ic_claude,
            Color(0xFFD97757), R.string.ai_claude_hint, "https://platform.claude.com/settings/keys") { configuring = true }
        KeyServiceCard(ai, ProviderPresets.openCode, "opencode", R.drawable.ic_opencode,
            colors.ink, R.string.ai_opencode_hint, "https://opencode.ai/console") { configuring = true }

        Label(stringResource(R.string.ai_jobs_title))
        Hint(R.string.ai_jobs_hint)
        if (settings.providers.isEmpty()) Hint(R.string.ai_none)
        else {
            Chip(stringResource(R.string.ai_job_list_models), false, "ai-refresh-models") { refresh++ }
            AiJob.entries.forEach { job ->
                JobSection(ai, job, catalogs[settings.choices[job]?.providerId] ?: CatalogState())
            }
        }

        Label(stringResource(R.string.ai_added_title))
        settings.providers.filter { it.kind != ProviderKind.CHATGPT_OAUTH }.forEach { p ->
            Panel("ai-provider-${p.id}") {
                Text(p.name, color = colors.ink, fontSize = 16.sp)
                Text(stringResource(R.string.ai_key_last4, p.keyLast4), color = colors.ink3,
                    fontSize = 12.sp, modifier = Modifier.testTag("ai-last4-${p.id}"))
                Chip(stringResource(R.string.ai_remove), false, "ai-remove-${p.id}") { ai.registry.remove(p.id) }
            }
        }
        var showApi by remember { mutableStateOf(false) }
        Chip(stringResource(R.string.ai_other_providers), showApi, "ai-api-expand") { showApi = !showApi }
        if (showApi) Panel("ai-api-form") { AddProviderForm(ai) }
    }
}

private data class CatalogState(
    val models: List<ModelOption> = emptyList(),
    val loading: Boolean = false,
    val error: Int? = null,
)

@Composable
private fun Panel(tag: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalReaderColors.current
    Column(Modifier.fillMaxWidth().background(colors.page, RoundedCornerShape(16.dp))
        .border(1.dp, colors.rule, RoundedCornerShape(16.dp)).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun ServiceHeading(name: String, icon: Int, tint: Color, subtitle: String) {
    val colors = LocalReaderColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.ink, fontFamily = AppFonts.display, fontSize = 23.sp)
            Text(subtitle, color = colors.ink3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Hint(id: Int) {
    Text(stringResource(id), color = LocalReaderColors.current.ink2, fontSize = 14.sp, lineHeight = 20.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatGptCard(ai: AiSetup, onConfigure: () -> Unit) {
    val auth = ai.chatGpt ?: return
    val status by auth.status.collectAsState()
    val settings by ai.registry.state.collectAsState()
    val connected = settings.providers.any { it.kind == ProviderKind.CHATGPT_OAUTH }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Panel("ai-chatgpt-card") {
        ServiceHeading("ChatGPT", R.drawable.ic_chatgpt, LocalReaderColors.current.ink,
            stringResource(if (connected) R.string.ai_chatgpt_connected else R.string.ai_plan_login))
        Hint(R.string.ai_chatgpt_hint)
        status.account?.let {
            Text(stringResource(R.string.ai_chatgpt_account, it), color = LocalReaderColors.current.ink2, fontSize = 14.sp)
        }
        if (connected) {
            Chip(stringResource(R.string.ai_configure_models), true, "ai-chatgpt-models", onConfigure)
            ProviderSummary(ai, ProviderKind.CHATGPT_OAUTH)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connected || status.busy) Chip(stringResource(
                if (status.busy) R.string.ai_chatgpt_cancel else R.string.ai_chatgpt_login
            ), !status.busy, "ai-chatgpt-login") {
                if (status.busy) auth.cancel()
                else auth.start { address ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                }
            }
            if (connected) Chip(stringResource(R.string.ai_disconnect), false, "ai-remove-chatgpt") {
                scope.launch { auth.disconnect() }
            }
        }
        if (status.busy) Hint(R.string.ai_chatgpt_waiting)
        if (status.error) Hint(R.string.ai_chatgpt_error)
        if (status.revocationUnconfirmed) Hint(R.string.ai_chatgpt_revoke_warning)
        if (connected) Chip(stringResource(R.string.ai_chatgpt_usage), false, "ai-chatgpt-usage") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
        }
    }
}

@Composable
private fun KeyServiceCard(ai: AiSetup, preset: ProviderPreset, tag: String, icon: Int, tint: Color, hint: Int,
    keyUrl: String, onConfigure: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settings by ai.registry.state.collectAsState()
    val connected = settings.providers.any { it.kind == preset.kind }
    Panel("ai-$tag-card") {
        ServiceHeading(preset.name, icon, tint,
            stringResource(if (connected) R.string.ai_api_connected else R.string.ai_api_login))
        Hint(hint)
        if (connected) {
            Chip(stringResource(R.string.ai_configure_models), true, "ai-$tag-models", onConfigure)
            ProviderSummary(ai, preset.kind)
        }
        Chip(stringResource(if (connected) R.string.ai_add_another_key else R.string.ai_connect_key),
            expanded, "ai-$tag-expand") { expanded = !expanded }
        if (expanded) {
            Hint(R.string.ai_url_prefilled)
            Chip(stringResource(R.string.ai_get_key), false, "ai-$tag-get-key") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
            }
            AddProviderForm(ai, preset, "ai-$tag") { expanded = false }
        }
    }
}

@Composable
private fun ProviderSummary(ai: AiSetup, kind: ProviderKind) {
    val settings by ai.registry.state.collectAsState()
    AiJob.entries.forEach { job ->
        val choice = settings.choices[job]
        val active = settings.providers.any { it.id == choice?.providerId && it.kind == kind }
        Text(stringResource(R.string.ai_job_summary, stringResource(jobLabel(job)),
            if (active && !choice?.model.isNullOrBlank()) choice.model else stringResource(R.string.ai_job_unassigned)),
            color = LocalReaderColors.current.ink2, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderForm(
    ai: AiSetup,
    initial: ProviderPreset = ProviderPresets.all.first(),
    tag: String = "ai",
    onAdded: () -> Unit = {},
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf("") }
    var priceIn by remember { mutableStateOf("") }
    var priceOut by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(initial.kind) }
    Label(stringResource(R.string.ai_add_title))
    if (tag == "ai") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderPresets.all.forEach { preset ->
            Chip(preset.name, false, "ai-preset-${preset.name.lowercase()}") {
                name = preset.name
                url = preset.baseUrl
                kind = preset.kind
            }
        }
        Chip(stringResource(R.string.ai_preset_other), false, "ai-preset-other") {
            name = ""
            url = ProviderPresets.all.first().baseUrl
            kind = ProviderKind.OPENAI_STYLE
        }
    }
    if (tag == "ai" && kind != ProviderKind.OPENCODE) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.ai_kind_openai), kind == ProviderKind.OPENAI_STYLE, "ai-kind-openai") {
            if (url == ProviderPresets.claude.baseUrl) url = ProviderPresets.all.first().baseUrl
            kind = ProviderKind.OPENAI_STYLE
        }
        Chip(stringResource(R.string.ai_kind_anthropic), kind == ProviderKind.ANTHROPIC, "ai-kind-anthropic") {
            if (url == ProviderPresets.all.first().baseUrl) url = ProviderPresets.claude.baseUrl
            kind = ProviderKind.ANTHROPIC
        }
    }
    Field(stringResource(R.string.ai_field_name), name, "$tag-name") { name = it }
    Field(stringResource(R.string.ai_field_url), url, "$tag-url", KeyboardType.Uri) { url = it }
    Field(stringResource(R.string.ai_field_key), key, "$tag-key", KeyboardType.Password, PasswordVisualTransformation()) { key = it }
    var prices by remember { mutableStateOf(false) }
    Chip(stringResource(R.string.ai_optional_prices), prices, "$tag-prices") { prices = !prices }
    if (prices) {
        Field(stringResource(R.string.ai_field_price_in), priceIn, "$tag-price-in", KeyboardType.Decimal) { priceIn = it }
        Field(stringResource(R.string.ai_field_price_out), priceOut, "$tag-price-out", KeyboardType.Decimal) { priceOut = it }
    }
    Text(stringResource(R.string.ai_key_hint), color = LocalReaderColors.current.ink3, fontSize = 12.sp)
    Chip(stringResource(R.string.ai_add), true, "$tag-add") {
        if (name.isBlank() || url.isBlank() || key.isBlank()) return@Chip
        ai.registry.add(
            name, kind, url, key,
            priceIn.replace(',', '.').toDoubleOrNull() ?: 0.0,
            priceOut.replace(',', '.').toDoubleOrNull() ?: 0.0,
        )
        key = ""; priceIn = ""; priceOut = ""
        onAdded()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobSection(ai: AiSetup, job: AiJob, catalog: CatalogState) {
    val colors = LocalReaderColors.current
    val settings by ai.registry.state.collectAsState()
    val choice = settings.choices[job]
    val tag = "ai-job-${job.name}"
    Panel(tag) {
        Text(stringResource(jobLabel(job)), color = colors.ink, fontSize = 18.sp, fontFamily = AppFonts.display)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.providers.forEach { p ->
                Chip(p.name, choice?.providerId == p.id, "$tag-provider-${p.id}") {
                    ai.registry.selectProvider(job, p.id)
                }
            }
        }
        if (choice == null) Hint(R.string.ai_choose_provider)
        else {
            val model = catalog.models.firstOrNull { it.id == choice.model }
            Label(stringResource(R.string.ai_model_title))
            if (catalog.loading) Hint(R.string.ai_models_loading)
            catalog.error?.let { Hint(it) }
            if (!catalog.loading && catalog.error == null && catalog.models.isEmpty()) Hint(R.string.ai_models_empty)
            var picker by remember(choice.providerId) { mutableStateOf(false) }
            if (catalog.models.isNotEmpty()) {
                Chip(model?.name ?: stringResource(R.string.ai_choose_model), true, "$tag-model-picker") { picker = true }
                if (picker) ModelPicker(catalog.models, choice.model, tag, onClose = { picker = false }) { selected ->
                    ai.registry.selectModel(job, selected)
                    picker = false
                }
            }
            // A manual id remains useful for compatible endpoints with no model-discovery route.
            var manual by remember(choice.providerId) { mutableStateOf(false) }
            if (catalog.models.isEmpty() || manual || (choice.model.isNotBlank() && model == null)) {
                Field(stringResource(R.string.ai_job_model), choice.model, "$tag-model") {
                    val id = it.trim()
                    val known = catalog.models.firstOrNull { option -> option.id == id }
                    if (known != null) ai.registry.selectModel(job, known) else ai.registry.selectModel(job, id)
                }
            } else Chip(stringResource(R.string.ai_model_manual), manual, "$tag-manual") { manual = !manual }
            Label(stringResource(R.string.ai_reasoning_title))
            val efforts = model?.efforts ?: listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(Effort.AUTO) + efforts).distinct().forEach { effort ->
                    Chip(stringResource(effortLabel(effort)), choice.effort == effort, "$tag-effort-${effort.name}") {
                        ai.registry.choose(job, choice.copy(effort = effort))
                    }
                }
            }
            if (choice.effort != Effort.AUTO && choice.effort !in efforts) Hint(R.string.ai_effort_unavailable)
            if (model?.efforts == null) Hint(R.string.ai_effort_unknown)
            run {
                Label(stringResource(R.string.ai_speed_title))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.ai_automatic), choice.serviceTier == null, "$tag-speed-auto") {
                        ai.registry.choose(job, choice.copy(serviceTier = null))
                    }
                    model?.serviceTiers.orEmpty().forEach { tier ->
                        val label = when (tier.id) {
                            "fast" -> stringResource(R.string.ai_speed_fast)
                            "priority" -> stringResource(R.string.ai_speed_priority)
                            "default" -> stringResource(R.string.ai_speed_default)
                            else -> tier.name
                        }
                        Chip(label, choice.serviceTier == tier.id, "$tag-speed-${tier.id}") {
                            ai.registry.choose(job, choice.copy(serviceTier = tier.id))
                        }
                    }
                }
                if (model?.serviceTiers.isNullOrEmpty()) Hint(R.string.ai_speed_unknown)
                else Hint(R.string.ai_speed_cost)
                if (choice.serviceTier != null && model?.serviceTiers.orEmpty().none { it.id == choice.serviceTier }) {
                    Hint(R.string.ai_speed_unavailable)
                }
            }
            if (choice.model.isNotBlank()) Chip(stringResource(R.string.ai_apply_all), false, "$tag-apply-all") {
                ai.registry.applyToAllJobs(job)
            }
        }
    }
}

@Composable
private fun ModelPicker(models: List<ModelOption>, selected: String, tag: String, onClose: () -> Unit, onPick: (ModelOption) -> Unit) {
    var search by remember { mutableStateOf("") }
    val filtered = models.filter { it.name.contains(search, ignoreCase = true) || it.id.contains(search, ignoreCase = true) }
    Dialog(onDismissRequest = onClose) {
        Panel("$tag-model-dialog") {
            Text(stringResource(R.string.ai_choose_model), color = LocalReaderColors.current.ink,
                fontFamily = AppFonts.display, fontSize = 23.sp)
            Field(stringResource(R.string.ai_model_search), search, "$tag-model-search") { search = it }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("$tag-model-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { option ->
                    Chip(option.name, selected == option.id, "$tag-pick-${option.id}") { onPick(option) }
                }
            }
            if (filtered.isEmpty()) Hint(R.string.ai_model_no_matches)
            Chip(stringResource(R.string.ai_close), false, "$tag-model-close", onClose)
        }
    }
}

private fun jobLabel(job: AiJob) = when (job) {
    AiJob.WRITE_COLLECTORS -> R.string.ai_job_write_collectors
    AiJob.REWRITE -> R.string.ai_job_rewrite
    AiJob.ANSWER -> R.string.ai_job_answer
}

internal fun effortLabel(effort: Effort) = when (effort) {
    Effort.LOW -> R.string.ai_effort_low
    Effort.MEDIUM -> R.string.ai_effort_medium
    Effort.HIGH -> R.string.ai_effort_high
    Effort.AUTO -> R.string.ai_automatic
    Effort.NONE -> R.string.ai_effort_none
    Effort.MINIMAL -> R.string.ai_effort_minimal
    Effort.XHIGH -> R.string.ai_effort_xhigh
    Effort.MAX -> R.string.ai_effort_max
    Effort.ULTRA -> R.string.ai_effort_ultra
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
