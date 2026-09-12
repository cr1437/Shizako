package moe.shizuku.manager.ui.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.shizuku.manager.app.ThemeHelper

/**
 * 界面风格的**运行时可观察状态**（MD3 / 玻璃材质）。
 *
 * 以前风格切换靠 `Activity.recreate()`：整屏重建 → 硬闪、列表跳回顶部、
 * 转场无从谈起。现在改成运行时可观察状态：
 * - 底栏（玻璃胶囊 ↔ MD3 标准栏）交叉淡入切换，宿主尺寸/边距用动画过渡；
 * - 提示页的配色跟着状态重算，内容整体交叉淡入；
 * - 只有依赖主题 Overlay 的部分（对话框容器透明）需要下次重建才完全生效，
 *   但窗口模糊（GlassWindow）是运行时判断，切换后立刻就有效。
 */
object UiStyle {

    /** 当前风格值（"md3" / "glass"），Compose 侧直接读这个状态。 */
    var current: String by mutableStateOf(ThemeHelper.getUiStyle())
        private set

    val isGlass: Boolean get() = current == ThemeHelper.UI_STYLE_GLASS

    /** 设置里改了风格：更新状态（不重建 Activity）。 */
    fun apply(value: String) {
        current = value
    }

    /** 从偏好设置重新读取（例如首次进入、或外部改了 prefs）。 */
    fun refresh() {
        current = ThemeHelper.getUiStyle()
    }
}
