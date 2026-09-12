package moe.shizuku.manager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.databinding.ActivityMainBinding
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.setup.SetupActivity
import moe.shizuku.manager.ui.liquidglass.LiquidGlassNavBar
import moe.shizuku.manager.ui.liquidglass.LiquidGlassNavItem
import moe.shizuku.manager.ui.nav.Md3NavBar
import moe.shizuku.manager.ui.nav.Md3NavColors
import moe.shizuku.manager.ui.nav.NavUiState
import rikka.core.util.ResourceUtils
import rikka.lifecycle.Status

/**
 * 主界面：单 Activity + Jetpack Navigation + **Jetpack Compose 底栏（双风格）**。
 *
 * - **玻璃材质**（默认）：Liquid Glass 悬浮胶囊 —— View 侧 BlurView 提供实时磨砂底，
 *   Compose 叠半透明底色 / 边缘折射 / 泛光 / 滑块指示器。
 * - **MD3**：Shizuku 同款标准 Material 3 底栏 —— 贴底整条、不透明 surfaceContainer、
 *   每项 M3 选中指示器 + 图标 + 文字标签。
 *
 * 两套风格由 设置 → 用户界面 → 界面风格 切换（切完 recreate 生效）。
 * Tab：应用管理 / 首页 / 日志 / 设置（首页默认，日志可隐藏）。
 */
class MainActivity : AppActivity() {

    companion object {
        const val EXTRA_TAB = "moe.shizuku.manager.extra.TAB"
        const val TAB_APPS = "apps"
        const val TAB_MODULES = "modules"
        const val TAB_COMPUT = "comput"
        const val TAB_TOOLS = "tools"
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

        /** 顶层 Tab：底栏可见集合 */
        private val TOP_LEVEL_TABS = setOf(
            R.id.home_fragment, R.id.apps_fragment, R.id.modules_fragment,
            R.id.comput_fragment, R.id.toolbox_fragment, R.id.logs_fragment, R.id.settings_fragment
        )

    }

    private var binding: ActivityMainBinding? = null
    private val appsModel by appsViewModel()

    /** Compose 侧状态：当前目的地 / 徽标数量 / 日志 Tab 是否显示 / MD3 手势条内边距 */
    private var currentDestinationId by mutableIntStateOf(R.id.home_fragment)
    private var appsBadgeCount by mutableIntStateOf(0)
    private var showLogsTab by mutableStateOf(true)
    private var navBottomInsetPx by mutableIntStateOf(0)

    /** 磨砂底（BlurView + BlurTarget）是否已接线 */
    private var acrylicReady = false

    /** 底栏当前是否应当显示（显隐动画去重用） */
    private var navBarVisible = true

    /** 从主题解析出来的底栏配色（切主题 / 切风格都会 recreate 本 Activity） */
    private var navAccent = 0
    private var navOnAccent = 0
    private var navOnSurface = 0
    private var navOnSurfaceVariant = 0
    private var navSurfaceContainer = 0
    private var navSecondaryContainer = 0
    private var navOnSecondaryContainer = 0

    /** 当前风格：读运行时可观察状态（设置里切换立即生效，不需要重建 Activity） */
    private val isGlassStyle: Boolean get() = moe.shizuku.manager.ui.style.UiStyle.isGlass

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

        // 风格状态与偏好同步（进程内单例，设置页改完即更新）
        moe.shizuku.manager.ui.style.UiStyle.refresh()

        resolveNavColors()
        showLogsTab = ShizukuSettings.getPreferences().getBoolean("show_logs_tab", true)

        applyNavBarStyle()
        setupNavInsets()
        // 始终接线磨砂底（幂等）：MD3 下 BlurView 隐藏，切到玻璃时立刻就有磨砂
        ensureAcrylicNav()
        setupComposeNav()

        val navController = findNavController() ?: return
        applyNavVisibility(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // 选中的底栏项 = 当前页（不在底栏里的页面就一个都不选）
            currentDestinationId = destination.id
            applyNavVisibilityNow(destination.id)
        }
        currentDestinationId = navController.currentDestination?.id ?: R.id.home_fragment

        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                appsBadgeCount = it.data ?: 0
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

    // ---------- 页面转场 ----------

    // View 体系的 Fragment 转场只能用基于时长的 XML 动画：Material Motion 的 Transition
    // 会被 FragmentNavigator 的 setCustomAnimations 覆盖掉（有 anim 就用 anim，Transition 不播），
    // 所以统一用 NavOptions 指定 R.anim.page_*。观感对齐 M3 的 fade through：
    // 旧页快速淡出、新页稍晚淡入 + 0.98→1 轻微放大；页面内部的上浮交给 Compose 弹簧。

    /** 顶层 Tab / 次级页统一用同一组转场参数（横向滑动 shared axis X）。 */
    private fun pageNavOptions(popUpTo: Int? = null, forward: Boolean = true): NavOptions {
        val motion = moe.shizuku.manager.app.NavMotion
        val builder = NavOptions.Builder()
            .setEnterAnim(if (forward) motion.enterForward else motion.enterBackward)
            .setExitAnim(if (forward) motion.exitForward else motion.exitBackward)
            .setPopEnterAnim(motion.enterBackward)
            .setPopExitAnim(motion.exitBackward)
        if (popUpTo != null) {
            builder.setPopUpTo(popUpTo, true).setRestoreState(true)
        }
        return builder.build()
    }

    // ---------- 底栏：两种风格 ----------

    /**
     * 宿主尺寸随风格切换：
     * 玻璃 = 悬浮胶囊（高 76dp = 52+2×泛光溢出，四周 4dp 外边距）；
     * MD3 = 贴底整条 Material 3 底栏（80dp + 手势条高度，左右无边距，背景铺到屏幕底部）。
     *
     * 这里不做逐帧动画：改 LayoutParams 的每帧动画会让 View 层每帧 relayout，
     * 属于「避免 layout 动画」的反面。宿主一次切到目标尺寸即可 —— 底栏本体由 Compose 按新尺寸直接重画。
     */
    private fun applyNavBarStyle() {
        val binding = binding ?: return
        val glass = isGlassStyle

        val targetHeight = resources.getDimensionPixelSize(
            if (glass) R.dimen.ksu_nav_height_outer else R.dimen.ksu_nav_height_md3
        )
        val targetMargin = if (glass) {
            resources.getDimensionPixelSize(R.dimen.ksu_nav_outer_margin)
        } else {
            0
        }
        val bottomInset = navBottomInsetPx

        binding.navCapsule.isVisible = true
        binding.navCompose.isVisible = true
        // 实时磨砂默认关（每帧重录整屏 + 模糊，滚动会掉帧、模糊还慢一帧）
        binding.navBlur.isVisible = glass && moe.shizuku.manager.ui.glass.GlassMaterials.isLiveBlurEnabled()

        binding.navCapsule.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = if (glass) targetHeight else targetHeight + bottomInset
            marginStart = targetMargin
            marginEnd = targetMargin
            bottomMargin = if (glass) targetMargin + bottomInset else 0
        }

        // ComposeView 在玻璃下比宿主高（多出来的就是滑块泛光的溢出区）：
        // 它居中溢出到宿主之外（宿主 clipChildren=false），胶囊位置不变，
        // 但光晕有地方淡出去，不会在 ComposeView 边界被硬切。
        binding.navCompose.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
            if (glass) {
                height = resources.getDimensionPixelSize(R.dimen.ksu_nav_compose_height_glass)
                gravity = android.view.Gravity.CENTER
                width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            } else {
                height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                gravity = android.view.Gravity.FILL
                width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            }
        }
    }

    /** 手势条避让：玻璃用外边距抬起来；MD3 底栏背景铺到底、高度交给 Compose 当内边距。 */
    private fun setupNavInsets() {
        val binding = binding ?: return
        ViewCompat.setOnApplyWindowInsetsListener(binding.navCapsule) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            navBottomInsetPx = bottom
            // Compose 页面用同一个手势条高度算底部留白
            NavUiState.bottomInsetPx = bottom
            // 两个分支都补全尺寸与边距，避免从另一种风格切过来时留下脏参数
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val outerMargin = resources.getDimensionPixelSize(R.dimen.ksu_nav_outer_margin)
                if (isGlassStyle) {
                    height = resources.getDimensionPixelSize(R.dimen.ksu_nav_height_outer)
                    marginStart = outerMargin
                    marginEnd = outerMargin
                    bottomMargin = outerMargin + bottom
                } else {
                    height = resources.getDimensionPixelSize(R.dimen.ksu_nav_height_md3) + bottom
                    marginStart = 0
                    marginEnd = 0
                    bottomMargin = 0
                }
            }
            insets
        }
    }

    /**
     * 玻璃的实时磨砂底：BlurView 模糊内容区，裁成胶囊形后由 Compose 叠底色与光效。
     *
     * **幂等**：App 启动时若风格是 MD3，这里会先跳过；等设置里切到玻璃时再接线
     * （之前只在 onCreate 判断一次，导致「切到玻璃后磨砂底没接线，胶囊看起来没了」）。
     */
    private fun ensureAcrylicNav() {
        val binding = binding ?: return
        if (!moe.shizuku.manager.ui.glass.GlassMaterials.isLiveBlurEnabled()) return
        if (acrylicReady) return
        val radius = 20f * resources.displayMetrics.density
        binding.navBlur
            .setupWith(binding.blurTarget)
            .setFrameClearDrawable(window?.decorView?.background)
            .setBlurRadius(radius)
        acrylicReady = true
    }

    private fun setupComposeNav() {
        val binding = binding ?: return
        binding.navCompose.setContent {
            val density = LocalDensity.current
            val resources = LocalContext.current.resources
            val barHeight = with(density) { resources.getDimension(R.dimen.ksu_nav_height).toDp() }

            // 风格状态：设置里切换后立即重组 → 宿主尺寸对齐 + 底栏形变过渡
            val glass = moe.shizuku.manager.ui.style.UiStyle.isGlass
            androidx.compose.runtime.LaunchedEffect(glass) {
                // 切到玻璃时先把磨砂底接上（幂等）
                if (glass) ensureAcrylicNav()
                applyNavBarStyle()
            }

            // 入口与顺序来自用户自定义（设置 → 底部栏），改完立即重组，不重建 Activity
            val tabs = moe.shizuku.manager.ui.nav.NavTabs.visible()
                .filter { showLogsTab || it.destinationId != R.id.logs_fragment }
            val items = tabs.map { tab ->
                LiquidGlassNavItem(
                    id = tab.destinationId.toString(),
                    label = resources.getString(tab.titleRes),
                    icon = tab.icon,
                    selectedIcon = tab.selectedIcon,
                    badgeCount = if (tab.destinationId == R.id.apps_fragment) appsBadgeCount else 0,
                )
            }
            val selectedIndex = tabs.indexOfFirst { it.destinationId == currentDestinationId }
                .coerceAtLeast(0)
            val onSelected: (Int) -> Unit = { index -> navigateToTab(tabs[index].destinationId) }

            // 照搬原版用法：不套 shared-element / AnimatedContent，直接组合底栏。
            // 风格切换靠重组 + 宿主尺寸瞬时对齐，不再叠一层共享元素动画（那层是「动效打架」的来源）。
            val barModifier = Modifier.fillMaxSize()

            if (glass) {
                val glowOverflow = with(density) {
                    resources.getDimension(R.dimen.ksu_nav_glow_overflow).toDp()
                }
                LiquidGlassNavBar(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelected = onSelected,
                    modifier = barModifier,
                    barHeight = barHeight,
                    // 边距 / 手势条避让由 View 侧 FrameLayout 负责
                    horizontalMargin = 0.dp,
                    bottomMargin = 0.dp,
                    accent = Color(navAccent),
                    selectedContentColor = Color(navOnAccent),
                    unselectedContentColor = Color(navOnSurface),
                    // 磨砂底由 View 侧 BlurView 提供
                    frosted = false,
                    glowOverflow = glowOverflow,
                )
            } else {
                Md3NavBar(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelected = onSelected,
                    colors = Md3NavColors(
                        container = Color(navSurfaceContainer),
                        indicator = Color(navSecondaryContainer),
                        selectedIcon = Color(navOnSecondaryContainer),
                        selectedText = Color(navOnSecondaryContainer),
                        unselectedIcon = Color(navOnSurfaceVariant),
                        unselectedText = Color(navOnSurfaceVariant),
                        badgeContainer = Color(navAccent),
                        badgeText = Color(navOnAccent),
                    ),
                    modifier = barModifier,
                    // Material 3 规范：带文字标签的底栏高 80dp
                    barHeight = with(density) {
                        resources.getDimension(R.dimen.ksu_nav_height_md3).toDp()
                    },
                    bottomInset = with(density) { navBottomInsetPx.toDp() },
                )
            }
        }
    }

    private fun resolveNavColors() {
        navAccent = ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorPrimary)
        navOnAccent = ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorOnPrimary)
        navOnSurface = ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorOnSurface)
        navOnSurfaceVariant =
            ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorOnSurfaceVariant)
        navSurfaceContainer =
            ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorSurfaceContainer)
        navSecondaryContainer =
            ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorSecondaryContainer)
        navOnSecondaryContainer =
            ResourceUtils.resolveColor(theme, com.google.android.material.R.attr.colorOnSecondaryContainer)
    }

    /**
     * 切换顶层 Tab：与 NavigationUI 的行为一致（singleTop + 状态保存/恢复）。
     *
     * 过渡是**横向滑动**：往右切（目标 Tab 在当前 Tab 右边）新页从右滑入，
     * 往左切则反过来 —— 方向和底栏的排列一致，观感才对得上。
     */
    private fun navigateToTab(destinationId: Int) {
        val navController = findNavController() ?: return
        val current = navController.currentDestination?.id ?: return
        if (current == destinationId) return

        val tabs = moe.shizuku.manager.ui.nav.NavTabs.visible()
        val currentIndex = tabs.indexOfFirst { it.destinationId == current }
        val targetIndex = tabs.indexOfFirst { it.destinationId == destinationId }
        // 未知位置（比如从次级页跳回）默认按前进方向
        val forward = if (currentIndex >= 0 && targetIndex >= 0) {
            targetIndex > currentIndex
        } else {
            true
        }

        navController.navigate(
            destinationId,
            null,
            pageNavOptions(
                popUpTo = navController.graph.findStartDestination().id,
                forward = forward,
            ),
        )
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // 次级页目的地导航
        val destination = intent.getIntExtra(EXTRA_DESTINATION, 0)
        if (destination != 0) {
            intent.removeExtra(EXTRA_DESTINATION)
            val args = intent.getBundleExtra(EXTRA_DESTINATION_ARGS)
            intent.removeExtra(EXTRA_DESTINATION_ARGS)
            val navController = findNavController() ?: return
            val currentId = navController.currentDestination?.id
            // 工具页是「同一目的地 + tool 参数」的形态：参数变了（模块 → 模块目录）也要入栈，
            // 否则点了没反应、也没有转场。其他页面停在原地时就不重复叠一层。
            val toolReentry = destination == R.id.tool_page_fragment && currentId == destination
            if (currentId != destination || toolReentry) {
                // 激活页是「配对成功后」的固定落点：返回栈里已经有它就退回去（配对教程 → 激活，
                // 而不是在教程上面再叠一层激活），栈里没有才新建
                if (destination != R.id.activation_fragment ||
                    !navController.popBackStack(destination, false)
                ) {
                    navController.navigate(destination, args, pageNavOptions())
                }
            }
            return
        }

        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            navigateToTab(R.id.settings_fragment)
            return
        }
        when (intent.getStringExtra(EXTRA_TAB)) {
            TAB_APPS -> navigateToTab(R.id.apps_fragment)
            TAB_MODULES -> navigateToTab(R.id.modules_fragment)
            TAB_COMPUT -> navigateToTab(R.id.comput_fragment)
            TAB_TOOLS -> navigateToTab(R.id.toolbox_fragment)
            TAB_LOGS -> navigateToTab(R.id.logs_fragment)
            TAB_SETTINGS -> navigateToTab(R.id.settings_fragment)
        }
    }

    /**
     * KernelSU 行为：底栏只在顶层 Tab（首页/应用管理/日志/设置）显示，
     * 进入次级页（激活/教程/终端等）时隐藏，返回时恢复。
     */
    private fun applyNavVisibility(navController: NavController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            applyNavVisibilityNow(destination.id)
        }
        // 监听器注册时会立刻回调一次，这里再兜一次底（数量对不上时首帧也不会漏）
        // 首帧不做动画：避免冷启动时底栏从屏幕外滑进来
        applyNavVisibilityNow(navController.currentDestination?.id ?: 0, animate = false)
    }

    /**
     * 底栏显隐：**看当前页面是不是主人开着的底栏入口**，而不是写死的顶层页集合。
     *
     * 这样才有两种入口共用同一页的可能：模块/控制台/日志默认不在底栏里，
     * 从工具箱点进去时就是普通次级页（底栏自动让开、返回回到工具箱）；
     * 主人在「设置 → 底部栏」把它们打开后，它们又是正常的 Tab。
     */
    private fun applyNavVisibilityNow(destinationId: Int, animate: Boolean = true) {
        val tabIds = moe.shizuku.manager.ui.nav.NavTabs.visible().map { it.destinationId }.toSet()
        val show = destinationId in tabIds
        // Compose 页面（控制台 / 设置 / 工具箱）靠这个在列表底部给底栏留白
        NavUiState.visible = show
        setNavBarVisible(show, animate)
    }

    /**
     * 底栏显隐动画：**淡出 + 往下滑一点**，而不是直接 GONE。
     *
     * 次级页（激活 / 工具页 / 详情）进去时底栏"啪"一下消失、返回时又"啪"一下出现，
     * 是之前观感上最硬的一处；这里和页面转场同一组曲线（0.2/0/0/1，200ms 淡出 160ms 收）。
     */
    private fun setNavBarVisible(show: Boolean, animate: Boolean = true) {
        val view = binding?.navCapsule ?: return
        if (show == navBarVisible) return
        navBarVisible = show

        view.animate().cancel()
        val offset = resources.displayMetrics.density * 24f
        val interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

        if (!animate) {
            view.visibility = if (show) View.VISIBLE else View.GONE
            view.alpha = if (show) 1f else 0f
            view.translationY = if (show) 0f else offset
            return
        }

        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(interpolator)
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .translationY(offset)
                .setDuration(160)
                .setInterpolator(interpolator)
                .withEndAction {
                    if (!navBarVisible) view.visibility = View.GONE
                }
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        // 主人可能在设置里改了底栏入口，回到页面时按新配置刷新一次
        applyNavVisibilityNow(findNavController()?.currentDestination?.id ?: 0, animate = false)
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
        super.onDestroy()
        binding = null
    }
}
