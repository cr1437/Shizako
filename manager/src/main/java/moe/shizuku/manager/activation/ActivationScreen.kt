package moe.shizuku.manager.activation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintStep

/** 服务当前跑在什么身份下。 */
enum class ServiceMode { NOT_RUNNING, ROOT, ADB }

/** 激活页状态，由 Fragment 单向下发。 */
@Immutable
data class ActivationUiState(
    val serviceMode: ServiceMode = ServiceMode.NOT_RUNNING,
    val dhizukuActive: Boolean = false,
    val rooted: Boolean = false,
    val activatingDhizuku: Boolean = false,
    val supportsWirelessAdb: Boolean = true,
    val isMiui: Boolean = false,
    val adbCommand: String = "",
    /** 已经配对过（adb 私钥在本地，重启后不用再配对） */
    val paired: Boolean = false,
    /** 有「修改安全设置」权限，可以自己开无线调试再启动 */
    val canAutoAdbStart: Boolean = false,
    /** 正在用已配对信息启动服务 */
    val adbStarting: Boolean = false,
) {
    val serviceRunning: Boolean get() = serviceMode != ServiceMode.NOT_RUNNING
}

/**
 * 一站式激活页（Jetpack Compose + 双风格）。
 *
 * 卡片/步骤/按钮全部走 [moe.shizuku.manager.ui.hint] 里的公共组件：
 * MD3 = Material 3 tonal 卡片与 M3 按钮；玻璃 = 半透明玻璃卡片 + 折射描边 + 发光胶囊按钮。
 */
@Composable
fun ActivationScreen(
    state: ActivationUiState,
    palette: HintPalette,
    listState: LazyListState,
    onStartRoot: () -> Unit,
    onStartPairing: () -> Unit,
    onStartAdbPaired: () -> Unit,
    onForgetPairing: () -> Unit,
    onOpenDevelopmentSettings: () -> Unit,
    onCopyAdbCommand: () -> Unit,
    onSendAdbCommand: () -> Unit,
    onActivateDhizuku: () -> Unit,
    onViewDhizukuCommand: () -> Unit,
    onOpenTerminal: () -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 已激活（服务在跑）时，四种激活方式就不需要了 —— 默认收起来，
    // 只留一个「查看激活方式」入口（服务万一挂了还能回去重新激活）。
    // 注意：状态必须在 HintPage 之外声明（它的 content 是 LazyListScope，不是 @Composable）。
    var showMethods by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val methodsVisible = !state.serviceRunning || showMethods

    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        modifier = modifier,
        // 页面本身有横向推入动画，卡片阶梯晚 120ms 再开始，避免两层动画打架
    ) {
        item { Box(modifier = Modifier.animateItem().itemEntrance(0)) { StatusCard(state, palette) } }

        // 已经配对过（服务没在跑）：给一排「不用再配对」的按钮 —— 重启后一键把服务拉起来
        if (state.paired && !state.serviceRunning) {
            item {
                Box(modifier = Modifier.animateItem().itemEntrance(1)) {
                    QuickStartCard(
                        state = state,
                        palette = palette,
                        onStart = onStartAdbPaired,
                        onPairAgain = onStartPairing,
                        onForget = onForgetPairing,
                    )
                }
            }
        }

        if (state.serviceRunning) {
            item {
                Box(modifier = Modifier.animateItem().itemEntrance(1)) {
                    HintCard(palette = palette) {
                        Text(
                            text = stringResource(R.string.activation_methods_collapsed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onCard,
                        )
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(
                                if (showMethods) R.string.activation_methods_hide
                                else R.string.activation_methods_show
                            ),
                            onClick = { showMethods = !showMethods },
                        )
                    }
                }
            }
        }

        if (methodsVisible) {

        item { Box(modifier = Modifier.animateItem().itemEntrance(1)) {
            MethodCard(
                iconRes = R.drawable.ic_root_24dp,
                titleRes = R.string.activation_method_root,
                tagRes = R.string.activation_tag_easiest,
                descriptionRes = R.string.activation_root_desc_detail,
                stepsRes = R.string.activation_root_steps,
                palette = palette,
            ) {
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.activation_method_action_start),
                    enabled = !state.serviceRunning && state.rooted,
                    onClick = onStartRoot,
                )
            }
        }}

        if (state.supportsWirelessAdb) {
            item { Box(modifier = Modifier.animateItem().itemEntrance(2)) {
                MethodCard(
                    iconRes = R.drawable.ic_wireless_adb_24dp,
                    titleRes = R.string.activation_method_wireless_adb,
                    tagRes = R.string.activation_tag_android11,
                    descriptionRes = R.string.activation_wadb_desc_detail,
                    stepsRes = R.string.activation_wadb_steps,
                    palette = palette,
                ) {
                    HintPrimaryButton(
                        palette = palette,
                        text = stringResource(
                            if (state.paired) R.string.activation_wadb_action_start_paired
                            else R.string.activation_wadb_action_pair
                        ),
                        enabled = !state.serviceRunning,
                        onClick = if (state.paired) onStartAdbPaired else onStartPairing,
                    )
                    if (state.paired) {
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.activation_quick_start_action_repair),
                            enabled = !state.serviceRunning,
                            onClick = onStartPairing,
                        )
                    }
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.activation_wadb_action_open_settings),
                        enabled = !state.serviceRunning,
                        onClick = onOpenDevelopmentSettings,
                    )
                    if (state.isMiui) {
                        Text(
                            text = stringResource(R.string.adb_pairing_tutorial_content_miui),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.error,
                        )
                    }
                }
            }}
        }

        item { Box(modifier = Modifier.animateItem().itemEntrance(3)) {
            MethodCard(
                iconRes = R.drawable.ic_adb_24dp,
                titleRes = R.string.activation_method_adb,
                tagRes = R.string.activation_tag_computer,
                descriptionRes = R.string.activation_adb_desc_detail,
                stepsRes = R.string.activation_adb_steps,
                palette = palette,
            ) {
                SelectionContainer {
                    Text(
                        text = state.adbCommand,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.onCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (palette.style == moe.shizuku.manager.ui.hint.HintStyle.MD3) {
                                    palette.card
                                } else {
                                    palette.glassFillTop
                                }
                            )
                            .padding(12.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.activation_adb_action_copy),
                        onClick = onCopyAdbCommand,
                        modifier = Modifier.weight(1f),
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.activation_adb_action_send),
                        onClick = onSendAdbCommand,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }}

        item { Box(modifier = Modifier.animateItem().itemEntrance(4)) {
            MethodCard(
                iconRes = R.drawable.ic_dhizuku_24dp,
                titleRes = R.string.activation_method_dhizuku,
                tagRes = R.string.activation_tag_persistent,
                descriptionRes = R.string.activation_dhizuku_desc_detail,
                stepsRes = R.string.activation_dhizuku_steps,
                palette = palette,
            ) {
                Text(
                    text = stringResource(R.string.activation_dhizuku_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.error,
                )
                if (!state.dhizukuActive) {
                    HintPrimaryButton(
                        palette = palette,
                        text = stringResource(
                            if (state.activatingDhizuku) R.string.activation_dhizuku_activating
                            else R.string.activation_method_action_activate
                        ),
                        enabled = state.serviceRunning && !state.activatingDhizuku,
                        onClick = onActivateDhizuku,
                    )
                }
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.activation_method_action_view_command),
                    onClick = onViewDhizukuCommand,
                )
            }
        }}

        } // end if (methodsVisible)

        item { Box(modifier = Modifier.animateItem().itemEntrance(5)) { TerminalCard(state, palette, onOpenTerminal) } }

        item { Box(modifier = Modifier.animateItem().itemEntrance(6)) { FaqCard(palette) } }
    }
}

/**
 * 「已经配对过，直接启动」卡：一条主按钮 + 两个次要按钮。
 *
 * 配对信息（adb 私钥）存在本地，重启后不用再配对 —— 这里直接走无线调试把启动脚本跑起来。
 */
@Composable
private fun QuickStartCard(
    state: ActivationUiState,
    palette: HintPalette,
    onStart: () -> Unit,
    onPairAgain: () -> Unit,
    onForget: () -> Unit,
) {
    HintCard(palette = palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_wireless_adb_24dp),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier
                    .size(32.dp)
                    .padding(4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.activation_quick_start_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.onCard,
                modifier = Modifier.weight(1f),
            )
            TagChip(R.string.activation_tag_android11, palette)
        }
        Text(
            text = stringResource(R.string.activation_quick_start_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onCard,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!state.canAutoAdbStart) {
            Text(
                text = stringResource(R.string.activation_quick_start_hint_secure_settings),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HintPrimaryButton(
                palette = palette,
                text = stringResource(
                    if (state.adbStarting) R.string.activation_toast_starting
                    else R.string.activation_quick_start_action_start
                ),
                enabled = !state.serviceRunning && !state.adbStarting,
                onClick = onStart,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.activation_quick_start_action_repair),
                    onClick = onPairAgain,
                    modifier = Modifier.weight(1f),
                )
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.activation_quick_start_action_forget),
                    onClick = onForget,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 终端入口卡：服务运行中才可用（原来挂在首页，现挪到本页）。 */
@Composable
private fun TerminalCard(
    state: ActivationUiState,
    palette: HintPalette,
    onOpenTerminal: () -> Unit,
) {
    MethodCard(
        iconRes = R.drawable.ic_terminal_24,
        titleRes = R.string.home_terminal_title,
        tagRes = R.string.activation_tag_terminal,
        descriptionRes = R.string.home_terminal_description,
        stepsRes = if (state.serviceRunning) {
            R.string.activation_terminal_steps_ready
        } else {
            R.string.activation_terminal_steps_not_running
        },
        palette = palette,
    ) {
        HintPrimaryButton(
            palette = palette,
            text = stringResource(R.string.activation_terminal_action_open),
            enabled = state.serviceRunning,
            onClick = onOpenTerminal,
        )
    }
}

// ---------------- 页面内的小组件（都基于双风格公共卡片） ----------------

/** 当前状态卡：两个状态行 + 状态圆点。 */
@Composable
private fun StatusCard(state: ActivationUiState, palette: HintPalette) {
    HintCard(palette = palette) {
        SectionTitle(R.string.activation_section_status, palette)
        Spacer(Modifier.height(4.dp))
        StatusRow(
            labelRes = R.string.activation_status_service,
            value = stringResource(
                when (state.serviceMode) {
                    ServiceMode.NOT_RUNNING -> R.string.activation_status_not_running
                    ServiceMode.ROOT -> R.string.activation_status_running_root
                    ServiceMode.ADB -> R.string.activation_status_running_adb
                }
            ),
            running = state.serviceRunning,
            palette = palette,
        )
        StatusRow(
            labelRes = R.string.activation_status_dhizuku,
            value = stringResource(
                if (state.dhizukuActive) R.string.activation_status_activated
                else R.string.activation_status_not_activated
            ),
            running = state.dhizukuActive,
            palette = palette,
        )
    }
}

@Composable
private fun StatusRow(
    @StringRes labelRes: Int,
    value: String,
    running: Boolean,
    palette: HintPalette,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 运行中：状态点呼吸 —— 文档里的 rememberInfiniteTransition 分支（需要无限重复的动画）
        val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "statusDot")
        val pulse by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(1100),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    // 只在 draw 阶段读动画值；未运行时恒为 1（不做无意义的动画）
                    alpha = if (running) pulse else 1f
                }
                .background(if (running) palette.accent else palette.variant.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.variant,
            modifier = Modifier.weight(1f),
        )
        AnimatedContent(
            targetState = value,
            label = "statusValue",
        ) { text ->
            Text(
                text = text,
                // 动画中的文字：TextMotion.Animated 让字号/行高变化也平滑（官方 API）
                style = MaterialTheme.typography.bodyMedium.copy(
                    textMotion = androidx.compose.ui.text.style.TextMotion.Animated,
                ),
                fontWeight = FontWeight.Bold,
                color = if (running) palette.accent else palette.onCard,
            )
        }
    }
}

/** 方式卡：图标 + 标题 + 标签 + 说明 + 步骤 + 操作。 */
@Composable
private fun MethodCard(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    @StringRes tagRes: Int,
    @StringRes descriptionRes: Int,
    @StringRes stepsRes: Int,
    palette: HintPalette,
    actions: @Composable () -> Unit,
) {
    HintCard(palette = palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier
                    .size(32.dp)
                    .padding(4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = palette.onCard,
                modifier = Modifier.weight(1f),
            )
            TagChip(tagRes, palette)
        }
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onCard,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(stepsRes),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.variant,
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** 右上角标签：MD3 = secondaryContainer；玻璃 = 主题色薄纱。 */
@Composable
private fun TagChip(@StringRes textRes: Int, palette: HintPalette) {
    val glass = palette.style == moe.shizuku.manager.ui.hint.HintStyle.GLASS
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (glass) palette.accent.copy(alpha = 0.18f) else palette.tagBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            // 自适应：标签底是强调色薄纱（玻璃）或 secondaryContainer（MD3），字按对比度挑色
        color = if (glass) {
            moe.shizuku.manager.ui.hint.ensureReadable(palette.accent, palette.glassFillTop)
        } else {
            moe.shizuku.manager.ui.hint.ensureReadable(palette.onTagBackground, palette.tagBackground)
        },
        )
    }
}

@Composable
private fun SectionTitle(@StringRes textRes: Int, palette: HintPalette) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        color = palette.onCard,
    )
}

@Composable
private fun FaqCard(palette: HintPalette) {
    HintCard(palette = palette) {
        SectionTitle(R.string.activation_section_faq, palette)
        FaqItem(R.string.activation_faq_reboot_q, R.string.activation_faq_reboot_a, palette)
        FaqItem(R.string.activation_faq_which_q, R.string.activation_faq_which_a, palette)
        FaqItem(R.string.activation_faq_fail_q, R.string.activation_faq_fail_a, palette)
    }
}

@Composable
private fun FaqItem(@StringRes questionRes: Int, @StringRes answerRes: Int, palette: HintPalette) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = stringResource(questionRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = palette.onCard,
        )
        Text(
            text = stringResource(answerRes),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.variant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
