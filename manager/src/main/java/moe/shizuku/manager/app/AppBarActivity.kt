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
    }

    private fun attachBlur(target: BlurTarget) {
        if (blurTarget === target) return
        blurTarget = target

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
