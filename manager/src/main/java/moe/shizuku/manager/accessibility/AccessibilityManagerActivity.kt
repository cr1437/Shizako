@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package moe.shizuku.manager.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager as SystemAccessibilityManager
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ktx.loge
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.HintStyle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.resolveHintPalette
import rikka.shizuku.Shizuku

/**
 * 列表项数据：一个已安装的无障碍服务。
 *
 * 上游 Shevery 把它写在 Activity 文件里当私有类；这里公开是因为
 * [AccessibilityManagerScreen] 是公开 Composable，参数类型不能是私有的。
 */
@Immutable
data class AccessibilityServiceEntry(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageBitmap,
)

/**
 * 无障碍管理器界面（Jetpack Compose，MD3 / 玻璃双风格）。
 *
 * 上游 Shevery 用的是它自家的 `ShizukuLazyScaffold` / `SettingsGroup` / `SwitchSettingsRow`；
 * 本项目没有这套组件，所以改成基于 [HintPage] / [HintCard] / [HintNote] / [HintSectionTitle]
 * 等现成提示页组件重写，配色统一走 [resolveHintPalette] —— 玻璃风格和 MD3 风格都能看。
 *
 * 功能保持：列出所有已安装的无障碍服务，每项一个启用开关 + 一个「固定（常驻）」开关；
 * 开关通过 [AccessibilityManager] 直接写系统设置，已固定的服务由
 * [AccessibilityDaemonService] 负责被系统杀掉后重新启用。
 *
 * 注意：本页**没有**宿主 CoordinatorLayout + CollapsingToolbar，所以
 * [HintPage] 的 `onCollapsedChange` 在本页不会被用上（保留参数是为了和其它页面签名一致）。
 */
class AccessibilityManagerActivity : AppActivity() {

    /** 自增即触发整页重新读状态（数据都在 SharedPreferences / Settings 里，没有 Flow 可收集）。 */
    private var tick by mutableIntStateOf(0)

    /** Shizuku 掉线时也刷新一次：写入能力没了，界面上的开关要变灰。 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            tick++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 本页没有 XML 顶栏，内容自己管状态栏 / 手势条留白
        WindowCompat.setDecorFitsSystemWindows(window, false)

        Shizuku.addBinderDeadListener(binderDeadListener)

        // 用 ComposeView + setContentView（而不是 activity-compose 的 setContent 扩展）：
        // 本项目没有引入 androidx.activity:activity-compose，ComposeView 是零额外依赖的做法
        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(composeView)

        composeView.setContent {
            val context = LocalContext.current
            // Application 级 Context：守护进程 reconcile 可能跨越 Activity 生命周期
            val application = context.applicationContext

            val refresh = tick
            val pinnedIds = remember(refresh) { AccessibilityKeepAliveStore.getKeepAliveIds() }
            val services = remember(refresh) { loadServices(context, pinnedIds) }
            val enabledIds = remember(refresh) {
                AccessibilityManager.getEnabledServices(context)
                    .map { it.flattenToString() }
                    .toSet()
            }
            val keepAliveEnabled = remember(refresh) { AccessibilityKeepAliveStore.isKeepAliveEnabled() }
            val autoBoot = remember(refresh) { AccessibilityKeepAliveStore.isAutoBootEnabled() }
            // 写系统设置需要 Shizuku 授权，Shizuku 没起来就没法开关服务
            val shizukuRunning = remember(refresh) { Shizuku.pingBinder() }

            var showWriteFailedDialog by rememberSaveable { mutableStateOf(false) }
            var detailsService by remember { mutableStateOf<AccessibilityServiceEntry?>(null) }

            // 玻璃风格下页面底是壁纸，卡片要按当前风格取色
            val palette = remember(refresh) { resolveHintPalette(context) }
            val listState = rememberLazyListState()

            AccessibilityManagerScreen(
                services = services,
                enabledIds = enabledIds,
                pinnedIds = pinnedIds,
                keepAliveEnabled = keepAliveEnabled,
                autoBootEnabled = autoBoot,
                canWrite = shizukuRunning,
                palette = palette,
                listState = listState,
                onCollapsedChange = { /* 本页顶栏是单行，没有折叠大标题需要联动 */ },
                onBack = { finish() },
                onKeepAliveChange = { enabled ->
                    AccessibilityKeepAliveStore.setKeepAliveEnabled(enabled)
                    tick++
                    AccessibilityDaemonService.reconcile(application)
                },
                onAutoBootChange = { enabled ->
                    AccessibilityKeepAliveStore.setAutoBootEnabled(enabled)
                    tick++
                },
                onOpenSystemSettings = { openSystemAccessibilitySettings() },
                onToggleService = { service, target ->
                    // 用户关掉服务时顺手取消固定，否则守护进程会立刻把它重新打开（"打架"）
                    if (!target && AccessibilityKeepAliveStore.isPinned(service.id)) {
                        AccessibilityKeepAliveStore.removePinned(service.id)
                    }
                    val ok = if (target) {
                        AccessibilityManager.enableService(application, service.id)
                    } else {
                        AccessibilityManager.disableService(application, service.id)
                    }
                    if (!ok) {
                        showWriteFailedDialog = true
                    }
                    tick++
                },
                onTogglePin = { service ->
                    if (AccessibilityKeepAliveStore.isPinned(service.id)) {
                        AccessibilityKeepAliveStore.removePinned(service.id)
                    } else {
                        AccessibilityKeepAliveStore.addPinned(service.id)
                    }
                    tick++
                },
                onShowDetails = { service -> detailsService = service },
            )

            if (showWriteFailedDialog) {
                AlertDialog(
                    onDismissRequest = { showWriteFailedDialog = false },
                    title = {
                        Text(stringResource(R.string.accessibility_manager_write_failed_title))
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.accessibility_manager_write_failed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    confirmButton = {
                        Button(onClick = { showWriteFailedDialog = false }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                )
            }

            detailsService?.let { service ->
                AlertDialog(
                    onDismissRequest = { detailsService = null },
                    title = {
                        Text(
                            text = service.label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = service.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = service.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { detailsService = null }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    /** 跳系统无障碍设置页（有些服务只在系统列表里才看得到全部信息）。 */
    private fun openSystemAccessibilitySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            loge("Failed to open accessibility settings", e)
        }
    }

    /** 枚举设备上已安装的无障碍服务，已固定的排在前面。 */
    @Suppress("unused")
    internal fun loadServices(
        context: Context,
        pinnedIds: Set<String>,
    ): List<AccessibilityServiceEntry> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as SystemAccessibilityManager?
            ?: return emptyList()
        val pm = context.packageManager
        return am.getInstalledAccessibilityServiceList().mapNotNull { info ->
            try {
                val componentName = info.resolveInfo.serviceInfo.run {
                    ComponentName(packageName, name)
                }
                val appInfo = pm.getApplicationInfo(componentName.packageName, 0)
                val label = info.resolveInfo.loadLabel(pm)?.toString()
                    ?: pm.getApplicationLabel(appInfo).toString()
                val description = info.loadDescription(pm)?.toString()
                    ?: context.getString(R.string.accessibility_manager_service_no_description)
                val icon = pm.getApplicationIcon(componentName.packageName)
                    .toBitmap(64, 64)
                    .asImageBitmap()
                AccessibilityServiceEntry(
                    id = componentName.flattenToString(),
                    label = label,
                    description = description,
                    icon = icon,
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // 服务来自已被卸载的应用：跳过
                null
            } catch (e: Exception) {
                loge("Failed to load accessibility service", e)
                null
            }
        }.sortedWith(
            compareBy<AccessibilityServiceEntry> { it.id !in pinnedIds }
                .thenBy { it.label.lowercase() }
        )
    }
}

/**
 * 无障碍管理器页面（无状态）。
 *
 * 抽成公开 Composable：状态全部由调用方持有，将来从别处（例如设置页）复用时
 * 不用碰 [AccessibilityManagerActivity] 里的读写实现。
 */
@Composable
fun AccessibilityManagerScreen(
    services: List<AccessibilityServiceEntry>,
    enabledIds: Set<String>,
    pinnedIds: Set<String>,
    keepAliveEnabled: Boolean,
    autoBootEnabled: Boolean,
    canWrite: Boolean,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onAutoBootChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onToggleService: (AccessibilityServiceEntry, Boolean) -> Unit,
    onTogglePin: (AccessibilityServiceEntry) -> Unit,
    onShowDetails: (AccessibilityServiceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = palette.style == HintStyle.GLASS
    // 玻璃风格下顶栏不能有不透明底，否则卡片上方的材质被切一刀
    val barColor = if (glass) androidx.compose.ui.graphics.Color.Transparent else palette.card

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.accessibility_manager_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowLeft,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = palette.onCard,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barColor,
                titleContentColor = palette.onCard,
                navigationIconContentColor = palette.onCard,
            ),
        )

        HintPage(
            listState = listState,
            onCollapsedChange = onCollapsedChange,
            modifier = Modifier.fillMaxSize(),
        ) {
            // ① 常驻总开关 + 开机自启 + 系统设置入口
            item {
                HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                    SettingSwitchRow(
                        palette = palette,
                        title = stringResource(R.string.accessibility_manager_keep_alive_title),
                        summary = stringResource(R.string.accessibility_manager_keep_alive_summary),
                        checked = keepAliveEnabled,
                        enabled = true,
                        onCheckedChange = onKeepAliveChange,
                    )
                    HorizontalDivider(color = palette.outline.copy(alpha = 0.25f))
                    SettingSwitchRow(
                        palette = palette,
                        title = stringResource(R.string.accessibility_manager_auto_boot_title),
                        summary = stringResource(R.string.accessibility_manager_auto_boot_summary),
                        checked = autoBootEnabled,
                        // 总开关关着的时候开机自启没意义，置灰
                        enabled = keepAliveEnabled,
                        onCheckedChange = onAutoBootChange,
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.accessibility_manager_open_system_settings),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenSystemSettings,
                    )
                }
            }

            // ② Shizuku 没连上：写不了系统设置，先说清楚
            if (!canWrite) {
                item {
                    HintNote(
                        palette = palette,
                        iconRes = R.drawable.ic_system_icon,
                        text = stringResource(R.string.accessibility_manager_shizuku_required),
                        errorTone = true,
                        modifier = Modifier.itemEntrance(1),
                    )
                }
            }

            // ③ 服务列表
            item {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(
                        R.string.accessibility_manager_services_group,
                        services.size,
                    ),
                )
            }

            if (services.isEmpty()) {
                item {
                    HintNote(
                        palette = palette,
                        iconRes = R.drawable.ic_system_icon,
                        text = stringResource(R.string.accessibility_manager_services_empty),
                        modifier = Modifier.itemEntrance(2),
                    )
                }
            } else {
                itemsIndexed(services) { index, service ->
                    ServiceCard(
                        service = service,
                        enabled = enabledIds.contains(service.id),
                        pinned = pinnedIds.contains(service.id),
                        canWrite = canWrite,
                        palette = palette,
                        modifier = Modifier.itemEntrance(2 + index),
                        onToggle = { target -> onToggleService(service, target) },
                        onPin = { onTogglePin(service) },
                        onDetails = { onShowDetails(service) },
                    )
                }
            }
        }
    }
}

/** 开关配色：跟随提示页调色板，玻璃风格下才不会跳出一块异色。 */
@Composable
private fun hintSwitchColors(palette: HintPalette) = SwitchDefaults.colors(
    checkedThumbColor = palette.onAccent,
    checkedTrackColor = palette.accent,
    checkedBorderColor = palette.accent,
    uncheckedThumbColor = palette.onCard,
    uncheckedTrackColor = palette.outline.copy(alpha = 0.30f),
    uncheckedBorderColor = palette.outline,
)

/** 一行「标题 + 说明 + 开关」，替代上游的 SwitchSettingsRow。 */
@Composable
private fun SettingSwitchRow(
    palette: HintPalette,
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.onCard,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = hintSwitchColors(palette),
        )
    }
}

/** 单个无障碍服务卡片：图标 + 名称 + 启用开关 + 固定按钮 + 详情。 */
@Composable
private fun ServiceCard(
    service: AccessibilityServiceEntry,
    enabled: Boolean,
    pinned: Boolean,
    canWrite: Boolean,
    palette: HintPalette,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
    onPin: () -> Unit,
    onDetails: () -> Unit,
) {
    HintCard(palette = palette, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 点整行看详情（服务 ID / 描述），和上游一致
                .clickable(onClick = onDetails),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 不加载应用图标位图：一屏几十个服务各解码一张图会让列表首帧很重，
            // 直接用统一的图标 + 名称表达，信息量不变
            Icon(
                imageVector = Icons.Rounded.Accessibility,
                contentDescription = null,
                tint = if (enabled) palette.accent else palette.variant,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = palette.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = service.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                // 没有 Shizuku 就写不进系统设置，直接禁用而不是让用户白点
                enabled = canWrite,
                colors = hintSwitchColors(palette),
            )
        }

        if (enabled || pinned) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (pinned) {
                            R.string.accessibility_manager_pinned
                        } else {
                            R.string.accessibility_manager_not_pinned
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pinned) palette.accent else palette.variant,
                    modifier = Modifier.weight(1f),
                )
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(
                        if (pinned) {
                            R.string.accessibility_manager_unpin
                        } else {
                            R.string.accessibility_manager_pin
                        }
                    ),
                    onClick = onPin,
                )
            }
        }
    }
}
