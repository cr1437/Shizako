package moe.shizuku.manager.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.R as MaterialR
import rikka.core.util.ResourceUtils

/**
 * 全应用唯一的 M3 配色提供方。
 *
 * 为什么必须有它：Compose Material3 的组件（`OutlinedTextField`、`Dialog`……）在没有
 * `MaterialTheme` 提供方时会**静默退回默认浅色方案**（`lightColorScheme()`），和系统 /
 * 应用正在用的深浅色完全无关。项目里绝大多数文字没事，是因为作者到处显式传了
 * `palette.onCard` 之类的颜色；但没传 `colors` 的组件（例如控制台输入框）就会拿默认
 * 浅色方案的 `onSurface`（近黑 `#1C1B1F`）去压深色玻璃卡片 → 黑字黑底。
 *
 * 取色**只从 Android XML 主题**（`context.theme`）解析 —— 深浅色 / 动态取色 / 主题色 /
 * 黑夜间模式全都已经落在 XML 主题里了（`Theme.Material3.Light|Dark.Rikka` + 各色
 * `ThemeOverlay`，由 materialthemebuilder 生成），从这同一处取色，Compose 侧才会和
 * View 侧界面完全一致；自己另编一套颜色迟早两边对不上。
 *
 * 只覆盖 `colorScheme`，typography / shapes 保持 MaterialTheme 默认（不动它们，避免
 * 影响面扩大）。
 */
@Composable
fun ShizakoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val night = isNightMode(context)

    // 解析要刷二十来个 TypedArray，只在 context / 深浅色变了时重算一次
    val colorScheme = remember(context, night) { resolveShizakoColorScheme(context, night) }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** ComposeView 的统一入口：先套主题再 setContent —— 全应用只需在这里保证一次。 */
fun ComposeView.setShizakoContent(content: @Composable () -> Unit) =
    setContent { ShizakoTheme(content) }

/** 深色判定：和 `ThemeHelper` / `ResourceUtils.isNightMode` 同一套位运算。 */
private fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * 把 XML 主题里的颜色读出来覆盖到基底 [ColorScheme] 上。
 *
 * 基底按 `night` 选 `darkColorScheme()` / `lightColorScheme()`：这样即使某个属性在当前
 * ROM 的主题里取不到，退回的也是**方向正确**的颜色（深色下退回浅色方案的 surface，
 * 那才是真的又出一次黑字黑底），而不是方向相反的一套。
 *
 * 每项都走 [resolve] 兜底：`Theme.obtainStyledAttributes` 在一个属性都匹配不到时会抛
 * `Resources.NotFoundException`，个别 ROM / 定制主题缺某个 `colorSurfaceContainer*` 时
 * 不能让整个 Compose 界面崩掉 —— 取不到就保留基底同名字段。
 */
private fun resolveShizakoColorScheme(context: Context, night: Boolean): ColorScheme {
    val theme = context.theme

    fun resolve(attr: Int): Color? =
        runCatching { ResourceUtils.resolveColor(theme, attr) }.getOrNull()?.let { Color(it) }

    val base = if (night) darkColorScheme() else lightColorScheme()

    return base.copy(
        // 强调色 / 主色
        primary = resolve(MaterialR.attr.colorPrimary) ?: base.primary,
        onPrimary = resolve(MaterialR.attr.colorOnPrimary) ?: base.onPrimary,
        primaryContainer = resolve(MaterialR.attr.colorPrimaryContainer) ?: base.primaryContainer,
        onPrimaryContainer = resolve(MaterialR.attr.colorOnPrimaryContainer) ?: base.onPrimaryContainer,
        // 次色 / 容器（标签胶囊等用到的就是这一对）
        secondary = resolve(MaterialR.attr.colorSecondary) ?: base.secondary,
        onSecondary = resolve(MaterialR.attr.colorOnSecondary) ?: base.onSecondary,
        secondaryContainer = resolve(MaterialR.attr.colorSecondaryContainer) ?: base.secondaryContainer,
        onSecondaryContainer = resolve(MaterialR.attr.colorOnSecondaryContainer) ?: base.onSecondaryContainer,
        // 表面 / 前景
        surface = resolve(MaterialR.attr.colorSurface) ?: base.surface,
        onSurface = resolve(MaterialR.attr.colorOnSurface) ?: base.onSurface,
        surfaceVariant = resolve(MaterialR.attr.colorSurfaceVariant) ?: base.surfaceVariant,
        onSurfaceVariant = resolve(MaterialR.attr.colorOnSurfaceVariant) ?: base.onSurfaceVariant,
        // 表面层级：卡片 / 对话框底（surfaceContainerHigh 是 M3 对话框的默认底色）
        surfaceContainerLowest = resolve(MaterialR.attr.colorSurfaceContainerLowest) ?: base.surfaceContainerLowest,
        surfaceContainerLow = resolve(MaterialR.attr.colorSurfaceContainerLow) ?: base.surfaceContainerLow,
        surfaceContainer = resolve(MaterialR.attr.colorSurfaceContainer) ?: base.surfaceContainer,
        surfaceContainerHigh = resolve(MaterialR.attr.colorSurfaceContainerHigh) ?: base.surfaceContainerHigh,
        surfaceContainerHighest = resolve(MaterialR.attr.colorSurfaceContainerHighest) ?: base.surfaceContainerHighest,
        // 描边
        outline = resolve(MaterialR.attr.colorOutline) ?: base.outline,
        outlineVariant = resolve(MaterialR.attr.colorOutlineVariant) ?: base.outlineVariant,
        // 错误态
        error = resolve(MaterialR.attr.colorError) ?: base.error,
        onError = resolve(MaterialR.attr.colorOnError) ?: base.onError,
        errorContainer = resolve(MaterialR.attr.colorErrorContainer) ?: base.errorContainer,
        onErrorContainer = resolve(MaterialR.attr.colorOnErrorContainer) ?: base.onErrorContainer,
    )
}
