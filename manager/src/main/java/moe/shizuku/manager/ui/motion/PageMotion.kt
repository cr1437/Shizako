package moe.shizuku.manager.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 全应用**唯一**的页面转场参数（横向滑动 shared axis X）。
 *
 * 以前三处各写一套，观感自然不统一：
 * - 底栏 Tab / 次级页（`R.anim.nav_*` 补间）：260/220ms、滑 14%p；
 * - 设置二级页、工具箱二级页（Compose `AnimatedContent`）：240/200ms、滑 1/4 页。
 *
 * 现在统一到这一组数字：**新页从 ±1/4 页宽滑入 + 淡入，旧页反向滑出 + 淡出**。
 * Compose 侧用 [slideSpec]；View 侧用 `R.anim.nav_enter_*` / `R.anim.nav_exit_*`，
 * 那四个 XML 里的数字和这里**一一对应**（改这里就要一起改那边，别只改一半）。
 */
object PageMotion {

    /** 新页滑入时长 */
    const val ENTER_MS = 240

    /** 旧页滑出时长 */
    const val EXIT_MS = 200

    /** 新页淡入时长（比滑动短，观感更"轻"） */
    const val FADE_IN_MS = 180

    /** 旧页淡出时长 */
    const val FADE_OUT_MS = 140

    /** 滑动距离 = 页宽的比例（1/4） */
    const val SLIDE_FRACTION = 0.25f

    /**
     * Compose 转场：[forward] = true（往下一级 / 往右切 Tab）新页从右进、旧页往左出。
     */
    fun slideSpec(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        return (
            slideInHorizontally(animationSpec = tween(ENTER_MS)) { width ->
                (dir * width * SLIDE_FRACTION).toInt()
            } + fadeIn(animationSpec = tween(FADE_IN_MS))
            ) togetherWith (
            slideOutHorizontally(animationSpec = tween(EXIT_MS)) { width ->
                (-dir * width * SLIDE_FRACTION).toInt()
            } + fadeOut(animationSpec = tween(FADE_OUT_MS))
            )
    }
}
