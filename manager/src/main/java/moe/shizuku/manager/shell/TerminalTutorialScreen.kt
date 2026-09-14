package moe.shizuku.manager.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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

/** 一键部署的状态。 */
@Immutable
sealed interface DeployState {
    data object Idle : DeployState
    data object Running : DeployState
    data class Success(
        val targetDir: String,
        val inTermuxPath: Boolean,
        val isRoot: Boolean,
    ) : DeployState
    data class Failure(val message: String) : DeployState
}

/** 终端教程页状态。 */
@Immutable
data class TerminalTutorialState(
    val serviceRunning: Boolean = false,
    val isRoot: Boolean = false,
    val hasTermux: Boolean = false,
    val deploy: DeployState = DeployState.Idle,
)

/**
 * 终端（rish）教程 —— 重新设计的版本，MD3 / 玻璃双风格。
 *
 * 页面结构：
 * 1. **一键部署卡**：说明 + 当前环境（服务身份 / 是否装了 Termux）+ 主按钮「一键部署」，
 *    部署结果（目标目录 / 在 Termux 里敲 `rish` / 完整路径）就地以动画切换呈现；
 * 2. **手动方式**：三段式时间线（导出文件 → 改 RISH_APPLICATION_ID → 放进终端能访问的目录），
 *    保留「导出文件」按钮作为兜底；
 * 3. **注意事项**：dex 只读、RISH_PRESERVE_ENV、需要终端应用。
 */
@Composable
fun TerminalTutorialScreen(
    state: TerminalTutorialState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onDeploy: () -> Unit,
    onExportFiles: () -> Unit,
    onOpenDocs: () -> Unit,
    onCopyCommand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        modifier = modifier,
        // 页面本身有横向推入动画，卡片阶梯晚一点再开始，避免两层动画打架
    ) {
        item { Box(modifier = Modifier.itemEntrance(0)) { DeployCard(state, palette, onDeploy, onOpenDocs, onCopyCommand) } }
        item { Box(modifier = Modifier.itemEntrance(1)) { ManualCard(palette, onExportFiles) } }
        item { Box(modifier = Modifier.itemEntrance(2)) { NotesCard(state, palette) } }
    }
}

/** 一键部署卡：状态 + 结果都在这里。 */
@Composable
private fun DeployCard(
    state: TerminalTutorialState,
    palette: HintPalette,
    onDeploy: () -> Unit,
    onOpenDocs: () -> Unit,
    onCopyCommand: () -> Unit,
) {
    HintCard(palette = palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_terminal_24),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.home_terminal_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(
                        if (state.serviceRunning) {
                            if (state.isRoot) R.string.terminal_env_root else R.string.terminal_env_adb
                        } else {
                            R.string.terminal_env_no_service
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.serviceRunning) palette.variant else palette.error,
                )
            }
        }

        // 部署状态：出现/消失用 AnimatedVisibility（文档决策树的「出现与消失」分支），
        // 状态之间切换再用 AnimatedContent（「内容不同的可组合项之间切换」分支）—— 两件事，两套 API。
        AnimatedVisibility(
            visible = state.deploy !is DeployState.Idle,
            enter = fadeIn(animationSpec = androidx.compose.animation.core.spring()) +
                expandVertically(animationSpec = androidx.compose.animation.core.spring()),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(120)),
        ) {
            AnimatedContent(
                targetState = state.deploy,
                label = "deployState",
            ) { deploy ->
                when (deploy) {
                    is DeployState.Idle -> Unit
                    is DeployState.Running -> DeployStatusRow(
                        palette = palette,
                        // 用**静态矢量**那支，转圈交给 Compose 驱动：
                        // `ic_deploy_spinner` 是 AnimatedVectorDrawable，Compose 的 painterResource
                        // 只吃 VectorDrawable / 位图，直接传它会崩
                        // （IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported）
                        iconRes = R.drawable.ic_deploy_spinner_vector,
                        spin = true,
                        text = stringResource(R.string.terminal_deploy_running),
                        color = palette.variant,
                    )
                    is DeployState.Success -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeployStatusRow(
                            palette = palette,
                            iconRes = R.drawable.ic_outline_info_24,
                            text = stringResource(R.string.terminal_deploy_success, deploy.targetDir),
                            color = palette.accent,
                        )
                        val command = if (deploy.inTermuxPath) "rish" else "${deploy.targetDir}/rish"
                        SelectionContainer {
                            Text(
                                text = stringResource(
                                    if (deploy.inTermuxPath) {
                                        R.string.terminal_deploy_hint_termux
                                    } else {
                                        R.string.terminal_deploy_hint_path
                                    },
                                    command,
                                ),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textMotion = androidx.compose.ui.text.style.TextMotion.Animated,
                                ),
                                color = palette.onCard,
                            )
                        }
                        // 「复制调用命令」：把完整的一行（含 RISH_APPLICATION_ID）塞进剪贴板，
                        // 用户只要在终端里粘贴回车 —— 不用自己拼路径和包名
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.terminal_copy_command),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onCopyCommand,
                        )
                        // adb 身份下 rish 只能从终端手动敲完整路径（进不了 Termux 的 PATH，也没法 chown）
                        if (!deploy.isRoot) {
                            Text(
                                text = stringResource(R.string.terminal_deploy_hint_shell),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.variant,
                            )
                        }
                    }
                    is DeployState.Failure -> DeployStatusRow(
                        palette = palette,
                        iconRes = R.drawable.ic_warning_24,
                        text = stringResource(R.string.terminal_deploy_failed, deploy.message),
                        color = palette.error,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HintPrimaryButton(
                palette = palette,
                text = stringResource(
                    if (state.deploy is DeployState.Running) R.string.terminal_deploy_running
                    else R.string.terminal_deploy_action
                ),
                enabled = state.serviceRunning && state.deploy !is DeployState.Running,
                onClick = onDeploy,
            )
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.terminal_open_docs),
                onClick = onOpenDocs,
            )
        }
    }
}

@Composable
private fun DeployStatusRow(
    palette: HintPalette,
    iconRes: Int,
    text: String,
    color: Color,
    /** 转圈：只有「部署中」这一支会传 true，状态一变这层的无限动画就跟着销毁 */
    spin: Boolean = false,
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(20.dp)
                .then(
                    if (spin) {
                        val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "deploySpin")
                        val angle = infinite.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                animation = androidx.compose.animation.core.tween(900),
                            ),
                            label = "deploySpinAngle",
                        ).value
                        Modifier.graphicsLayer { rotationZ = angle }
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 手动方式：三步时间线（保留导出兜底）。 */
@Composable
private fun ManualCard(palette: HintPalette, onExportFiles: () -> Unit) {
    HintCard(palette = palette) {
        HintSectionTitle(palette = palette, text = stringResource(R.string.terminal_section_manual))
        HintStep(
            palette = palette,
            index = 1,
            title = stringResource(R.string.terminal_step_export_title),
            body = stringResource(R.string.terminal_tutorial_1, "rish", "rish_shizuku.dex"),
            showConnector = true,
            content = {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.terminal_export_files),
                    onClick = onExportFiles,
                )
            },
        )
        HintStep(
            palette = palette,
            index = 2,
            title = stringResource(R.string.terminal_step_edit_title),
            body = stringResource(
                R.string.terminal_tutorial_2_description,
                "Termux", "PKG", "com.termux", "com.termux",
            ),
            showConnector = true,
        )
        HintStep(
            palette = palette,
            index = 3,
            title = stringResource(R.string.terminal_step_place_title),
            body = stringResource(R.string.terminal_tutorial_3, "sh rish"),
        )
    }
}

/** 注意事项。 */
@Composable
private fun NotesCard(state: TerminalTutorialState, palette: HintPalette) {
    HintCard(palette = palette, errorTone = !state.serviceRunning) {
        HintSectionTitle(palette = palette, text = stringResource(R.string.hint_section_notes))
        HintNoteRow(
            palette = palette,
            iconRes = R.drawable.ic_outline_info_24,
            title = stringResource(R.string.terminal_note_dex_title),
            body = stringResource(R.string.terminal_note_dex_body),
            errorTone = !state.serviceRunning,
        )
        HintNoteRow(
            palette = palette,
            iconRes = R.drawable.ic_help_outline_24dp,
            title = stringResource(R.string.terminal_note_env_title),
            body = stringResource(R.string.terminal_note_env_body),
        )
        HintNoteRow(
            palette = palette,
            iconRes = R.drawable.ic_terminal_24,
            title = stringResource(R.string.terminal_note_app_title),
            body = stringResource(
                if (state.hasTermux) R.string.terminal_note_app_termux
                else R.string.terminal_note_app_none
            ),
            errorTone = !state.hasTermux,
        )
    }
}
