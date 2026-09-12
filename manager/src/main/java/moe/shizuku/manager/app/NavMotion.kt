package moe.shizuku.manager.app

import androidx.navigation.NavOptions
import moe.shizuku.manager.R

/**
 * 全应用统一的页面转场参数：**横向滑动**（M3 shared axis X）。
 *
 * View 体系的 Fragment 转场只能用 XML 补间动画 —— Material Motion 的 Transition
 * 会被 `FragmentNavigator.setCustomAnimations` 覆盖掉（有 anim 用 anim，Transition 不播），
 * 所以所有 `navigate()` 都带上这一组：
 *
 * - 前进：新页从右侧滑入（14%p → 0，260ms）并淡入；旧页向左滑出（0 → -10%p，220ms）并淡出。
 * - 后退（pop）：方向反过来 —— 新页从左侧滑入，旧页向右滑出。
 * - 切换底栏 Tab 时按 Tab 顺序决定方向：往右切就前进、往左切就后退，
 *   滑动方向和你手指移动的方向一致，不会出现"往右切却从左边滑进来"。
 */
object NavMotion {

    val enterForward: Int get() = R.anim.nav_enter_forward
    val exitForward: Int get() = R.anim.nav_exit_forward
    val enterBackward: Int get() = R.anim.nav_enter_backward
    val exitBackward: Int get() = R.anim.nav_exit_backward

    private val forwardOptions: NavOptions by lazy {
        NavOptions.Builder()
            .setEnterAnim(enterForward)
            .setExitAnim(exitForward)
            .setPopEnterAnim(enterBackward)
            .setPopExitAnim(exitBackward)
            .build()
    }

    private val backwardOptions: NavOptions by lazy {
        NavOptions.Builder()
            .setEnterAnim(enterBackward)
            .setExitAnim(exitBackward)
            .setPopEnterAnim(enterBackward)
            .setPopExitAnim(exitBackward)
            .build()
    }

    /** 次级页（进详情）= 前进方向 */
    fun pageOptions(): NavOptions = forwardOptions

    /** 按滑动方向取一组：forward = 往右切 Tab（新页从右进） */
    fun tabOptions(forward: Boolean): NavOptions =
        if (forward) forwardOptions else backwardOptions
}
