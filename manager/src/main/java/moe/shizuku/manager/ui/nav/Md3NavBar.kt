package moe.shizuku.manager.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.liquidglass.LiquidGlassNavItem

/** MD3 底栏配色（全部从 Android 主题解析，不依赖 Compose MaterialTheme）。 */
@Immutable
data class Md3NavColors(
    val container: Color = Color(0xFF1E1E22),
    val indicator: Color = Color(0xFF4A4458),
    val selectedIcon: Color = Color(0xFFE8DEF8),
    val selectedText: Color = Color(0xFFE8DEF8),
    val unselectedIcon: Color = Color(0xFFCAC4D0),
    val unselectedText: Color = Color(0xFFCAC4D0),
    val badgeContainer: Color = Color(0xFFFF9CA8),
    val badgeText: Color = Color.White,
)

/**
 * **Shizuku 同款标准 Material 3 底栏**（MD3 风格用，替代悬浮胶囊）：
 * 贴底整条、不透明 surfaceContainer 底、每项 M3 选中指示器胶囊 + 图标 + 文字标签。
 *
 * @param barHeight 内容区高度（不含手势条）；[bottomInset] 由宿主传入手势条高度，
 *   让底栏背景铺到屏幕底部、内容避让手势条（edge-to-edge 的标准做法）。
 */
@Composable
fun Md3NavBar(
    items: List<LiquidGlassNavItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    colors: Md3NavColors,
    modifier: Modifier = Modifier,
    barHeight: Dp = 56.dp,
    bottomInset: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight + bottomInset)
            .background(colors.container),
    ) {
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter),
            containerColor = colors.container,
            tonalElevation = 0.dp,
        ) {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = index == selectedIndex,
                    onClick = { if (index != selectedIndex) onSelected(index) },
                    icon = {
                        val icon: @Composable () -> Unit = {
                            Icon(
                                imageVector = item.selectedIcon ?: item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        if (item.badgeCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = colors.badgeContainer,
                                        contentColor = colors.badgeText,
                                    ) {
                                        Text(
                                            text = item.badgeCount.toString(),
                                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                            ) { icon() }
                        } else {
                            icon()
                        }
                    },
                    label = {
                        Text(
                            text = item.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.selectedIcon,
                        selectedTextColor = colors.selectedText,
                        indicatorColor = colors.indicator,
                        unselectedIconColor = colors.unselectedIcon,
                        unselectedTextColor = colors.unselectedText,
                    ),
                )
            }
        }
    }
}
