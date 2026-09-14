package moe.shizuku.manager.app

import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.core.res.isNight
import rikka.core.res.resolveColor
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    companion object {
        /** 等本地小动画（滑块滑动）走完再切 —— 切换时才不会"卡那一下" */
        const val SWITCH_DELAY_MS = 170L

        /** 新内容入场时长（滑入 + 淡入），短一点更跟手 */
        const val FADE_IN_MS = 220L

        /** 新内容从右侧滑入的幅度（占比） */
        private const val SLIDE_IN_FRACTION = 0.10f

        /** 设置页改完「模糊 / 亮暗」直接换窗口底，不用重建 Activity。 */
        @JvmStatic
        fun applyBackgroundNow(activity: android.app.Activity?) {
            val a = activity as? AppActivity ?: return
            a.applyCustomBackground()
        }

        /**
         * 需要重建的改动（深浅色 / 界面风格 / 主题色 / 清除背景）统一走这里。
         *
         * **不再先淡出**：淡出那 170ms 就是"切的时候卡一下"的来源 ——
         * 用户点完还得干等一段全透明，然后才重建。现在改成：
         * 先让点下去的那个本地动画（滑块滑到位）播完（[SWITCH_DELAY_MS]），
         * 立刻重建，新内容滑入 + 淡入。整个过程没有"先黑一下"的空档。
         */
        @JvmStatic
        fun runWithTransition(activity: android.app.Activity?, action: () -> Unit) {
            val a = activity as? AppActivity
            if (a == null) {
                action()
                return
            }
            a.scheduleSwitch(action)
        }

        /** 背景图明暗变了、但主题还没跟着换 → 需要重建一次才能把文字自适应叠上。 */
        @JvmStatic
        fun needsTextAdaptationRefresh(activity: android.app.Activity?): Boolean {
            val a = activity as? AppActivity ?: return false
            val wanted = wantsTextAdaptation(a)
            return wanted != a.textAdapted
        }

        /** 玻璃风格 + 自定义背景偏暗 → 叠「亮字 + 淡白卡片」那层 Overlay */
        private fun wantsTextAdaptation(context: android.content.Context): Boolean =
            ThemeHelper.isUsingGlass() &&
                BackgroundHelper.isEnabled(context) &&
                BackgroundHelper.isBackgroundDark(context)
    }

    /** 本 Activity 的主题里是否已经叠过「文字自适应」Overlay */
    private var textAdapted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t0 = moe.shizuku.manager.utils.StartupTrace.begin()
        moe.shizuku.manager.utils.StartupTrace.newLaunch(this)
        // binder 状态缓存：显示用的判断不再每次都打一次 IPC
        moe.shizuku.manager.utils.ShizukuState.install()
        // 实时磨砂关着的时候，BlurTarget 那层离屏 RenderNode 必须一起关：
        // 它会把整个内容区变成一个硬件层，每帧多录一遍，滚动时还比窗口背景慢一帧
        // （整屏背景抖 / 卡片边缘拖影都来自这里）。
        moe.shizuku.blurview.BlurTarget.snapshotEnabled =
            moe.shizuku.manager.ui.glass.GlassMaterials.isLiveBlurEnabled()
        applyEdgeToEdge()
        moe.shizuku.manager.utils.StartupTrace.since(this, t0, "AppActivity.applyEdgeToEdge")
        val tBg = moe.shizuku.manager.utils.StartupTrace.now()
        applyCustomBackground()
        moe.shizuku.manager.utils.StartupTrace.since(this, tBg, "AppActivity.applyCustomBackground")
        val tHr = moe.shizuku.manager.utils.StartupTrace.now()
        applyHighRefreshRate()
        moe.shizuku.manager.utils.StartupTrace.since(this, tHr, "AppActivity.applyHighRefreshRate")
        fadeInContent()
        moe.shizuku.manager.utils.StartupTrace.since(this, t0, "AppActivity.onCreate total")
    }

    /**
     * 重建（切深色模式 / 切界面风格）之后内容滑入 + 淡入。
     */
    private fun fadeInContent() {
        val content = findViewById<android.view.View>(android.R.id.content) ?: return
        val width = content.width
        content.alpha = 0f
        content.translationX = width * SLIDE_IN_FRACTION
        content.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(FADE_IN_MS)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    /**
     * [runWithTransition] 的实现：等本地小动画播完 → 立刻执行切换动作。
     *
     * 关键点是**不做淡出**：淡出会让人先看到一段空档（"卡一下"），
     * 而重建本身是必须的（XML 主题属性只在 Activity 创建时解析）。
     * 所以顺序是：滑块滑到位（170ms）→ 重建 → 新内容滑入淡入。
     */
    private fun scheduleSwitch(action: () -> Unit) {
        if (isFinishing || isDestroyed) {
            action()
            return
        }
        val decor = window?.decorView
        if (decor == null) {
            action()
            return
        }
        decor.postDelayed({ if (!isFinishing && !isDestroyed) action() }, SWITCH_DELAY_MS)
    }

    /**
     * 自定义背景图片：设置里选过图就用它当窗口底（玻璃卡片是半透明的，会直接透出这张图）。
     * 没设置就走主题的纯色底（`android:windowBackground = ?attr/colorSurface`）。
     *
     * 应用内的滑块改动走 [applyCustomBackground] 的公开入口，无需重建 Activity。
     */
    internal fun applyCustomBackground() {
        val window = window ?: return

        // 【性能】解码底图（1600px 左右的 PNG，约 20MB 位图）以前是在 onCreate 主线程上做的，
        // 冷启动那几百毫秒里它占一块。现在：命中缓存就直接用，没命中就丢到后台线程解码，
        // 好了再换窗口底 —— 内容本来就有 220ms 淡入，晚一帧换底看不出来。
        val cached = BackgroundHelper.cachedWindowDrawable(this)
        if (cached != null) {
            window.setBackgroundDrawable(cached)
        } else {
            Thread {
                val drawable = BackgroundHelper.loadDrawable(this)
                runOnUiThread {
                    if (drawable != null && !isFinishing && !isDestroyed) {
                        window.setBackgroundDrawable(drawable)
                    }
                }
            }.start()
        }

        // 自愈：处理图还没生成（模糊/亮暗那一步没跑成）就补一次，
        // 否则窗口底会退回到未处理的底图 —— 表现就是"模糊和亮暗没效果"。
        if (BackgroundHelper.isProcessedStale(this) && !backgroundProcessing) {
            backgroundProcessing = true
            Thread {
                BackgroundHelper.process(this)
                runOnUiThread {
                    backgroundProcessing = false
                    if (!isFinishing && !isDestroyed) {
                        BackgroundHelper.loadDrawable(this)?.let { window.setBackgroundDrawable(it) }
                        BackgroundHelper.invalidateBitmapCache()
                    }
                }
            }.start()
        }
    }

    /** 防止自愈逻辑递归触发 */
    private var backgroundProcessing = false

    /**
     * 沉浸式（Edge-to-Edge）：内容延伸到状态栏/导航栏下面。
     *
     * - [WindowCompat.setDecorFitsSystemWindows] 关掉系统自动留白（状态栏那块不再被系统涂黑）；
     * - 状态栏/导航栏背景透明（主题里已设 `android:statusBarColor=transparent`，这里再兜一层）；
     * - 顶部有 `appbar_scrim` 深色渐变遮罩，所以状态栏图标统一用**浅色**（白），
     *   标题栏透明之后标题是白字 + 阴影，压在任何壁纸上都清晰。
     */
    private fun applyEdgeToEdge() {
        val window = window ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        // 顶部遮罩是深色的 → 状态栏图标固定用浅色，保证可见
        controller.isAppearanceLightStatusBars = false
        // 自定义背景偏暗时，导航栏图标也得转浅（否则浅色主题 + 夜景照片 = 手势条看不见）
        controller.isAppearanceLightNavigationBars = !resources.configuration.isNight() && !textAdapted
    }

    override fun onResume() {
        super.onResume()
        // Re-check so toggling the setting applies immediately, without
        // recreating activities that are already on the back stack.
        applyHighRefreshRate()
    }

    /**
     * Prefer the highest refresh rate supported by the display so that UI
     * animations run smoothly on high refresh rate devices.
     *
     * On Android 11+ (API 30) the same can be declared per-activity via
     * android:preferredRefreshRate in the manifest, but setting the window's
     * preferred display mode at runtime works from API 23 on every device.
     *
     * Controlled by the "high_refresh_rate" setting; when disabled the
     * preference is cleared so the system's default mode is used.
     */
    private fun applyHighRefreshRate() {
        val window = window ?: return
        val lp = window.attributes

        if (!ShizukuSettings.isHighRefreshRateEnabled()) {
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                window.attributes = lp
            }
            return
        }

        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        } ?: return

        val modes = display.supportedModes
        if (modes.isEmpty()) return

        val current = display.mode
        // Prefer modes with the same resolution as the current one, so devices
        // whose peak refresh mode uses a lower resolution keep their full size.
        val best = modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: modes.maxByOrNull { it.refreshRate }
            ?: return

        if (lp.preferredDisplayModeId == best.modeId) return
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
    }

    override fun computeUserThemeKey(): String {
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor() + ThemeHelper.getColorTheme() + ThemeHelper.getUiStyle()
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)

        // 主题色自选：未跟随系统色时叠加用户选择的色板
        if (!ThemeHelper.isUsingSystemColor()) {
            val overlay = ThemeHelper.getColorThemeStyleRes()
            if (overlay != 0) theme.applyStyle(overlay, true)
        }

        // 双风格（MD3 / 玻璃材质）：玻璃风格叠加主题 Overlay，
        // 让对话框容器透明，好让窗口自己的圆角玻璃底 + Android 12 窗口模糊透出来。
        if (ThemeHelper.isUsingGlass()) {
            theme.applyStyle(R.style.ThemeOverlay_Glass, true)

            // 文字自适应：自定义背景图偏暗（浅色主题配夜景照片那种）时，
            // 再叠一层「亮字 + 淡白卡片」的 Overlay，否则正文和卡片会一起沉进照片里。
            textAdapted = wantsTextAdaptation(this)
            if (textAdapted) {
                theme.applyStyle(R.style.ThemeOverlay_Glass_OnDarkBackground, true)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars()

        val window = window
        val theme = theme

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window?.decorView?.post {
                if (window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0 >= Resources.getSystem().displayMetrics.density * 40) {
                    window.navigationBarColor =
                        theme.resolveColor(android.R.attr.navigationBarColor) and 0x00ffffff or -0x20000000
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                } else {
                    window.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = true
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}