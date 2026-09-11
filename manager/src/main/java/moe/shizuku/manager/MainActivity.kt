package moe.shizuku.manager

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.android.material.navigation.NavigationBarView
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.ActivityMainBinding
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.setup.SetupActivity
import rikka.core.util.ResourceUtils
import rikka.lifecycle.Status

/**
 * KernelSU 风格主界面：单 Activity + 悬浮胶囊底部导航 + Jetpack Navigation。
 *
 * Tab：应用管理 / 首页 / 日志 / 设置（首页为默认）。
 * 应用管理 Tab 上的 Badge 显示已授权应用数量（对应 KernelSU 的超级用户计数徽标）。
 */
class MainActivity : AppActivity() {

    companion object {
        const val EXTRA_TAB = "moe.shizuku.manager.extra.TAB"
        const val TAB_APPS = "apps"
        const val TAB_LOGS = "logs"
        const val TAB_SETTINGS = "settings"

        /** 次级页目的地（Navigation destination id） */
        const val EXTRA_DESTINATION = "moe.shizuku.manager.extra.DESTINATION"
        const val EXTRA_DESTINATION_ARGS = "moe.shizuku.manager.extra.DESTINATION_ARGS"

        @JvmStatic
        fun tabIntent(context: Context, tab: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TAB, tab)

        /** 单入口：所有次级页都通过 MainActivity 内的 NavController 打开 */
        @JvmStatic
        fun destinationIntent(context: Context, destinationId: Int, args: Bundle? = null): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destinationId)
                .putExtra(EXTRA_DESTINATION_ARGS, args)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** 顶层 Tab：底栏可见集合，同时是滑动胶囊的动画目标集合 */
        private val TOP_LEVEL_TABS = setOf(
            R.id.home_fragment, R.id.apps_fragment,
            R.id.logs_fragment, R.id.settings_fragment
        )

        /** 胶囊滑动时长（ms）：M3 推荐的中等时长，肉眼可辨又不拖沓 */
        private const val PILL_SLIDE_DURATION = 280L

        /** 胶囊首次出现淡入时长（ms） */
        private const val PILL_FADE_IN_DURATION = 160L

        /** M3 强调减速曲线（先快后慢、到位即停、无回弹），替代原弹簧动画 */
        private val PILL_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    private var binding: ActivityMainBinding? = null
    private val appsModel by appsViewModel()

    // 滑动胶囊位移动画实例（重建前先 cancel，快速连点时从当前位置继续滑，轨迹连续）
    private var pillAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动：先跑设置向导（原 HomeActivity 的逻辑）
        if (!ShizukuSettings.isSetupCompleted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val binding = ActivityMainBinding.inflate(layoutInflater)
        this.binding = binding
        setContentView(binding.root)
        applyNavCapsuleInsets()
        setupAcrylicNav()

        val navController = findNavController() ?: return
        val nav = binding.navCapsule.nav as NavigationBarView
        NavigationUI.setupWithNavController(nav, navController)
        applyLogsTabVisibility()
        applyNavCapsuleVisibility(navController)
        setupNavPill(navController)

        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                setAppsBadge(it.data ?: 0)
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun findNavController(): NavController? {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        return navHostFragment?.navController
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val nav = binding?.navCapsule?.nav as? NavigationBarView ?: return

        // 次级页目的地导航（KernelSU 同款转场动画）
        val destination = intent.getIntExtra(EXTRA_DESTINATION, 0)
        if (destination != 0) {
            intent.removeExtra(EXTRA_DESTINATION)
            val args = intent.getBundleExtra(EXTRA_DESTINATION_ARGS)
            intent.removeExtra(EXTRA_DESTINATION_ARGS)
            findNavController()?.navigate(
                destination,
                args,
                androidx.navigation.NavOptions.Builder()
                    .setEnterAnim(R.anim.fragment_enter)
                    .setExitAnim(R.anim.fragment_exit)
                    .setPopEnterAnim(R.anim.fragment_enter_pop)
                    .setPopExitAnim(R.anim.fragment_exit_pop)
                    .setLaunchSingleTop(true)
                    .build()
            )
            return
        }

        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            nav.selectedItemId = R.id.settings_fragment
            return
        }
        when (intent.getStringExtra(EXTRA_TAB)) {
            TAB_APPS -> nav.selectedItemId = R.id.apps_fragment
            TAB_LOGS -> nav.selectedItemId = R.id.logs_fragment
            TAB_SETTINGS -> nav.selectedItemId = R.id.settings_fragment
        }
    }

    /** 悬浮胶囊底部导航：在基础间距上叠加手势导航条高度，使胶囊悬浮于手势条上方 */
    private fun applyNavCapsuleInsets() {
        val capsule = binding?.navCapsule?.root ?: return
        val base = resources.getDimensionPixelSize(R.dimen.ksu_nav_margin_bottom)
        ViewCompat.setOnApplyWindowInsetsListener(capsule) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = base + navBar.bottom
            }
            insets
        }
    }

    /**
     * 亚克力悬浮导航：BlurView 实时模糊 BlurTarget（整个内容区）中位于胶囊
     * 下方的部分，叠加 nav_frost_scrim 罩层形成 acrylic 玻璃质感。
     * 与 AppBarActivity 的磨砂顶栏同一套机制（bundled Dimezis/BlurView）。
     */
    private fun setupAcrylicNav() {
        val binding = binding ?: return
        // 关键：Material BottomNavigationView 的 applyWindowInsets() 会把系统手势条
        // inset 叠加为内部 padding（底 + 左右），用于传统贴底导航避让。悬浮胶囊已由
        // applyNavCapsuleInsets 用外层 margin 避让；若不覆盖，固定高度会被 inset
        // padding 吃掉，图标被竖直裁成一条（厚手势条机型必现）。这里替换为空实现，
        // insets 原样返回继续分发。
        ViewCompat.setOnApplyWindowInsetsListener(binding.navCapsule.nav) { _, insets -> insets }
        val radius = 20f * resources.displayMetrics.density
        binding.navCapsule.navBlur
            .setupWith(binding.blurTarget)
            .setFrameClearDrawable(window?.decorView?.background)
            .setBlurRadius(radius)
    }

    /**
     * KernelSU 行为：底栏只在顶层 Tab（首页/应用管理/日志/设置）显示，
     * 进入次级页（激活/教程/终端等）时隐藏，返回时恢复。
     */
    private fun applyNavCapsuleVisibility(navController: NavController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding?.navCapsule?.root?.isVisible = destination.id in TOP_LEVEL_TABS
        }
    }

    /**
     * 选中胶囊（Figma「Home - Bottom」：全圆角玻璃胶囊包裹选中 tab 的图标+标签）：
     * 共享胶囊宽度对齐选中 tab（等分恒宽），切换 Tab 时沿水平方向平滑滑过去，
     * 替代 M3 每项各自的静态指示器（布局中已关闭 itemActiveIndicator）。
     * 由 OnDestinationChanged 驱动，不触碰 NavigationUI 设置的 ItemSelectedListener，
     * menu / 徽标 / 日志 Tab 显隐逻辑完全不受影响。
     * 动画用 ValueAnimator + M3 强调减速曲线：匀速率滑动、到位即停，无弹簧回弹。
     */
    private fun setupNavPill(navController: NavController) {
        val binding = binding ?: return
        val nav = binding.navCapsule.nav
        val pill = binding.navCapsule.navActivePill

        // 胶囊目标 x：胶囊与 nav 同为 BlurView 直接子视图（布局原点一致），
        // translationX 直接取 nav.left + itemView.left；宽度对齐选中 tab（等分恒宽）。
        // 目标 tab 不在栏内（例如隐藏日志后通过 intent 进入）时返回 null。
        fun capsuleTarget(itemId: Int): Float? {
            val itemView = nav.findViewById<View>(itemId) ?: return null
            if (itemView.width == 0) return null
            if (pill.layoutParams.width != itemView.width) {
                pill.updateLayoutParams { width = itemView.width }
            }
            return nav.left + itemView.left.toFloat()
        }

        // 无动画对齐：首次定位，及旋转屏幕 / 日志 Tab 显隐（removeItem）等布局变化时
        fun snapToSelection() {
            val target = capsuleTarget(nav.selectedItemId) ?: return
            pillAnimator?.cancel()
            pill.translationX = target
            if (!pill.isVisible) {
                // 首次出现淡入，避免硬切
                pill.alpha = 0f
                pill.isVisible = true
                pill.animate().alpha(1f).setDuration(PILL_FADE_IN_DURATION).start()
            }
        }

        nav.doOnPreDraw { snapToSelection() }
        nav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> snapToSelection() }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id !in TOP_LEVEL_TABS) return@addOnDestinationChangedListener
            val target = capsuleTarget(destination.id) ?: return@addOnDestinationChangedListener

            pill.isVisible = true
            // 滑块切换：从当前位置匀速率滑向目标 tab（不跳动、不瞬移、无回弹）。
            // 连点时 cancel 后从当前位置重新起滑，轨迹始终连续。
            pillAnimator?.cancel()
            pillAnimator = ValueAnimator.ofFloat(pill.translationX, target).apply {
                duration = PILL_SLIDE_DURATION
                interpolator = PILL_INTERPOLATOR
                addUpdateListener { pill.translationX = it.animatedValue as Float }
                start()
            }
        }
    }

    /** 自定义功能：设置里可隐藏「日志」Tab（切换后设置页会 recreate 本 Activity 生效） */
    private fun applyLogsTabVisibility() {
        val show = ShizukuSettings.getPreferences().getBoolean("show_logs_tab", true)
        if (!show) {
            (binding?.navCapsule?.nav as? NavigationBarView)?.menu?.removeItem(R.id.logs_fragment)
        }
    }

    private fun setAppsBadge(count: Int) {
        runOnUiThread {
            val nav = binding?.navCapsule?.nav as? NavigationBarView ?: return@runOnUiThread
            val badge = nav.getOrCreateBadge(R.id.apps_fragment)
            badge.backgroundColor =
                ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorPrimary)
            badge.badgeTextColor =
                ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorOnPrimary)
            if (count > 0) {
                badge.isVisible = true
                badge.number = count
            } else {
                badge.isVisible = false
            }
        }
    }

    override fun onApplyUserThemeResource(theme: android.content.res.Resources.Theme, isDecorView: Boolean) {
        super.onApplyUserThemeResource(theme, isDecorView)
        // 设置 Tab 使用 RikkaX M3 Preference 风格（原 SettingsActivity 的 overlay）
        theme.applyStyle(R.style.ThemeOverlay_Rikka_Material3_Preference, true)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController()
        return (navController?.navigateUp() ?: false) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        // 释放进行中的位移动画，避免泄漏已销毁的视图引用
        pillAnimator?.cancel()
        pillAnimator = null
        super.onDestroy()
        binding = null
    }
}