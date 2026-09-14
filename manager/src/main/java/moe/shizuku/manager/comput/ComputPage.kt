package moe.shizuku.manager.comput

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.utils.AiExplainUtil

/**
 * Comput 控制台（**页面本体**，界面与状态都在这里）。
 *
 * 为什么把状态从 Fragment 搬到 Composable：设置里的「Comput 控制台」是二级菜单，
 * 和别的二级页一样走 settings 里那层 `AnimatedContent` 滑动过渡；Fragment 版本
 * （底栏 Tab）也复用同一个页面 —— 两边一份实现，不会再出现"设置里点进来动画不一样"。
 *
 * 执行、宏、AI 解释这些重活都还是现成的对象：`ComputRunner` / `ComputMacroStore` / `AiExplainUtil`。
 */
@Composable
fun ComputPage(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit = {},
    /** 设置里的二级页为 true（顶部有返回行 + 「控制台设置」入口） */
    showBack: Boolean = true,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf(ComputUiState()) }
    var output by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var macros by remember { mutableStateOf<List<ComputMacro>>(emptyList()) }
    var macRotation by remember { mutableIntStateOf(0) }

    // 首次进入：读宏 + 看 key 配没配
    LaunchedEffect(Unit) {
        macros = withContext(Dispatchers.IO) { ComputMacroStore.load() }
        uiState = uiState.copy(aiKeyConfigured = AiExplainUtil.hasApiKey())
    }

    fun startExecution(command: String, historyEntry: String) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        history.remove(historyEntry)
        history.add(0, historyEntry)
        while (history.size > 50) history.removeAt(history.lastIndex)

        output = context.getString(R.string.comput_echo_command, command) + "\n"
        uiState = uiState.copy(
            isRunning = true,
            outputTruncated = false,
            lastOutcome = ComputOutcome.NONE,
            lastCommand = command,
            lastElapsedMs = 0L,
            aiExplanation = "",
        )

        scope.launch {
            val result = ComputRunner.run(
                command = command,
                shizukuUnavailableMessage = context.getString(R.string.comput_error_no_shizuku),
                timeoutMessage = context.getString(R.string.comput_output_timeout),
                cancelledMessage = context.getString(R.string.comput_output_cancelled),
                noOutputMessage = context.getString(R.string.comput_no_output),
                exitMessage = { code -> context.getString(R.string.comput_output_exit, code) },
                onUpdate = { text, _ -> output = text },
            )
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            output = result.text
            uiState = uiState.copy(
                isRunning = false,
                outputTruncated = result.truncated,
                lastExitCode = result.exitCode,
                lastElapsedMs = elapsed,
                lastOutcome = when {
                    result.cancelled -> ComputOutcome.CANCELLED
                    result.timedOut -> ComputOutcome.TIMEOUT
                    !result.finished -> ComputOutcome.FAILED
                    result.exitCode == 0 -> ComputOutcome.OK
                    else -> ComputOutcome.FAILED
                },
            )
        }
    }

    fun persistMacros(next: List<ComputMacro>) {
        macros = next
        macRotation++
        scope.launch(Dispatchers.IO) { ComputMacroStore.save(next) }
    }

    fun explain() {
        if (output.isBlank()) {
            toast(context, context.getString(R.string.comput_ai_no_command))
            return
        }
        val command = uiState.lastCommand
        uiState = uiState.copy(isExplaining = true, aiExplanation = "")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AiExplainUtil.explainCommand(command = command, output = output)
            }
            val text = result.getOrElse { error ->
                when (val reason = AiExplainUtil.errorOf(error)) {
                    is AiExplainUtil.AiError.NoApiKey ->
                        context.getString(R.string.comput_ai_no_key_error)
                    is AiExplainUtil.AiError.Network ->
                        context.getString(R.string.comput_ai_error_network, reason.message)
                    is AiExplainUtil.AiError.Http ->
                        context.getString(R.string.comput_ai_error_http, reason.code, reason.body.take(400))
                    is AiExplainUtil.AiError.Empty ->
                        context.getString(R.string.comput_ai_error_empty)
                }
            }
            uiState = uiState.copy(isExplaining = false, aiExplanation = text)
        }
    }

    val callbacks = remember(macros, uiState.isRunning) {
        ComputCallbacks(
            onRun = { command ->
                val cmd = command.trim()
                if (cmd.isNotBlank() && !uiState.isRunning) startExecution(cmd, cmd)
            },
            onRunMacro = { macro ->
                val cmd = macro.commands.firstOrNull()?.trim().orEmpty()
                if (cmd.isNotBlank() && !uiState.isRunning) startExecution(cmd, cmd)
            },
            onSaveMacro = { name, command ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank() && command.isNotBlank()) {
                    persistMacros(
                        macros.filterNot { it.name == trimmed } +
                            ComputMacro(trimmed, listOf(command.trim())),
                    )
                }
            },
            onRenameMacro = { oldName, newName ->
                val trimmed = newName.trim()
                if (trimmed.isNotBlank() && trimmed != oldName && macros.none { it.name == trimmed }) {
                    persistMacros(macros.map { if (it.name == oldName) it.copy(name = trimmed) else it })
                }
            },
            onDeleteMacro = { name -> persistMacros(macros.filterNot { it.name == name }) },
            onClear = {
                if (!uiState.isRunning) {
                    output = ""
                    uiState = uiState.copy(
                        lastOutcome = ComputOutcome.NONE,
                        lastExitCode = 0,
                        lastElapsedMs = 0L,
                        outputTruncated = false,
                        aiExplanation = "",
                    )
                }
            },
            onStop = { ComputRunner.stop() },
            onCopy = { label, text ->
                copyToClipboard(context, label, text)
                toast(context, context.getString(R.string.comput_copied_output))
            },
            onExplain = { explain() },
            onSaveApiKey = { key ->
                scope.launch {
                    withContext(Dispatchers.IO) { AiExplainUtil.setApiKey(key) }
                    val configured = AiExplainUtil.hasApiKey()
                    uiState = uiState.copy(
                        aiKeyConfigured = configured,
                        aiExplanation = if (key.isBlank()) "" else uiState.aiExplanation,
                    )
                    if (key.isNotBlank()) {
                        toast(context, context.getString(R.string.comput_ai_key_saved))
                    }
                }
            },
        )
    }

    ComputScreen(
        state = uiState,
        palette = palette,
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        output = output,
        history = history,
        macros = macros,
        macRotation = macRotation,
        callbacks = callbacks,
        showBack = showBack,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * 控制台设置（**二级菜单**）：Gemini key / 模型 / 清空宏。
 *
 * 和别的二级页同一套骨架（返回行 + 卡片），所以从控制台点进来的动画也是一样的。
 */
@Composable
fun ComputSettingsPage(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasKey by remember { mutableStateOf(AiExplainUtil.hasApiKey()) }
    var model by remember { mutableStateOf(AiExplainUtil.getModel()) }
    var keyInput by remember { mutableStateOf("") }
    // 模型提供商：默认一批常用预设，最后一项是「自定义（OpenAI 兼容）」
    var provider by remember { mutableStateOf(AiExplainUtil.getProvider()) }
    var providerLabel by remember { mutableStateOf(AiExplainUtil.getProvider().label) }
    var baseUrl by remember { mutableStateOf(AiExplainUtil.getRawBaseUrl()) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var macroCount by remember { mutableIntStateOf(0) }

    /** 正在用 API 拉模型列表 / 拉回来的候选（非空就弹选择框） */
    var fetchingModels by remember { mutableStateOf(false) }
    // 待选模型列表：null = 不显示弹窗。必须走状态 + LaunchedEffect 来弹（原因见下方注释）
    var pendingModelPick by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        macroCount = withContext(Dispatchers.IO) { ComputMacroStore.load().size }
    }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                // 注意：这一行以前少了 clickable —— 看着是返回、点着没反应
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .clickable(onClick = onBack)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_back),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.accent,
                    )
                }
            }
        }

        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.comput_settings_ai),
                )
                // 提供商：默认一批常用大模型，最后一个是「自定义（OpenAI 兼容）」
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProviderDialog = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.comput_ai_provider),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.onCard,
                        )
                        Text(
                            text = providerLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.comput_settings_change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.accent,
                    )
                }

                // 自定义（或想覆盖预设）时才需要填 base URL
                if (provider.protocol == AiExplainUtil.Protocol.OPENAI) {
                    androidx.compose.material3.OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.comput_ai_base_url)) },
                        placeholder = { Text(provider.baseUrl.ifBlank { "https://.../v1" }) },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.variant.copy(alpha = 0.4f),
                            focusedLabelColor = palette.accent,
                            unfocusedLabelColor = palette.variant,
                            focusedTextColor = palette.onCard,
                            unfocusedTextColor = palette.onCard,
                        ),
                    )
                }
                Text(
                    text = stringResource(
                        if (hasKey) R.string.comput_ai_key_present else R.string.comput_ai_key_missing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.comput_ai_key_label)) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.variant.copy(alpha = 0.4f),
                        focusedLabelColor = palette.accent,
                        unfocusedLabelColor = palette.variant,
                        focusedTextColor = palette.onCard,
                        unfocusedTextColor = palette.onCard,
                    ),
                )
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.comput_ai_key_save),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AiExplainUtil.setApiKey(keyInput) }
                            hasKey = AiExplainUtil.hasApiKey()
                            keyInput = ""
                            toast(context, context.getString(R.string.comput_ai_key_saved))
                        }
                    },
                )
                if (hasKey) {
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.comput_ai_key_clear),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { AiExplainUtil.setApiKey("") }
                                hasKey = false
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.comput_settings_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
            }
        }

        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.comput_settings_model),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(AiExplainUtil.DEFAULT_MODEL) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.variant.copy(alpha = 0.4f),
                        focusedLabelColor = palette.accent,
                        unfocusedLabelColor = palette.variant,
                        focusedTextColor = palette.onCard,
                        unfocusedTextColor = palette.onCard,
                    ),
                )
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.comput_settings_save),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AiExplainUtil.setModel(model) }
                            toast(context, context.getString(R.string.comput_settings_saved))
                        }
                    },
                )
                // 模型列表直接用厂商 API 拉：不用再手抄模型名
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(
                        if (fetchingModels) R.string.comput_settings_model_fetching
                        else R.string.comput_settings_model_fetch
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !fetchingModels,
                    onClick = {
                        fetchingModels = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { AiExplainUtil.fetchModels() }
                            }
                            fetchingModels = false
                            val list = result.getOrNull()
                            when {
                                list == null -> toast(
                                    context,
                                    context.getString(
                                        R.string.comput_settings_model_fetch_failed,
                                        result.exceptionOrNull()?.message ?: "unknown",
                                    ),
                                )
                                list.isEmpty() -> toast(
                                    context,
                                    context.getString(R.string.comput_settings_model_fetch_empty),
                                )
                                else -> pendingModelPick = list
                            }
                        }
                    },
                )
            }
        }

        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(3)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.comput_settings_macros),
                )
                Text(
                    text = stringResource(R.string.comput_settings_macros_count, macroCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.comput_settings_macros_clear),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { ComputMacroStore.save(emptyList()) }
                            macroCount = 0
                            toast(context, context.getString(R.string.comput_settings_macros_cleared))
                        }
                    },
                )
            }
        }

        item { Spacer(Modifier.padding(bottom = 96.dp)) }
    }

    // 模型列表：从 API 拉回来以后弹单选，选中即保存。
    // 注意：弹窗只能在 LaunchedEffect 里弹一次，回调只读「快照」——之前直接在
    // 组合里 show()、回调读实时状态：每次重组合都会再叠一层弹窗，上面那个一关
    // 就把状态清空，再点下面残留那个就 EmptyList[1] 崩溃（2026-09-13 实机日志）。
    val modelsToPick = pendingModelPick
    if (modelsToPick != null) {
        LaunchedEffect(modelsToPick) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.comput_settings_model_pick)
                .setSingleChoiceItems(modelsToPick.toTypedArray(), modelsToPick.indexOf(model)) { dialog, which ->
                    val picked = modelsToPick.getOrNull(which) ?: return@setSingleChoiceItems
                    model = picked
                    scope.launch {
                        withContext(Dispatchers.IO) { AiExplainUtil.setModel(picked) }
                        toast(context, context.getString(R.string.comput_settings_model_picked, picked))
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { pendingModelPick = null }
                .show()
                .also { moe.shizuku.manager.ui.glass.GlassWindow.applyIfGlass(it) }
        }
    }

    // 提供商选择弹窗：单选列表，和语言选择那套一致。
    // 同样只在 LaunchedEffect 里弹一次；关掉时把标志复位，避免重组合时反复弹。
    if (showProviderDialog) {
        LaunchedEffect(Unit) {
            val labels = AiExplainUtil.PROVIDERS.map { it.label }.toTypedArray()
            val currentIndex = AiExplainUtil.PROVIDERS.indexOfFirst { it.id == provider.id }
                .coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.comput_ai_provider)
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    val picked = AiExplainUtil.PROVIDERS.getOrNull(which) ?: return@setSingleChoiceItems
                    AiExplainUtil.setProviderId(picked.id)
                    // 换厂商就把「覆盖用的 base URL」清掉，免得拿着上一家的地址请求下一家
                    if (picked.id != "custom") AiExplainUtil.setBaseUrl("")
                    model = picked.defaultModel.ifBlank { model }
                    provider = picked
                    providerLabel = picked.label
                    baseUrl = AiExplainUtil.getRawBaseUrl()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { showProviderDialog = false }
                .show()
                .also { moe.shizuku.manager.ui.glass.GlassWindow.applyIfGlass(it) }
        }
    }
}