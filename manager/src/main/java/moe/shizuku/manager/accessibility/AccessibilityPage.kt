package moe.shizuku.manager.accessibility

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import rikka.shizuku.Shizuku

/**
 * 无障碍管理器（**重写版**，设置里的二级页）。
 *
 * 骨架和其它二级页完全一致：[HintPage] + 返回行卡片 + 分组卡片，
 * 所以从设置进来时走的是同一个 `AnimatedContent` 滑动过渡。
 *
 * 三块内容：
 * 1. 守护：保持服务常驻（被系统关掉就再打开）+ 开机自启；
 * 2. 服务列表：每个已安装的无障碍服务一行（图标 / 名称 / 描述 / 固定 / 开关）；
 * 3. 底部：打开系统无障碍设置（服务自身的配置只能在那里改）。
 *
 * 读写的是 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，需要 WRITE_SECURE_SETTINGS，
 * 所以 Shizuku 没运行时只让看、不让改（开关置灰并说明原因）。
 */
@Composable
fun AccessibilityPage(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext

    var refresh by remember { mutableIntStateOf(0) }
    var writeFailed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<AccessibilityServiceEntry?>(null) }

    val pinnedIds = remember(refresh) { AccessibilityKeepAliveStore.getKeepAliveIds() }
    val services = remember(refresh) { loadAccessibilityServices(context, pinnedIds) }
    val enabledIds = remember(refresh) {
        AccessibilityManager.getEnabledServices(context).map { it.flattenToString() }.toSet()
    }
    val keepAliveEnabled = remember(refresh) { AccessibilityKeepAliveStore.isKeepAliveEnabled() }
    val autoBoot = remember(refresh) { AccessibilityKeepAliveStore.isAutoBootEnabled() }
    val canWrite = remember(refresh) { Shizuku.pingBinder() }

    fun toggleService(service: AccessibilityServiceEntry, target: Boolean) {
        if (!canWrite) {
            writeFailed = true
            return
        }
        // 关掉服务时顺手取消固定，否则守护进程会立刻把它再打开（两边打架）
        if (!target && AccessibilityKeepAliveStore.isPinned(service.id)) {
            AccessibilityKeepAliveStore.removePinned(service.id)
        }
        val ok = if (target) {
            AccessibilityManager.enableService(application, service.id)
        } else {
            AccessibilityManager.disableService(application, service.id)
        }
        if (!ok) writeFailed = true
        refresh++
    }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        // 返回行
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

        if (!canWrite) {
            item {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_warning_24,
                    text = stringResource(R.string.accessibility_manager_shizuku_required),
                    modifier = Modifier.itemEntrance(1),
                )
            }
        }

        // 守护开关
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.accessibility_manager_keep_alive_group),
                )
                SwitchRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_notifications_active_24,
                    title = stringResource(R.string.accessibility_manager_keep_alive_title),
                    summary = stringResource(R.string.accessibility_manager_keep_alive_summary),
                    checked = keepAliveEnabled,
                    enabled = canWrite,
                    onChange = { enabled ->
                        AccessibilityKeepAliveStore.setKeepAliveEnabled(enabled)
                        refresh++
                        AccessibilityDaemonService.reconcile(application)
                    },
                )
                SwitchRow(
                    palette = palette,
                    iconRes = R.drawable.ic_server_restart,
                    title = stringResource(R.string.accessibility_manager_auto_boot_title),
                    summary = stringResource(R.string.accessibility_manager_auto_boot_summary),
                    checked = autoBoot,
                    enabled = canWrite,
                    onChange = { enabled ->
                        AccessibilityKeepAliveStore.setAutoBootEnabled(enabled)
                        refresh++
                    },
                )
            }
        }

        // 服务列表
        item {
            HintSectionTitle(
                palette = palette,
                text = stringResource(
                    R.string.accessibility_manager_services_group,
                    services.size,
                ),
                modifier = Modifier.itemEntrance(2),
            )
        }

        if (services.isEmpty()) {
            item {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_help_outline_24dp,
                    text = stringResource(R.string.accessibility_manager_services_empty),
                    modifier = Modifier.itemEntrance(2),
                )
            }
        } else {
            items(services.size) { index ->
                val service = services[index]
                val enabled = service.id in enabledIds
                val pinned = service.id in pinnedIds

                HintCard(palette = palette, modifier = Modifier.itemEntrance(2 + index)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = service.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = service.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.onCard,
                            )
                            Text(
                                text = if (pinned) {
                                    stringResource(R.string.accessibility_manager_pinned)
                                } else {
                                    stringResource(R.string.accessibility_manager_not_pinned)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pinned) palette.accent else palette.variant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = canWrite,
                            onCheckedChange = { target -> toggleService(service, target) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.onAccent,
                                checkedTrackColor = palette.accent,
                                uncheckedThumbColor = palette.variant,
                                uncheckedTrackColor = palette.card,
                                uncheckedBorderColor = palette.variant.copy(alpha = 0.5f),
                            ),
                        )
                    }

                    Text(
                        text = service.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallAction(
                            palette = palette,
                            text = stringResource(
                                if (pinned) {
                                    R.string.accessibility_manager_unpin
                                } else {
                                    R.string.accessibility_manager_pin
                                },
                            ),
                            modifier = Modifier.weight(1f),
                            enabled = canWrite,
                            onClick = {
                                if (pinned) {
                                    AccessibilityKeepAliveStore.removePinned(service.id)
                                } else {
                                    AccessibilityKeepAliveStore.addPinned(service.id)
                                }
                                refresh++
                            },
                        )
                        SmallAction(
                            palette = palette,
                            text = stringResource(R.string.accessibility_manager_details),
                            modifier = Modifier.weight(1f),
                            onClick = { details = service },
                        )
                    }
                }
            }
        }

        // 系统设置入口
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(3)) {
                Text(
                    text = stringResource(R.string.accessibility_manager_system_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Spacer(Modifier.height(8.dp))
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.accessibility_manager_open_system_settings),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                                ),
                            )
                        }
                    },
                )
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }

    if (writeFailed) {
        AlertDialog(
            onDismissRequest = { writeFailed = false },
            title = { Text(stringResource(R.string.accessibility_manager_write_failed_title)) },
            text = { Text(stringResource(R.string.accessibility_manager_write_failed)) },
            confirmButton = {
                TextButton(onClick = { writeFailed = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    details?.let { service ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(service.label) },
            text = { Text(service.id) },
            confirmButton = {
                TextButton(onClick = { details = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/** 图标 + 标题 + 说明 + 开关（和设置页其它开关行同一套观感） */
@Composable
private fun SwitchRow(
    palette: HintPalette,
    iconRes: Int,
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(palette.accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) palette.onCard else palette.variant,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
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

/** 小号次要按钮（固定 / 详情） */
@Composable
private fun SmallAction(
    palette: HintPalette,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.onCard.copy(alpha = if (enabled) 0.08f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) palette.onCard else palette.variant.copy(alpha = 0.6f),
        )
    }
}

/**
 * 读取设备上已安装的无障碍服务（含图标 / 描述）。
 *
 * [AccessibilityManagerActivity] 里那份是成员函数，设置页拿不到 —— 这里提成顶层函数，
 * 两个宿主（Activity / 设置二级页）共用同一份加载逻辑。
 */
internal fun loadAccessibilityServices(
    context: android.content.Context,
    pinnedIds: Set<String>,
): List<AccessibilityServiceEntry> {
    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager ?: return emptyList()
    val pm = context.packageManager
    return am.installedAccessibilityServiceList.mapNotNull { info ->
        try {
            val componentName = info.resolveInfo.serviceInfo.run {
                android.content.ComponentName(packageName, name)
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
        } catch (e: Throwable) {
            null
        }
    }.sortedWith(
        compareBy<AccessibilityServiceEntry> { it.id !in pinnedIds }.thenBy { it.label.lowercase() },
    )
}
