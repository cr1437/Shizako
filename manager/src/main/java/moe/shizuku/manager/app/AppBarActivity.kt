package moe.shizuku.manager.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import moe.shizuku.blurview.BlurTarget
import moe.shizuku.blurview.BlurView
import com.google.android.material.appbar.AppBarLayout
import moe.shizuku.manager.R
import rikka.core.ktx.unsafeLazy

abstract class AppBarActivity : AppActivity() {

    private val rootView: ViewGroup by unsafeLazy {
        findViewById<ViewGroup>(R.id.root)
    }

    private val toolbarContainer: AppBarLayout by unsafeLazy {
        findViewById<AppBarLayout>(R.id.toolbar_container)
    }

    private val toolbar: Toolbar by unsafeLazy {
        findViewById<Toolbar>(R.id.toolbar)
    }

    private val blurView: BlurView by unsafeLazy {
        findViewById<BlurView>(R.id.blur_view)
    }

    /**
     * Everything below the app bar is wrapped in a BlurTarget so the BlurView
     * in the toolbar can snapshot and blur it in real time as it scrolls
     * behind the bar (Bundled source of https://github.com/Dimezis/BlurView (Apache-2.0),
     * repackaged under moe.shizuku.blurview.).
     */
    private var blurTarget: BlurTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.setContentView(getLayoutId())

        setSupportActionBar(toolbar)

        // The M3 AppBarLayout style applies a surface background tint over any
        // background; the frosted glass is drawn by the BlurView child instead,
        // so the container itself must stay backgroundless. See
        // material-components#1597.
        toolbarContainer.backgroundTintList = null

        // Extend the frost under the status bar. The AppBarLayout used to
        // inset itself via fitsSystemWindows, which pushed the BlurView below
        // the status bar and left an unfrosted strip at the top of the screen.
        // This listener replaces the AppBarLayout's internal inset handling:
        // the inset is applied to the BlurView as padding instead, and since
        // BlurView draws its blur, noise and scrim over its full bounds
        // (padding only offsets the toolbar child), the frosted glass now
        // covers the status bar area as well.
        ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer) { _, insets ->
            val top = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            blurView.setPadding(0, top, 0, 0)
            insets
        }

        // Fragment-based activities declare their BlurTarget directly in the
        // layout (appbar_fragment_activity.xml).
        findViewById<BlurTarget>(R.id.blur_target)?.let { attachBlur(it) }

        // 顶栏标题按背景图定黑白（不然亮背景上的白字、暗背景上的黑字都看不清）
        applyAppBarTextContrast()
    }

    /**
     * 顶栏标题自适应：拿顶栏那一带的背景图算 WCAG 对比度，决定白字还是深字，
     * 遮罩也跟着换（暗背景用深色渐变、亮背景用淡白渐变）。
     *
     * 只作用于玻璃风格 + 自定义背景图；MD3 标准模式保持原样。
     */
    private fun applyAppBarTextContrast() {
        if (!ThemeHelper.isUsingGlass()) return
        val bitmap = BackgroundHelper.cachedBitmap(this) ?: return

        val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE)
            as? android.view.WindowManager ?: return
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            android.graphics.Rect(0, 0, size.x, size.y)
        }
        val region = AdaptiveTextHelper.regionBehind(
            card = toolbarContainer,
            bitmap = bitmap,
            window = android.graphics.Point(bounds.width(), bounds.height()),
        )
        // 大标题是 HeadlineMedium（>18sp），按 WCAG 大字号标准 3:1 就够
        val plan = AdaptiveTextHelper.planForRegion(
            bitmap = bitmap,
            region = region,
            minRatio = AdaptiveTextHelper.MIN_CONTRAST_LARGE,
            preferWhite = true,
        ) ?: return

        toolbar.setTitleTextColor(plan.textColor)
        findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(
            R.id.toolbar_layout,
        )?.apply {
            setExpandedTitleColor(plan.textColor)
            setCollapsedTitleTextColor(plan.textColor)
        }

        // 遮罩换极性：深色字配淡白遮罩，浅色字配深色遮罩
        val scrimColor = if (plan.textColor == android.graphics.Color.BLACK) {
            R.drawable.appbar_scrim_light
        } else {
            R.drawable.appbar_scrim
        }
        findViewById<View>(R.id.appbar_scrim)?.setBackgroundResource(scrimColor)
    }

    private fun attachBlur(target: BlurTarget) {
        if (blurTarget === target) return
        blurTarget = target

        // 实时磨砂默认关：这层每帧把整个内容层重录一遍再模糊（同一屏画两遍以上），
        // 壁纸 + 半透明卡片一起上会掉帧、模糊还会比内容慢一帧（卡片边缘拖影）。
        // 关掉时把 BlurView 收起来，顶栏只留渐变遮罩。
        if (!moe.shizuku.manager.ui.glass.GlassMaterials.isLiveBlurEnabled()) {
            blurView.visibility = View.GONE
            return
        }
        blurView.visibility = View.VISIBLE

        // The window background (wallpaper + mask) is drawn under each blurred
        // frame so the frosted bar stays opaque even where the content is
        // fully transparent.
        val windowBackground = window?.decorView?.background
        val radius = 20f * resources.displayMetrics.density
        blurView.setupWith(target)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(radius)
    }

    @LayoutRes
    open fun getLayoutId(): Int {
        return R.layout.appbar_activity
    }

    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, null, false))
    }

    override fun setContentView(view: View?) {
        setContentView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        val content = view ?: return

        val target = blurTarget ?: BlurTarget(this).also {
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            rootView.addView(it, 0)
            attachBlur(it)
        }
        target.addView(content, 0, params)

        rootView.bringChildToFront(toolbarContainer)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars()
        window?.statusBarColor = Color.TRANSPARENT
    }
}

abstract class AppBarFragmentActivity : AppBarActivity() {

    override fun getLayoutId(): Int {
        return R.layout.appbar_fragment_activity
    }
}
