package moe.shizuku.manager.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 底栏的运行时状态（Compose 可观察）。
 *
 * 底栏是**浮在内容之上**的（玻璃 = 悬浮胶囊，MD3 = 贴底整条），只有顶层 Tab 会显示。
 * 用 View 写的列表页早就用 `ksu_content_bottom_padding`（104dp）避让了，
 * 但 Compose 页面拿不到底栏的尺寸 —— 控制台最下面那排「宏」被 MD3 底栏盖住、
 * 设置里最后一张卡贴到手势条上，都是这个原因。
 *
 * MainActivity 在目的地变化（决定显隐）与窗口 Insets 变化（手势条高度）时更新这里，
 * [moe.shizuku.manager.ui.hint.HintPage] 据此在列表底部留白。
 */
object NavUiState {

    /** 底栏当前是否可见（只有顶层 Tab 可见：首页 / 应用 / 模块 / 控制台 / 设置） */
    var visible by mutableStateOf(false)

    /** 手势条高度（px）。MD3 底栏背景会把手势条一起铺掉，玻璃胶囊则靠外边距让开。 */
    var bottomInsetPx by mutableIntStateOf(0)
}
