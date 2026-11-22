package moe.shizuku.manager.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页 →「自动化」二级页（规格 F6）。
 *
 * 这一页把 F1（令牌）、F3（白名单 / 元字符）、F4 的超时、F5 的历史、以及调用示例
 * 放在同一屏里：它们是一套东西，分五个二级页反而要多点五次返回。
 *
 * 数据直接读 [AutomationStore]（和 `ComputSettingsPage` 读 `AiExplainUtil` 同一套做法）：
 * 这些数据不参与导航状态，没必要为了「单向数据流」把它们塞进 `SettingsUiState`
 * 再让 Fragment 转一手。
 */
@Composable
fun AutomationPage(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tokens by remember { mutableStateOf<List<TokenView>>(emptyList()) }
    var whitelist by remember { mutableStateOf<List<WhitelistEntry>>(emptyList()) }
    var allowedMeta by remember { mutableStateOf<Set<String>>(emptySet()) }
    var timeoutSec by remember { mutableIntStateOf(30) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 筛选条件（规格 F5：按调用方 / 结果状态 / 时间）
    var filterStatus by remember { mutableStateOf<String?>(null) }
    var filterCaller by remember { mutableStateOf<String?>(null) }
    var filterSince by remember { mutableLongStateOf(0L) }

    // 弹窗状态
    var showNewToken by remember { mutableStateOf(false) }
    var formLabel by remember { mutableStateOf("") }
    var formPackage by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var revealedToken by remember { mutableStateOf<String?>(null) }
    var pendingRotate by remember { mutableStateOf<TokenView?>(null) }
    var pendingRevoke by remember { mutableStateOf<TokenView?>(null) }
    var showAddPrefix by remember { mutableStateOf(false) }
    var prefixInput by remember { mutableStateOf("") }
    var pendingMeta by remember { mutableStateOf<AutomationPolicy.MetaChar?>(null) }
    var toastText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        val snapshot = withContext(Dispatchers.IO) {
            Snapshot(
                tokens = AutomationStore.listTokens(context),
                whitelist = AutomationStore.whitelist(context),
                allowedMeta = AutomationStore.allowedMetachars(context),
                timeoutMs = AutomationStore.timeoutMs(context),
                history = AutomationStore.history(context),
            )
        }
        tokens = snapshot.tokens
        whitelist = snapshot.whitelist
        allowedMeta = snapshot.allowedMeta
        timeoutSec = snapshot.timeoutMs / 1000
        history = snapshot.history
    }

    val filtered = remember(history, filterStatus, filterCaller, filterSince) {
        filterHistory(history, filterCaller, filterStatus, filterSince)
    }
    val callers = remember(history) { historyCallers(history) }
    // 时间筛选项的基准只取一次：每次重组都取一次 now 的话，选中的那一档
    // （value = now - 1h）下一秒就和 selected 对不上，chip 会自己弹回未选中
    val baseNow = remember { System.currentTimeMillis() }

    fun refresh() {
        refreshKey++
    }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onBack)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
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

        // ── 令牌（F1） ──
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.automation_tokens))
                Text(
                    text = stringResource(R.string.automation_tokens_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )

                if (tokens.isEmpty()) {
                    Text(
                        text = stringResource(R.string.automation_token_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    tokens.forEach { token ->
                        TokenRow(
                            palette = palette,
                            token = token,
                            onRotate = { pendingRotate = token },
                            onRevoke = { pendingRevoke = token },
                        )
                    }
                }

                HorizontalDivider(color = palette.outline.copy(alpha = 0.25f))
                AutomationActionRow(
                    palette = palette,
                    title = stringResource(R.string.automation_token_new),
                    enabled = tokens.size < AutomationContract.MAX_TOKENS,
                    onClick = {
                        formLabel = ""
                        formPackage = ""
                        formError = null
                        showNewToken = true
                    },
                )
                Text(
                    text = stringResource(R.string.automation_token_limit, AutomationContract.MAX_TOKENS),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
            }
        }

        // ── 命令白名单（F3） ──
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.automation_whitelist))
                Text(
                    text = stringResource(R.string.automation_whitelist_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                whitelist.forEach { entry ->
                    WhitelistRow(
                        palette = palette,
                        entry = entry,
                        onToggle = { enabled ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AutomationStore.setPrefixEnabled(context, entry.prefix, enabled)
                                }
                                refresh()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AutomationStore.removePrefix(context, entry.prefix)
                                }
                                refresh()
                            }
                        },
                    )
                }
                HorizontalDivider(color = palette.outline.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        prefixInput = ""
                        showAddPrefix = true
                    }) {
                        Text(stringResource(R.string.automation_whitelist_add))
                    }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AutomationStore.restoreDefaultWhitelist(context)
                            }
                            refresh()
                        }
                    }) {
                        Text(stringResource(R.string.automation_whitelist_restore))
                    }
                }
            }
        }

        // ── 元字符（F3） ──
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(3)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.automation_metachar))
                Text(
                    text = stringResource(R.string.automation_metachar_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                FlowRow {
                    AutomationPolicy.MetaChar.entries.forEach { meta ->
                        val checked = allowedMeta.contains(meta.key)
                        FilterChip(
                            selected = checked,
                            onClick = {
                                if (checked) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            AutomationStore.setMetaAllowed(context, meta.key, false)
                                        }
                                        refresh()
                                    }
                                } else {
                                    // 放行是降低安全等级的开关：必须过一次确认框
                                    pendingMeta = meta
                                }
                            },
                            label = { Text(meta.label) },
                            modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                        )
                    }
                }
            }
        }

        // ── 超时（F4） ──
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(4)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.automation_timeout))
                Text(
                    text = stringResource(
                        R.string.automation_timeout_desc,
                        AutomationContract.MIN_TIMEOUT_MS / 1000,
                        AutomationContract.MAX_TIMEOUT_MS / 1000,
                        timeoutSec,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Slider(
                    value = timeoutSec.toFloat(),
                    onValueChange = { timeoutSec = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AutomationStore.setTimeoutMs(context, timeoutSec * 1000)
                            }
                        }
                    },
                    valueRange = (AutomationContract.MIN_TIMEOUT_MS / 1000).toFloat()..
                        (AutomationContract.MAX_TIMEOUT_MS / 1000).toFloat(),
                    // 5 秒一档：5,10,...,300
                    steps = ((AutomationContract.MAX_TIMEOUT_MS - AutomationContract.MIN_TIMEOUT_MS) / 5000) - 1,
                )
            }
        }

        // ── 执行历史（F5） ──
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(5)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.automation_history))
                Text(
                    text = stringResource(R.string.automation_history_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )

                FilterGroupLabel(palette, stringResource(R.string.automation_filter_status))
                ChipRow(
                    options = listOf(
                        null to stringResource(R.string.automation_filter_all),
                        HistoryStatus.OK to stringResource(R.string.automation_status_ok),
                        HistoryStatus.DENIED to stringResource(R.string.automation_status_denied),
                        HistoryStatus.ERROR to stringResource(R.string.automation_status_error),
                        HistoryStatus.TIMEOUT to stringResource(R.string.automation_status_timeout),
                    ),
                    selected = filterStatus,
                    onSelect = { filterStatus = it },
                )

                FilterGroupLabel(palette, stringResource(R.string.automation_filter_caller))
                ChipRow(
                    options = buildList<Pair<String?, String>> {
                        add(null to stringResource(R.string.automation_filter_all))
                        callers.forEach { add(it to it) }
                    },
                    selected = filterCaller,
                    onSelect = { filterCaller = it },
                )

                FilterGroupLabel(palette, stringResource(R.string.automation_filter_time))
                ChipRow(
                    options = timeOptions(baseNow),
                    selected = filterSince,
                    onSelect = { filterSince = it },
                )

                HorizontalDivider(color = palette.outline.copy(alpha = 0.25f))

                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (history.isEmpty()) R.string.automation_history_empty
                            else R.string.automation_none_matching,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    filtered.take(MAX_HISTORY_ROWS).forEach { entry ->
                        HistoryRow(palette, entry)
                    }
                }

                if (history.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AutomationStore.clearHistory(context) }
                            refresh()
                        }
                    }) {
                        Text(stringResource(R.string.automation_history_clear))
                    }
                }
            }
        }

        // ── 调用示例（F6） ──
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(6)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.automation_example))
                Text(
                    text = stringResource(R.string.automation_example_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                // 横向滚动 + 不折行：类名/action 很长，软折行会把
                // `moe.shizuku.manager.automation.ExecShellReceiver` 断成两截，
                // 复制出去就是错的
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.outline.copy(alpha = 0.10f))
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        text = exampleText(context),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onCard,
                        softWrap = false,
                    )
                }
                TextButton(onClick = {
                    copyToClipboard(context, context.getString(R.string.automation_example), exampleText(context))
                    toastText = context.getString(R.string.automation_copied)
                }) {
                    Text(stringResource(R.string.automation_example_copy))
                }
            }
        }

        // ── 平台限制说明（能不能真正认出调用方，取决于 Android 版本） ──
        if (android.os.Build.VERSION.SDK_INT < 34) {
            item {
                HintCard(palette = palette, modifier = Modifier.itemEntrance(7)) {
                    Text(
                        text = stringResource(R.string.automation_verify_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }
            }
        }
    }

    // ══════════════ 弹窗 ══════════════

    if (showNewToken) {
        AlertDialog(
            onDismissRequest = { showNewToken = false },
            title = { Text(stringResource(R.string.automation_token_new)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = formLabel,
                        onValueChange = { formLabel = it },
                        label = { Text(stringResource(R.string.automation_token_label)) },
                        placeholder = { Text(stringResource(R.string.automation_token_label_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = formPackage,
                        onValueChange = { formPackage = it },
                        label = { Text(stringResource(R.string.automation_token_package)) },
                        placeholder = { Text(stringResource(R.string.automation_token_package_hint)) },
                        singleLine = true,
                    )
                    formError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pkg = formPackage.trim()
                    if (pkg.isEmpty()) {
                        formError = context.getString(R.string.automation_token_no_package)
                        return@TextButton
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            AutomationStore.createToken(context, formLabel, pkg)
                        }
                        when (result) {
                            is TokenSecretResult.Success -> {
                                showNewToken = false
                                revealedToken = result.plaintext
                                refresh()
                            }
                            is TokenSecretResult.Failure -> {
                                formError = failureText(context, result.reason)
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.automation_token_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewToken = false }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }

    revealedToken?.let { plain ->
        AlertDialog(
            onDismissRequest = { revealedToken = null },
            title = { Text(stringResource(R.string.automation_token_create)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.automation_token_only_once),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = plain,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onCard,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, context.getString(R.string.automation_token_label), plain)
                    toastText = context.getString(R.string.automation_copied)
                }) {
                    Text(stringResource(R.string.automation_token_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { revealedToken = null }) {
                    Text(stringResource(R.string.automation_close))
                }
            },
        )
    }

    pendingRotate?.let { token ->
        AlertDialog(
            onDismissRequest = { pendingRotate = null },
            title = { Text(stringResource(R.string.automation_token_rotate)) },
            text = { Text(stringResource(R.string.automation_token_rotate_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRotate = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            AutomationStore.rotateToken(context, token.id)
                        }
                        if (result is TokenSecretResult.Success) {
                            revealedToken = result.plaintext
                        }
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.automation_token_rotate))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRotate = null }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }

    pendingRevoke?.let { token ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(stringResource(R.string.automation_token_revoke)) },
            text = { Text(stringResource(R.string.automation_token_revoke_confirm, token.label)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRevoke = null
                    scope.launch {
                        withContext(Dispatchers.IO) { AutomationStore.revokeToken(context, token.id) }
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.automation_token_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }

    pendingMeta?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingMeta = null },
            title = { Text(stringResource(R.string.automation_metachar)) },
            text = { Text(stringResource(R.string.automation_metachar_risk, meta.label)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingMeta = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AutomationStore.setMetaAllowed(context, meta.key, true)
                        }
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.automation_metachar_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMeta = null }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }

    if (showAddPrefix) {
        AlertDialog(
            onDismissRequest = { showAddPrefix = false },
            title = { Text(stringResource(R.string.automation_whitelist_add)) },
            text = {
                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = { prefixInput = it },
                    label = { Text(stringResource(R.string.automation_whitelist)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = prefixInput
                    showAddPrefix = false
                    scope.launch {
                        withContext(Dispatchers.IO) { AutomationStore.addPrefix(context, value) }
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.automation_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPrefix = false }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }

    // 复制成功的轻提示：直接用 AlertDialog 太重，这里用一句话条
    toastText?.let { text ->
        androidx.compose.runtime.LaunchedEffect(text) {
            kotlinx.coroutines.delay(1500)
            toastText = null
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = palette.accent,
            )
        }
    }
}

private const val MAX_HISTORY_ROWS = 30

/** 一次 IO 读出来的快照，避免每块单独 withContext。 */
private class Snapshot(
    val tokens: List<TokenView>,
    val whitelist: List<WhitelistEntry>,
    val allowedMeta: Set<String>,
    val timeoutMs: Int,
    val history: List<HistoryEntry>,
)

@Composable
private fun TokenRow(
    palette: HintPalette,
    token: TokenView,
    onRotate: () -> Unit,
    onRevoke: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = token.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onCard,
                )
                Text(
                    text = token.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = if (token.broken) "***" else token.maskedToken,
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = if (token.lastUsedAt <= 0L) {
                stringResource(R.string.automation_token_never_used)
            } else {
                stringResource(R.string.automation_token_last_used, formatTime(token.lastUsedAt))
            } + " · " + stringResource(R.string.automation_token_created, formatTime(token.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = palette.variant,
        )
        if (token.broken) {
            Text(
                text = stringResource(R.string.automation_token_broken),
                style = MaterialTheme.typography.bodySmall,
                color = palette.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRotate, enabled = !token.broken) {
                Text(stringResource(R.string.automation_token_rotate))
            }
            TextButton(onClick = onRevoke) {
                Text(stringResource(R.string.automation_token_revoke))
            }
        }
    }
}

@Composable
private fun WhitelistRow(
    palette: HintPalette,
    entry: WhitelistEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.prefix,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onCard,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (entry.custom) {
            Text(
                text = stringResource(R.string.automation_whitelist_custom),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.automation_delete))
            }
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.variant,
                uncheckedTrackColor = palette.card,
                uncheckedBorderColor = palette.variant.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun AutomationActionRow(
    palette: HintPalette,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) palette.accent else palette.variant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FilterGroupLabel(palette: HintPalette, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.variant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HistoryRow(palette: HintPalette, entry: HistoryEntry) {
    // stringResource 是 @Composable，塞进 buildString {} 会编译报错（不是 composable 上下文），
    // 所以先取出来
    val truncatedLabel = stringResource(R.string.automation_truncated)
    val exitLabel = exitText(entry)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusLabel(entry.status),
                style = MaterialTheme.typography.bodyMedium,
                color = when (entry.status) {
                    HistoryStatus.OK -> palette.accent
                    HistoryStatus.TIMEOUT -> palette.error
                    HistoryStatus.DENIED -> palette.variant
                    else -> palette.onCard
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(entry.time),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
        }
        if (entry.commandSummary.isNotEmpty()) {
            Text(
                text = entry.commandSummary,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onCard,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = buildString {
                append(entry.callerPackage.ifEmpty { "-" })
                if (entry.callerUid > 0) append(" (").append(entry.callerUid).append(")")
                append(" · ")
                append(entry.tokenLabel)
                append(" · ")
                append(exitLabel)
                if (entry.truncated) {
                    append(" · ")
                    append(truncatedLabel)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.variant,
        )
        entry.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun exitText(entry: HistoryEntry): String {
    val exit = if (entry.exitCode == Int.MIN_VALUE) -1 else entry.exitCode
    return stringResource(R.string.automation_exit_code, exit, entry.durationMs)
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    HistoryStatus.OK -> stringResource(R.string.automation_status_ok)
    HistoryStatus.DENIED -> stringResource(R.string.automation_status_denied)
    HistoryStatus.TIMEOUT -> stringResource(R.string.automation_status_timeout)
    else -> stringResource(R.string.automation_status_error)
}

/** 时间筛选项：不限 / 1h / 24h / 7d（值 = 「从什么时候开始」） */
@Composable
private fun timeOptions(baseNow: Long): List<Pair<Long, String>> {
    return listOf(
        0L to stringResource(R.string.automation_filter_any_time),
        (baseNow - 3_600_000L) to stringResource(R.string.automation_filter_1h),
        (baseNow - 86_400_000L) to stringResource(R.string.automation_filter_24h),
        (baseNow - 7 * 86_400_000L) to stringResource(R.string.automation_filter_7d),
    )
}

private fun failureText(context: Context, reason: String): String = when (reason) {
    "limit" -> context.getString(R.string.automation_token_limit, AutomationContract.MAX_TOKENS)
    "duplicate" -> context.getString(R.string.automation_token_duplicate)
    "no_package" -> context.getString(R.string.automation_token_no_package)
    "no_signature" -> context.getString(R.string.automation_token_no_signature, "")
    else -> context.getString(R.string.automation_token_crypto_failed)
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String =
    if (timestamp <= 0L) "-" else TIME_FORMAT.format(Date(timestamp))

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 调用示例（F6）。故意写在这里而不是 `strings.xml`：
 * 它是代码，不是文案；三份语言文件各维护一份 30 行的转义代码只会更容易写错。
 */
private fun exampleText(context: Context): String {
    val pkg = context.packageName
    val action = AutomationContract.ACTION_EXEC_SHELL
    val resultAction = AutomationContract.ACTION_EXEC_SHELL_RESULT
    val receiver = AutomationContract.RECEIVER_CLASS_NAME
    return """
# 1) 命令行验证（先把令牌绑定到 com.android.shell）
am broadcast -a $action \
  -n $pkg/$receiver \
  --es request_id 3f2b1c1e-8f0a-4c7b-9d2e-5a6b7c8d9e0f \
  --es command "getprop ro.build.version.sdk" \
  --es token 粘贴令牌明文

# 2) Tasker：Send Intent
#    Action  = $action
#    Package = $pkg
#    Class   = $receiver
#    Extra   = request_id / command / token（String）
#              timeout_ms（Int，可选，5000-300000）

// 3) 代码：发请求 + 收结果
Intent i = new Intent("$action");
i.setComponent(new ComponentName("$pkg", "$receiver"));
i.putExtra("request_id", UUID.randomUUID().toString());
i.putExtra("command", "pm list packages -3");
i.putExtra("token", "令牌明文");
i.putExtra("timeout_ms", 30000);
sendBroadcast(i);

registerReceiver(resultReceiver,
        new IntentFilter("$resultAction"),
        Context.RECEIVER_EXPORTED);   // Android 13+ 必须显式声明
    """.trimIndent()
}
