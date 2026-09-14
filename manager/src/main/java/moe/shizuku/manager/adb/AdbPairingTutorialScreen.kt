package moe.shizuku.manager.adb

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNoteRow
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.HintStep
import moe.shizuku.manager.ui.hint.HintStyle

/** 配对教程页状态。 */
@Immutable
data class PairingTutorialState(
    val notificationEnabled: Boolean = true,
    val isMiui: Boolean = false,
)

/**
 * 无线调试配对教程（重新设计的版本，MD3 / 玻璃双风格）。
 *
 * 页面结构（不是旧 Shizuku 布局的移植）：
 * 1. **主操作卡**：图标 + 标题 + 当前状态一句话 + 主/次按钮（打开开发者选项 / 通知设置）。
 * 2. **操作步骤**：三步时间线（圆序号 + 连接线），每步标题 + 说明，流程一目了然。
 * 3. **注意事项**：通知 / 本地网络 / MIUI 三条紧凑提示（通知被禁时高亮）。
 */
@Composable
fun AdbPairingTutorialScreen(
    state: PairingTutorialState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        modifier = modifier,
        // 页面本身有横向推入动画，卡片阶梯晚 120ms 再开始，避免两层动画打架
    ) {
        item { Box(modifier = Modifier.itemEntrance(0)) { HeroCard(state, palette, onOpenNotificationSettings, onOpenDeveloperOptions) } }
        item { Box(modifier = Modifier.itemEntrance(1)) { StepsCard(palette) } }
        item { Box(modifier = Modifier.itemEntrance(2)) { NotesCard(state, palette, onOpenNotificationSettings) } }
    }
}

/** 主操作卡：一眼看清「现在该做什么」。 */
@Composable
private fun HeroCard(
    state: PairingTutorialState,
    palette: HintPalette,
    onOpenNotificationSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    HintCard(palette = palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_wireless_adb_24dp),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.activation_method_wireless_adb),
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.onCard,
                )
                // 通知状态变化（去设置里开了通知回来）时淡入淡出切换，不硬切
                AnimatedContent(
                    targetState = state.notificationEnabled,
                    label = "pairingStatus",
                ) { enabled ->
                    Text(
                        text = stringResource(
                            if (enabled) {
                                R.string.adb_pairing_tutorial_content_notification
                            } else {
                                R.string.adb_pairing_tutorial_content_notification_blocked
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) palette.variant else palette.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HintPrimaryButton(
                palette = palette,
                text = stringResource(R.string.development_settings),
                onClick = onOpenDeveloperOptions,
            )
            if (!state.notificationEnabled) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.notification_settings),
                    onClick = onOpenNotificationSettings,
                )
            }
        }
    }
}

/** 操作步骤：三步时间线。 */
@Composable
private fun StepsCard(palette: HintPalette) {
    HintCard(palette = palette) {
        HintSectionTitle(palette = palette, text = stringResource(R.string.hint_section_steps))
        HintStep(
            palette = palette,
            index = 1,
            title = stringResource(R.string.hint_step_open_wireless_title),
            body = stringResource(R.string.adb_pairing_tutorial_content_steps),
            hint = stringResource(R.string.adb_pairing_tutorial_content_left_is_clickable),
            showConnector = true,
        )
        HintStep(
            palette = palette,
            index = 2,
            title = stringResource(R.string.hint_step_copy_code_title),
            body = stringResource(R.string.adb_pairing_tutorial_content_enter_pairing_code),
            showConnector = true,
        )
        HintStep(
            palette = palette,
            index = 3,
            title = stringResource(R.string.hint_step_done_title),
            body = stringResource(R.string.adb_pairing_tutorial_content_finish),
        )
    }
}

/** 注意事项：紧凑三行。 */
@Composable
private fun NotesCard(
    state: PairingTutorialState,
    palette: HintPalette,
    onOpenNotificationSettings: () -> Unit,
) {
    HintCard(palette = palette, errorTone = !state.notificationEnabled) {
        HintSectionTitle(palette = palette, text = stringResource(R.string.hint_section_notes))
        HintNoteRow(
            palette = palette,
            iconRes = if (state.notificationEnabled) {
                R.drawable.ic_outline_notifications_active_24
            } else {
                R.drawable.ic_outline_info_24
            },
            title = stringResource(R.string.hint_note_notification_title),
            body = stringResource(
                if (state.notificationEnabled) {
                    R.string.adb_pairing_tutorial_content_notification
                } else {
                    R.string.adb_pairing_tutorial_content_notification_blocked
                }
            ),
            errorTone = !state.notificationEnabled,
            action = if (state.notificationEnabled) null else {
                {
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.notification_settings),
                        onClick = onOpenNotificationSettings,
                    )
                }
            },
        )
        HintNoteRow(
            palette = palette,
            iconRes = R.drawable.ic_help_outline_24dp,
            title = stringResource(R.string.hint_note_network_title),
            body = stringResource(R.string.adb_pairing_tutorial_content_network_limation_not_foreground),
        )
        if (state.isMiui) {
            HintNoteRow(
                palette = palette,
                iconRes = R.drawable.ic_warning_24,
                title = stringResource(R.string.hint_note_miui_title),
                body = stringResource(R.string.adb_pairing_tutorial_content_miui),
                errorTone = true,
            )
        }
    }
}
