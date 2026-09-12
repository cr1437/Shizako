package moe.shizuku.manager.toolbox

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance

/**
 * 工具箱里的一条：图标 + 标题 + 说明 + 点击动作。
 *
 * 动作（跳到哪一页）由 [ToolboxFragment] 提供 —— 页面在这里只负责画，
 * 所以同一条目想换目的地不用改 UI。
 */
@Immutable
data class ToolboxItem(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val action: () -> Unit,
)

/** 工具箱的一个分区（模块 / 控制台 / 无障碍 / 设备 …）。 */
@Immutable
data class ToolboxGroup(
    @StringRes val titleRes: Int,
    val items: List<ToolboxItem>,
)

/**
 * 工具箱页（Jetpack Compose + 双风格）。
 *
 * 把散在底栏 / 设置二级页里的功能收成一页入口：模块、控制台、无障碍、实验室、
 * 激活、推荐应用下载、日志…… 点哪条进哪页，功能本身没搬家。
 */
@Composable
fun ToolboxScreen(
    palette: HintPalette,
    listState: LazyListState,
    groups: List<ToolboxGroup>,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        modifier = modifier,
    ) {
        item(key = "toolbox-note") {
            Box(modifier = Modifier.animateItem().itemEntrance(0)) {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_toolbox_24,
                    text = stringResource(R.string.toolbox_note),
                    secondary = stringResource(R.string.toolbox_note_secondary),
                )
            }
        }

        groups.forEachIndexed { groupIndex, group ->
            item(key = "toolbox-group-${group.titleRes}") {
                Box(modifier = Modifier.animateItem().itemEntrance(1 + groupIndex)) {
                    HintSectionTitle(
                        palette = palette,
                        text = stringResource(group.titleRes),
                    )
                }
            }
            item(key = "toolbox-card-${group.titleRes}") {
                Box(modifier = Modifier.animateItem().itemEntrance(1 + groupIndex)) {
                    HintCard(palette = palette) {
                        group.items.forEach { item -> ToolboxRow(palette, item) }
                    }
                }
            }
        }
    }
}

/** 一条入口：图标徽章 + 标题 + 说明 + 右侧箭头。 */
@Composable
private fun ToolboxRow(palette: HintPalette, item: ToolboxItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = item.action)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIconBadge(palette, item.iconRes)
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onCard,
            )
            Text(
                text = stringResource(item.summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.variant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 图标徽章：强调色薄纱底 + 强调色图标（和设置一级菜单同一个观感）。 */
@Composable
private fun ToolIconBadge(palette: HintPalette, iconRes: Int, size: Dp = 38.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(palette.accent.copy(alpha = 0.14f), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
