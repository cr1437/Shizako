package moe.shizuku.manager.module

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance

/**
 * ADB Modules 主界面（重写版）。
 *
 * 骨架和设置里其它二级页**完全一样**：[HintPage] + 一张返回行卡片 + 若干卡片，
 * 所以从设置里点进来时走的是同一个 `AnimatedContent` 滑动过渡（不会另开一套转场），
 * 页面的入场也是同一套 `itemEntrance` 弹簧。
 *
 * 模块卡片：banner（有才显示）、名字 / 版本 / 体积 / 作者、启用开关、
 * Action / Service / WebUI / 日志 / 删除，长按卡片切换「信任」。
 */
@Composable
fun ModulesScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenWebUi: (String) -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onOpenPolicy: () -> Unit = {},
    /** 作为设置二级页时为 true（顶部有返回行）；放进底栏当顶层页时为 false */
    showBack: Boolean = true,
    /** 宿主 Fragment 选好的 ZIP（null = 没在选）；由宿主注册选择器，本页只负责安装 */
    pendingZip: Uri? = null,
    onZipConsumed: () -> Unit = {},
    onPickZip: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modules by remember { mutableStateOf<List<AdbModule>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<AdbModuleManager.LiveModuleOutput?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }

    // 执行中的实时输出（Action 走流式）
    LaunchedEffect(Unit) {
        AdbModuleManager.liveOutput.collect { live = it }
    }

    LaunchedEffect(reload) {
        modules = withContext(Dispatchers.IO) {
            runCatching { AdbModuleManager.listModules(context) }.getOrDefault(emptyList())
        }
    }

    // 安装 ZIP 的流程：选择器由**宿主 Fragment** 在初始化时注册（Compose 里临时注册会抛
    // IllegalStateException：LifecycleOwner 必须在 STARTED 之前注册），这里只负责
    // 拿到 uri 之后真正安装。
    LaunchedEffect(pendingZip) {
        val uri = pendingZip ?: return@LaunchedEffect
        onZipConsumed()
        busy = "install"
        val error = withContext(Dispatchers.IO) {
            runCatching { AdbModuleManager.install(context, uri); null }
                .getOrElse { it.message ?: context.getString(R.string.adb_modules_install_failed) }
        }
        busy = null
        message = error ?: context.getString(R.string.adb_modules_install_success)
        reload++
    }
    fun runScript(module: AdbModule, service: Boolean) {
        scope.launch {
            busy = module.id
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if (service) AdbModuleManager.runService(module)
                    else AdbModuleManager.runActionStreaming(module)
                    null
                }.getOrElse { it.message ?: context.getString(R.string.adb_modules_action_failed) }
            }
            busy = null
            message = error
            reload++
        }
    }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        // 返回行：和设置二级页一模一样的写法
        if (showBack) item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
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
                    Spacer(Modifier.width(12.dp))
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
                Text(
                    text = stringResource(R.string.adb_modules_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Spacer(Modifier.height(10.dp))
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(
                        if (busy == "install") R.string.adb_modules_installing else R.string.adb_modules_install_zip,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = busy == null,
                    onClick = onPickZip,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.settings_module_policy),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenPolicy,
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.settings_module_catalog),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenCatalog,
                    )
                }
            }
        }

        message?.let { text ->
            item {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_info_24,
                    text = text,
                    modifier = Modifier.itemEntrance(1),
                )
            }
        }

        if (modules.isEmpty()) {
            item {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_help_outline_24dp,
                    text = stringResource(R.string.adb_modules_empty_title),
                    secondary = stringResource(R.string.adb_modules_empty_summary),
                    modifier = Modifier.itemEntrance(2),
                )
            }
        } else {
            item {
                HintSectionTitle(
                    text = stringResource(R.string.adb_modules_count, modules.size),
                    palette = palette,
                    modifier = Modifier.itemEntrance(2),
                )
            }
            items(modules.size) { index ->
                val module = modules[index]
                ModuleCard(
                    palette = palette,
                    module = module,
                    busy = busy == module.id,
                    expanded = expanded == module.id,
                    live = if (live?.moduleId == module.id) live else null,
                    onToggleExpand = {
                        expanded = if (expanded == module.id) null else module.id
                    },
                    onToggleEnabled = { enabled ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { AdbModuleManager.setEnabled(module, enabled) }
                            }
                            reload++
                        }
                    },
                    onAction = { runScript(module, service = false) },
                    onService = { runScript(module, service = true) },
                    onWebUi = { onOpenWebUi(module.id) },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { AdbModuleManager.delete(module) }
                            }
                            message = context.getString(R.string.adb_modules_delete_done, module.name)
                            reload++
                        }
                    },
                    onToggleTrust = {
                        ModuleSettings.setModuleTrusted(
                            module.id,
                            !ModuleSettings.isModuleTrusted(module.id),
                        )
                        reload++
                    },
                    modifier = Modifier.itemEntrance(3 + index),
                )
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun ModuleCard(
    palette: HintPalette,
    module: AdbModule,
    busy: Boolean,
    expanded: Boolean,
    live: AdbModuleManager.LiveModuleOutput?,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onAction: () -> Unit,
    onService: () -> Unit,
    onWebUi: () -> Unit,
    onDelete: () -> Unit,
    onToggleTrust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trusted = ModuleSettings.isModuleTrusted(module.id)

    HintCard(palette = palette, modifier = modifier) {
        module.banner?.let { banner ->
            val image = remember(banner.path, banner.lastModified()) {
                runCatching { android.graphics.BitmapFactory.decodeFile(banner.path) }.getOrNull()
            }
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.15f)),
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(module.id) {
                    detectTapGestures(
                        onTap = { onToggleExpand() },
                        onLongPress = { onToggleTrust() },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onCard,
                    )
                    if (trusted) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(palette.accent.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.adb_modules_tag_trusted),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                            )
                        }
                    }
                }
                Text(
                    text = buildString {
                        append(module.id)
                        module.version?.let { append(" · v$it") }
                        append(" · ${module.formattedSize}")
                        module.author?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
            }
            Switch(
                checked = module.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = palette.onAccent,
                    checkedTrackColor = palette.accent,
                    uncheckedThumbColor = palette.variant,
                    uncheckedTrackColor = palette.card,
                    uncheckedBorderColor = palette.variant.copy(alpha = 0.5f),
                ),
            )
        }

        module.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onCard,
            )
        }

        // 被策略挡住时说明原因，而不是只给一个不能按的按钮
        when {
            !module.enabled -> Text(
                text = stringResource(R.string.adb_modules_reason_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )

            module.hasService && !ModuleSettings.canRunService(module) -> Text(
                text = stringResource(R.string.adb_modules_reason_service_policy),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (module.hasAction) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(
                        if (busy) R.string.adb_modules_running else R.string.adb_modules_action,
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = !busy && module.enabled,
                    onClick = onAction,
                )
            }
            if (module.hasService) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.adb_modules_service),
                    modifier = Modifier.weight(1f),
                    enabled = !busy && module.enabled && ModuleSettings.canRunService(module),
                    onClick = onService,
                )
            }
            if (module.hasWebUi) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.adb_modules_webui),
                    modifier = Modifier.weight(1f),
                    enabled = ModuleSettings.canOpenWebUi(module),
                    onClick = onWebUi,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.adb_modules_panel_action),
                modifier = Modifier.weight(1f),
                onClick = onToggleExpand,
            )
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.adb_modules_delete),
                modifier = Modifier.weight(1f),
                onClick = onDelete,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            ScriptLog(palette = palette, module = module, live = live)
        }
    }
}

/** 展开后的日志：执行中的实时输出优先，否则显示上次落盘的日志（可切 Action / Service）。 */
@Composable
private fun ScriptLog(
    palette: HintPalette,
    module: AdbModule,
    live: AdbModuleManager.LiveModuleOutput?,
) {
    val context = LocalContext.current
    var which by remember { mutableStateOf(0) }
    val text = remember(which, live?.text, module.id) {
        when {
            live != null -> live.text
            which == 0 -> runCatching { module.lastActionLog.takeIf { it.isFile }?.readText() }
                .getOrNull() ?: context.getString(R.string.adb_modules_no_output)
            else -> runCatching { module.lastServiceLog.takeIf { it.isFile }?.readText() }
                .getOrNull() ?: context.getString(R.string.adb_modules_no_output)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.adb_modules_panel_action),
                modifier = Modifier.weight(1f),
                onClick = { which = 0 },
            )
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.adb_modules_panel_service),
                modifier = Modifier.weight(1f),
                onClick = { which = 1 },
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.onCard.copy(alpha = 0.06f))
                .padding(12.dp),
        ) {
            Text(
                text = text.takeLast(20_000),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.onCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 从 Compose 的 Context 往上找宿主 Activity（本项目没有 activity-compose，只能这么拿）。 */
