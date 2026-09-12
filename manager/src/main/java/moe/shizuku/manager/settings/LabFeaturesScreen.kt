package moe.shizuku.manager.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.compat.StubManager
import moe.shizuku.manager.connector.ShizukuConnectors
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNoteRow
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.resolveHintPalette

/**
 * 实验室功能页（**Compose**，不是 PreferenceFragment）。
 *
 * 为什么用 Compose 而不是偏好页：本项目的设置已经整页迁到 Compose（`SettingsScreen`），
 * 卡片材质（玻璃 / MD3）、文字自适应、配色都来自 [resolveHintPalette]；再插一个
 * PreferenceFragment 的偏好列表，样式会和设置页其他部分对不上。
 *
 * 当前有两项功能：[ShizukuConnectors]（对第三方 Activator 暴露启动命令的 Provider 开关，
 * 首次开启必须过安全警告确认框），以及**兼容性 Stub**（安装 / 卸载包名为
 * `moe.shizuku.privileged.api` 的占位 APK，逻辑在 [StubManager]）。
 * 备份 / 恢复只是入口按钮（回调由宿主提供），
 * 真正的读写逻辑在 `moe.shizuku.manager.utils.BackupRestoreUtil`。
 *
 * 接线方式（宿主自己决定放哪，比如设置页加一个二级页，或者单独一个 Activity）：
 * ```
 * val listState = LazyListState()
 * LabFeaturesScreen(
 *     onBack = { 返回上一级 },
 *     listState = listState,
 *     onCollapsedChange = { expanded -> appBar.setExpanded(expanded) },
 *     onBackup = { 拉起 SAF 创建文件，再把 BackupRestoreUtil.backup() 的结果写进去 },
 *     onRestore = { 拉起 SAF 选文件，读成字符串后调 BackupRestoreUtil.restore() },
 * )
 * ```
 *
 * @param onBack 返回上一级（左上角返回行）。
 * @param palette 页面配色；不传就按当前主题 / 风格自己解析。
 * @param listState 列表状态由宿主传入，好让大标题折叠栏联动；不传就内部新建。
 * @param onCollapsedChange 列表是否处于「未滚动」状态（真 = 展开），用于折叠大标题。
 * @param onBackup 备份入口；**传 null 就不显示备份与恢复这一块**（宿主还没接线时不留死按钮）。
 * @param onRestore 恢复入口；同上。
 */
@Composable
fun LabFeaturesScreen(
    onBack: () -> Unit,
    palette: HintPalette? = null,
    listState: LazyListState? = null,
    onCollapsedChange: (Boolean) -> Unit = {},
    onBackup: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val p = palette ?: remember(context) { resolveHintPalette(context) }
    val state = listState ?: rememberLazyListState()

    // 开关真值来自偏好（Provider 读的是同一份），这里只做单向镜像，避免两个页面各自记状态
    var connectorsEnabled by remember { mutableStateOf(ShizukuConnectors.isEnabled(context)) }
    var showWarning by remember { mutableStateOf(false) }

    // 兼容性 Stub 的安装状态：初值直接查一遍 PackageManager，之后由安装/卸载的结果写回
    var stubInstalled by remember { mutableStateOf(StubManager.isInstalled(context)) }
    var stubBusy by remember { mutableStateOf(false) }
    var stubMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    HintPage(listState = state, onCollapsedChange = onCollapsedChange) {
        // 1. 返回行（和设置页二级页一致：一行图标 + 文字，整行可点）
        item {
            HintCard(palette = p, modifier = Modifier.itemEntrance(0)) {
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
                        tint = p.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_back),
                        style = MaterialTheme.typography.bodyLarge,
                        color = p.accent,
                    )
                }
            }
        }

        // 2. Shizuku Connectors 开关
        item { HintSectionTitle(p, stringResource(R.string.lab_features_title)) }
        item {
            HintCard(palette = p, modifier = Modifier.itemEntrance(1)) {
                SwitchRow(
                    palette = p,
                    iconRes = R.drawable.ic_baseline_link_24,
                    title = stringResource(R.string.lab_connectors_title),
                    summary = stringResource(R.string.lab_connectors_summary),
                    checked = connectorsEnabled,
                    onChange = { want ->
                        // 只有「打开」需要过警告框；关闭直接落盘
                        if (want && !ShizukuConnectors.isWarningAccepted(context)) {
                            showWarning = true
                        } else {
                            connectorsEnabled = want
                            ShizukuConnectors.setEnabled(context, want)
                        }
                    },
                )

                // 开启后才把 URI 亮出来：关着的时候显示它没意义，还容易被误读成"已经能用"
                if (connectorsEnabled) {
                    Text(
                        text = stringResource(R.string.lab_connectors_uri),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.variant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SelectionContainer {
                        Text(
                            // 客户端要拼的完整地址，给用户直接复制
                            text = ShizukuConnectors.uri(context),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = p.onCard,
                        )
                    }
                }
            }
        }

        // 3. 说明：这是什么 / 怎么用 / 风险
        item { HintSectionTitle(p, stringResource(R.string.lab_connectors_notes_title)) }
        item {
            HintCard(palette = p, modifier = Modifier.itemEntrance(2)) {
                HintNoteRow(
                    palette = p,
                    iconRes = R.drawable.ic_outline_info_24,
                    title = stringResource(R.string.lab_connectors_what_title),
                    body = stringResource(R.string.lab_connectors_what_body),
                )
                HintNoteRow(
                    palette = p,
                    iconRes = R.drawable.ic_terminal_24,
                    title = stringResource(R.string.lab_connectors_query_title),
                    body = stringResource(R.string.lab_connectors_query_body),
                )
                HintNoteRow(
                    palette = p,
                    iconRes = R.drawable.ic_warning_24,
                    title = stringResource(R.string.lab_connectors_risk_title),
                    body = stringResource(R.string.lab_connectors_risk_body),
                    errorTone = true,
                )
            }
        }

        // 4. 兼容性 Stub：给写死原版包名的应用装一个同名占位包
        item { HintSectionTitle(p, stringResource(R.string.lab_stub_title)) }
        item {
            HintCard(palette = p, modifier = Modifier.itemEntrance(3)) {
                // 状态文案跟着安装状态走，用户一眼能看出当前是哪种情况
                val stateText = stringResource(
                    if (stubInstalled) R.string.lab_stub_installed else R.string.lab_stub_not_installed
                )
                val summary = stringResource(R.string.lab_stub_summary)

                IconBadgeRow(
                    palette = p,
                    iconRes = R.drawable.ic_outline_extension_24,
                    title = stringResource(R.string.lab_stub_title),
                    summary = "$stateText · $summary",
                )

                // 安装 / 卸载都是 shell + 轮询，几十毫秒到几秒不等，所以放 IO 上跑并禁用按钮防重复点
                if (stubInstalled) {
                    HintSecondaryButton(
                        palette = p,
                        text = stringResource(R.string.lab_stub_uninstall),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !stubBusy,
                        onClick = {
                            stubBusy = true
                            stubMessage = null
                            scope.launch {
                                val r = StubManager.uninstall(context)
                                stubInstalled = StubManager.isInstalled(context)
                                stubMessage = if (r.ok) {
                                    context.getString(R.string.lab_stub_ok, r.channel)
                                } else {
                                    context.getString(R.string.lab_stub_failed, r.error ?: "")
                                }
                                stubBusy = false
                            }
                        },
                    )
                } else {
                    HintPrimaryButton(
                        palette = p,
                        text = stringResource(R.string.lab_stub_install),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !stubBusy,
                        onClick = {
                            stubBusy = true
                            stubMessage = null
                            scope.launch {
                                val r = StubManager.install(context)
                                stubInstalled = StubManager.isInstalled(context)
                                stubMessage = if (r.ok) {
                                    context.getString(R.string.lab_stub_ok, r.channel)
                                } else {
                                    context.getString(R.string.lab_stub_failed, r.error ?: "")
                                }
                                stubBusy = false
                            }
                        },
                    )
                }

                if (stubBusy || stubMessage != null) {
                    Text(
                        text = stubMessage ?: stringResource(R.string.lab_stub_working),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.variant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            HintCard(palette = p, modifier = Modifier.itemEntrance(4)) {
                HintNoteRow(
                    palette = p,
                    iconRes = R.drawable.ic_outline_info_24,
                    title = stringResource(R.string.lab_stub_note_title),
                    body = stringResource(R.string.lab_stub_note_body),
                )
            }
        }

        // 5. 备份与恢复（宿主给了回调才显示）
        if (onBackup != null || onRestore != null) {
            item { HintSectionTitle(p, stringResource(R.string.lab_backup_title)) }
            item {
                HintCard(palette = p, modifier = Modifier.itemEntrance(5)) {
                    Text(
                        text = stringResource(R.string.lab_backup_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.variant,
                    )
                    if (onBackup != null) {
                        HintPrimaryButton(
                            palette = p,
                            text = stringResource(R.string.lab_backup_button),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onBackup,
                        )
                    }
                    if (onRestore != null) {
                        HintSecondaryButton(
                            palette = p,
                            text = stringResource(R.string.lab_restore_button),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onRestore,
                        )
                    }
                }
            }
        }

        // 底部留白：内容不被系统导航条 / 底栏压住
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (showWarning) {
        // 用 M3 AlertDialog 而不是 View 侧 MaterialAlertDialogBuilder：
        // 这一页是纯 Compose，用 Compose 对话框才不会被 Activity 的主题切换漏掉。
        // 底色取 palette.card（不透明）—— 玻璃卡的材质在 Dialog 窗口里没有模糊源，会糊在半透明上。
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.lab_connectors_unsafe_title)) },
            text = { Text(stringResource(R.string.lab_connectors_unsafe_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    // 先记「已接受警告」再开开关：两个条件都满足 Provider 才会给命令
                    ShizukuConnectors.setWarningAccepted(context, true)
                    ShizukuConnectors.setEnabled(context, true)
                    connectorsEnabled = true
                }) {
                    Text(
                        text = stringResource(R.string.lab_connectors_risk_accept),
                        color = p.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(text = stringResource(android.R.string.cancel), color = p.variant)
                }
            },
            containerColor = p.card,
            titleContentColor = p.onCard,
            textContentColor = p.variant,
        )
    }
}

/** 开关行：图标徽章 + 标题 + 说明 + 右侧 Switch（整行可点，和设置页行为一致）。 */
@Composable
private fun SwitchRow(
    palette: HintPalette,
    iconRes: Int,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    IconBadgeRow(
        palette = palette,
        iconRes = iconRes,
        title = title,
        summary = summary,
        onClick = { onChange(!checked) },
    ) {
        Switch(
            checked = checked,
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

/**
 * 通用行：图标徽章（强调色 14% 圆角方块）+ 标题 + 说明，右边留给调用方塞控件。
 *
 * 抽出来是因为本页既有「开关」也有「状态 + 按钮」两种行，抽掉公共部分后
 * 两处的间距、圆角、配色不会各改各的而漂移。
 */
@Composable
private fun IconBadgeRow(
    palette: HintPalette,
    iconRes: Int,
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标徽章：强调色 14% 圆角方块 + 图标（和设置页的 IconBadge 同一套语言）
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
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = palette.onCard)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
