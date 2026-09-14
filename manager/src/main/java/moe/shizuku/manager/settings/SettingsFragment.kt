package moe.shizuku.manager.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.AUTO_UPDATE
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.ShizukuSettings.WATCHDOG_ENABLED
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.BackgroundHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.ui.glass.GlassWindow
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.update.AutoUpdateScheduler
import moe.shizuku.manager.update.DownloadProgressDialog
import moe.shizuku.manager.update.UpdateChecker
import moe.shizuku.manager.utils.CrashLog
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.watchdog.WatchdogService
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.manager.ShizukuLocales
import java.util.Locale

/**
 * 设置页（**Compose 重写版**）。
 *
 * 之前是 `PreferenceFragmentCompat` + `res/xml/settings.xml` 的偏好列表，样式由 rikka 的
 * M3 preference 决定 —— 玻璃风格下和首页卡片完全是两种材质。现在整页改成 Compose，
 * 直接复用首页/提示页的那套卡片组件（HintPage / HintCard），所以：
 * - 玻璃风格：和首页同一块料（白 30% + 噪点 + 28dp 圆角）；
 * - MD3 风格：标准 M3 卡片 + M3 Switch / 分段按钮。
 *
 * 所有开关的行为与原实现一致（都写成下面这些回调）。
 */
class SettingsFragment : Fragment() {

    companion object {
        const val KEY_API_LOG_ENABLED = "api_log_enabled"
        const val KEY_DEBUG_UNLOCKED = "debug_unlocked"
        /** 当前停在哪个设置页：重建后恢复，避免"设置一下就回到一级" */
        var lastPage: SettingsPage = SettingsPage.MAIN

        /**
         * 从别的地方直接进某个设置二级页（切到设置 Tab，并记下目标页让 [SettingsTabFragment] 照它打开）。
         *
         * 注意：**功能类**页面（模块 / 控制台 / 实验室…）现在都收进了工具箱，
         * 从工具箱点进去用的是 [moe.shizuku.manager.toolbox.ToolPageFragment]（独立次级页，
         * 返回回工具箱，不会再落进设置里），只有真正的「设置」才该走这里。
         */
        @JvmStatic
        fun openAt(context: android.content.Context, page: SettingsPage) {
            lastPage = page
            context.startActivity(
                moe.shizuku.manager.MainActivity.tabIntent(
                    context,
                    moe.shizuku.manager.MainActivity.TAB_SETTINGS,
                ),
            )
        }
        const val API_LOG_PATH = "/data/local/tmp/shizako-api.log"
        const val API_LOG_OFF_PATH = "/data/local/tmp/shizako-api.log.off"
    }

    private val listState = LazyListState()

    /** Compose 只读状态：Fragment 单向下发 */
    private var uiState by mutableStateOf(SettingsUiState())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // 只做内容：外壳（大标题折叠栏 + 工具栏）由 SettingsTabFragment 提供
        return ComposeView(inflater.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setContent {
                val palette = androidx.compose.runtime.remember(bgVersion, uiState.hasCustomBackground) {
                    resolveHintPalette(requireContext())
                }
                SettingsScreen(
                    state = uiState,
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = { expanded ->
                        (parentFragment as? SettingsTabFragment)?.setAppBarExpanded(expanded)
                    },
                    onUiStyle = ::setUiStyle,
                    onNightMode = ::setNightMode,
                    onBlackNight = ::setBlackNightTheme,
                    onUseSystemColor = ::setUseSystemColor,
                    onThemeColor = ::showThemeColorDialog,
                    onShowLogsTab = ::setShowLogsTab,
                    onHighRefresh = ::setHighRefreshRate,
                    onAutoDisableUsbDebugging = ::setAutoDisableUsbDebugging,
                    onStartOnBoot = ::setStartOnBoot,
                    onWatchdog = ::setWatchdog,
                    onTcpMode = ::setTcpMode,
                    onAutoUpdate = ::setAutoUpdate,
                    onApiLogEnabled = ::setApiLogEnabled,
                    onApiLogClear = ::clearApiLog,
                    onOpenApiLog = ::openApiLog,
                    onLanguage = ::showLanguageDialog,
                    onCheckUpdate = ::checkUpdate,
                    onCrashLogs = ::showCrashLogsDialog,
                    onTranslation = {
                        CustomTabsHelper.launchUrlOrCopy(
                            requireContext(),
                            getString(R.string.translation_url),
                        )
                    },
                    onUnlockDebug = ::unlockDebugItems,
                    // 关于页「给项目点个 Star」：系统浏览器打开项目主页
                    onOpenStar = {
                        moe.shizuku.manager.utils.StarPrompt.openRepo(requireContext())
                    },
                    onPickBackground = { pickImage.launch("image/*") },
                    onCropBackground = { cropPending = true },
                    onClearBackground = ::clearBackground,
                    onBackgroundBlur = { value ->
                        BackgroundHelper.setBlur(requireContext(), value.toInt())
                        uiState = uiState.copy(bgBlur = value)
                    },
                    onBackgroundDim = { value ->
                        BackgroundHelper.setDim(requireContext(), value.toInt())
                        uiState = uiState.copy(bgDim = value)
                    },
                    onBackgroundCommit = ::commitBackground,
                    // 二级页时接管系统返回键，返回一级菜单而不是退出
                    onPageChanged = { page ->
                        lastPage = page
                        subPage = page != SettingsPage.MAIN
                        backCallback.isEnabled = subPage
                    },
                    onBackActionReady = { backHolder = it },
                    // 模块页现在是设置里的二级页（走同一个滑动过渡），不再切 Tab
                    onOpenModules = {},
                    pendingModuleZip = pendingModuleZip,
                    onModuleZipConsumed = { pendingModuleZip = null },
                    onPickModuleZip = { moduleZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    onOpenModuleWebUi = { moduleId ->
                        startActivity(
                            moe.shizuku.manager.module.ModuleWebViewActivity.newIntent(
                                requireContext(),
                                moduleId,
                            ),
                        )
                    },
                    onOpenComput = {
                        startActivity(
                            moe.shizuku.manager.MainActivity.tabIntent(
                                requireContext(),
                                moe.shizuku.manager.MainActivity.TAB_COMPUT,
                            ),
                        )
                    },
                    onOpenAccessibility = {
                        startActivity(
                            android.content.Intent(
                                requireContext(),
                                moe.shizuku.manager.accessibility.AccessibilityManagerActivity::class.java,
                            ),
                        )
                    },
                    // 推荐应用（下载引导页）：次级页，走单入口的 NavController
                    onOpenRecommendedApps = {
                        startActivity(
                            MainActivity.destinationIntent(
                                requireContext(),
                                R.id.apps_download_fragment,
                            ),
                        )
                    },
                    onBackup = ::backupSettings,
                    onRestore = ::restoreSettings,
                    initialPage = lastPage,
                )

                // 裁切页：选完图自动弹出，也可以从「背景 → 裁切图片」再次进入
                if (cropPending) {
                    val ctx = requireContext()
                    moe.shizuku.manager.ui.crop.BackgroundCropDialog(
                        sourcePath = BackgroundHelper.originalFile(ctx).absolutePath,
                        accent = palette.accent,
                        onAccent = palette.onAccent,
                        onCancel = { cropPending = false },
                        onApply = ::applyCrop,
                        onResetSource = ::cropRemove,
                    )
                }
            }
        }
    }

    /** 是否停在二级页（决定系统返回键的行为） */
    private var subPage = false

    /** 模块页选中的 ZIP：选择器在本 Fragment 初始化时注册（Compose 里临时注册会崩） */
    private var pendingModuleZip by mutableStateOf<android.net.Uri?>(null)

    private val moduleZipLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingModuleZip = uri
    }
    /** 是否正在裁切背景图（选完图自动进裁切，也可以从背景页再进） */
    private var cropPending by mutableStateOf(false)

    /** 背景图版本号：换图 / 裁切 / 调模糊后 +1 → Compose 侧配色重算（文字自适应） */
    private var bgVersion by mutableIntStateOf(0)

    /**
     * 背景换完之后统一收尾：
     * - 明暗没跨过阈值 → 直接换窗口底（不重建，用户还在原地）；
     * - 跨过了（比如浅色主题配了张夜景照片）→ 重建一次让「文字自适应」的主题 Overlay 生效，
     *   重建后仍停在当前二级页（lastPage），所以不会被踢回一级菜单。
     */
    private fun refreshBackground() {
        bgVersion++
        val act = activity
        if (AppActivity.needsTextAdaptationRefresh(act)) {
            act?.recreate()
        } else {
            AppActivity.applyBackgroundNow(act)
        }
    }

    /** 系统图片选择器：选完拷进 filesDir，然后直接进裁切页取景 */
    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext()
        Thread {
            val ok = BackgroundHelper.import(context, uri)
            activity?.runOnUiThread {
                if (ok) {
                    // 先不重建：裁切页还开着，重建会把取景框关掉
                    bgVersion++
                    AppActivity.applyBackgroundNow(activity)
                    cropPending = true
                    refreshState()
                } else {
                    Toast.makeText(context, R.string.settings_bg_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 裁切完成：按分数裁原图 → 写使用图 → 重算处理图 → 换窗口底（不重建 Activity）。 */
    private fun applyCrop(left: Float, top: Float, right: Float, bottom: Float) {
        val context = requireContext()
        cropPending = false
        Thread {
            val ok = BackgroundHelper.applyCrop(context, left, top, right, bottom)
            activity?.runOnUiThread {
                if (ok) {
                    refreshBackground()
                    refreshState()
                } else {
                    Toast.makeText(context, R.string.settings_bg_crop_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }.start()
    }

    /** 裁切页里的「移除图片」 */
    private fun cropRemove() {
        cropPending = false
        clearBackground()
    }

    private fun clearBackground() {
        cropPending = false
        switchWithTransition {
            BackgroundHelper.clear(requireContext())
            recreateSoon() // 回到主题纯色底
        }
    }

    /** 模糊 / 亮暗 松手后重算处理图，再直接换窗口底（拖动中不重建 Activity）。 */
    private fun commitBackground() {
        val context = requireContext()
        Thread {
            BackgroundHelper.process(context)
            activity?.runOnUiThread {
                // 拖动过程中不重建：只刷新窗口底 + 让 Compose 侧的配色跟着重算
                bgVersion++
                AppActivity.applyBackgroundNow(activity)
            }
        }.start()
    }

    /** Compose 侧注册的「回到一级菜单」动作 */
    private var backHolder: (() -> Unit)? = null

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            backHolder?.invoke()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // 重建后可能直接停在二级页（lastPage）：返回键一开始就要接管，
        // 否则在二级页按返回会直接退出 App，而不是回一级菜单。
        subPage = lastPage != SettingsPage.MAIN
        backCallback.isEnabled = subPage
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    // ---------------- 状态 ----------------

    private fun refreshState() {
        val context = requireContext()
        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
        uiState = SettingsUiState(
            uiStyle = ThemeHelper.getUiStyle(),
            nightMode = ShizukuSettings.getNightMode(),
            blackNight = ThemeHelper.isBlackNightTheme(context),
            useSystemColor = ThemeHelper.isUsingSystemColor(),
            showLogsTab = ShizukuSettings.getPreferences().getBoolean("show_logs_tab", true),
            highRefresh = ShizukuSettings.isHighRefreshRateEnabled(),
            startOnBoot = context.packageManager.isComponentEnabled(componentName),
            watchdog = ShizukuSettings.isWatchdogEnabled(),
            autoUpdate = ShizukuSettings.isAutoUpdateEnabled(),
            language = ShizukuSettings.getPreferences().getString("language", "SYSTEM") ?: "SYSTEM",
            languageLabel = currentLanguageLabel(),
            themeColorLabel = currentThemeColorLabel(),
            contributors = getString(R.string.translation_contributors).toHtml().toString(),
            versionName = moe.shizuku.manager.BuildConfig.VERSION_NAME,
            supportsSystemColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            apiLogEnabled = ShizukuSettings.getPreferences()
                .getBoolean(KEY_API_LOG_ENABLED, true),
            debugUnlocked = ShizukuSettings.getPreferences()
                .getBoolean(KEY_DEBUG_UNLOCKED, false),
            autoDisableUsbDebugging = ShizukuSettings.getAutoDisableUsbDebugging(),
            tcpMode = ShizukuSettings.isTcpMode(),
            hasCustomBackground = BackgroundHelper.isEnabled(context),
            hasBackgroundOriginal = BackgroundHelper.hasOriginal(context),
            bgBlur = BackgroundHelper.blur(context).toFloat(),
            bgDim = BackgroundHelper.dim(context).toFloat(),
        )
    }

    private fun recreateSoon() {
        // 偏好值落盘/状态生效需要一帧，立刻 recreate 会读到旧值
        activity?.window?.decorView?.post { activity?.recreate() }
    }

    /**
     * 需要重建的切换统一走这里：**淡出 → 切换 → 新页面淡入**。
     *
     * 深浅色、界面风格、主题色、清除背景全都用同一条路径，时长和曲线一致，
     * 不会出现"有的切换硬闪、有的切换淡入"这种不统一。
     */
    private fun switchWithTransition(action: () -> Unit) {
        AppActivity.runWithTransition(activity, action)
    }

    // ---------------- 各项设置 ----------------

    private fun setUiStyle(value: String) {
        if (moe.shizuku.manager.ui.style.UiStyle.current == value) return
        ShizukuSettings.getPreferences().edit().putString("ui_style", value).apply()
        moe.shizuku.manager.ui.style.UiStyle.apply(value)
        // 卡片底色来自主题里的 ?ksuCardStyle，主题只在 Activity 创建时解析 → 必须重建
        switchWithTransition { recreateSoon() }
    }

    private fun setNightMode(value: Int) {
        if (ShizukuSettings.getNightMode() == value) return
        // 关键：先落盘再切换。以前只调 setDefaultNightMode，偏好没写，
        // 重建后读回旧值 → 外观页的滑块"弹回跟随系统"。
        ShizukuSettings.setNightMode(value)
        switchWithTransition {
            // 配置真的变了时 AppCompat 自己会重建，不要再手动 recreate 一次（会连闪两下）
            AppCompatDelegate.setDefaultNightMode(value)
        }
    }

    private fun setBlackNightTheme(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
        if (ResourceUtils.isNightMode(requireContext().resources.configuration)) {
            switchWithTransition { recreateSoon() }
        }
    }

    private fun setUseSystemColor(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
        if (ThemeHelper.isUsingSystemColor() != enabled) {
            switchWithTransition { recreateSoon() }
        }
    }

    private fun setShowLogsTab(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean("show_logs_tab", enabled).apply()
        recreateSoon()
    }

    private fun setHighRefreshRate(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(
            ShizukuSettings.HIGH_REFRESH_RATE, enabled,
        ).apply()
        // AppActivity.onResume 会重新应用
        activity?.recreate()
    }

    /** 开机自动关闭 USB 调试：写偏好即可，接收器在开机时读它 */
    private fun setAutoDisableUsbDebugging(enabled: Boolean) {
        ShizukuSettings.setAutoDisableUsbDebugging(enabled)
        uiState = uiState.copy(autoDisableUsbDebugging = enabled)
    }

    private fun setStartOnBoot(enabled: Boolean) {
        val context = requireContext()
        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
        context.packageManager.setComponentEnabled(componentName, enabled)
        refreshState()
    }

    private fun setWatchdog(enabled: Boolean) {
        val context = requireContext()
        ShizukuSettings.getPreferences().edit().putBoolean(WATCHDOG_ENABLED, enabled).apply()
        if (enabled) WatchdogService.start(context) else WatchdogService.stop(context)
        refreshState()
    }

    /** 持久 TCP 模式（照搬 Shevery）：开关只负责写偏好；真正切端口在下次启动服务 / 激活页提示里做。 */
    private fun setTcpMode(enabled: Boolean) {
        ShizukuSettings.setTcpMode(enabled)
        uiState = uiState.copy(tcpMode = enabled)
    }

    private fun setAutoUpdate(enabled: Boolean) {
        val context = requireContext()
        ShizukuSettings.getPreferences().edit().putBoolean(AUTO_UPDATE, enabled).apply()
        if (enabled) AutoUpdateScheduler.schedule(context) else AutoUpdateScheduler.cancel(context)
        refreshState()
    }

    /**
     * API 调用日志开关。
     *
     * 日志是 server（root/shell 身份）写 `/data/local/tmp/shizako-api.log` 的，
     * 两边没法直接通信 —— 用一个哨兵文件约定：开关关掉时创建 `....log.off`，
     * server 的 auditCall 看到它就跳过写入；打开时删掉哨兵。
     */
    private fun setApiLogEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_API_LOG_ENABLED, enabled).apply()
        Thread {
            ActivationRunner.run(
                if (enabled) {
                    "rm -f $API_LOG_OFF_PATH"
                } else {
                    "touch $API_LOG_OFF_PATH; : > $API_LOG_PATH"
                },
                timeoutSeconds = 10,
            )
            activity?.runOnUiThread { refreshState() }
        }.start()
    }

    /** 清空日志文件（保留开关状态）。 */
    private fun clearApiLog() {
        val context = requireContext()
        Thread {
            ActivationRunner.run(": > $API_LOG_PATH", timeoutSeconds = 10)
            activity?.runOnUiThread {
                Toast.makeText(context, R.string.settings_api_log_cleared, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** 关于页连点版本号 5 次：解锁隐藏项（翻车记录）。 */
    private fun unlockDebugItems() {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_DEBUG_UNLOCKED, true).apply()
        Toast.makeText(requireContext(), R.string.settings_debug_unlocked, Toast.LENGTH_SHORT).show()
        refreshState()
    }

    // ---------------- 对话框 ----------------

    private fun showThemeColorDialog() {
        val context = requireContext()
        val names = resources.getStringArray(R.array.theme_color_names)
        val values = resources.getStringArray(R.array.theme_color_values)
        val current = ShizukuSettings.getPreferences().getString("theme_color", "") ?: ""
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_theme_color)
            .setSingleChoiceItems(names, values.indexOf(current)) { dialog, which ->
                ShizukuSettings.getPreferences().edit()
                    .putString("theme_color", values[which]).apply()
                dialog.dismiss()
                // 主题色切换也走同一条过渡：淡出 → 重建 → 淡入
                switchWithTransition { recreateSoon() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    private fun showLanguageDialog() {
        val context = requireContext()
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES
        val labels = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                labels.add(getString(R.string.follow_system))
                continue
            }
            // 语言列表用**该语言自己的本土名称**（Deutsch / 日本語 / Русский…），
            // 而不是把外语名翻译成当前语言 —— 看不懂当前语言的人才找得到自己的语言。
            // 中文这两种特别处理：写全「简体中文」「繁體中文」，不写 zh-CN / zh-TW
            labels.add(
                moe.shizuku.manager.utils.LanguageNames.display(context, displayLocale.toString()),
            )
        }

        val currentTag = moe.shizuku.manager.utils.LanguageNames.normalize(
            ShizukuSettings.getPreferences().getString("language", "SYSTEM"),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels.toTypedArray(), localeTags.indexOf(currentTag)) { dialog, which ->
                val newValue = localeTags[which]
                ShizukuSettings.getPreferences().edit()
                    .putString("language", newValue).apply()
                LocaleDelegate.defaultLocale = if (newValue == "SYSTEM") {
                    LocaleDelegate.systemLocale
                } else {
                    Locale.forLanguageTag(newValue)
                }
                dialog.dismiss()
                recreateSoon()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    // ---------------- 备份 / 恢复（实验室功能里） ----------------
    //
    // 注册逻辑和工具箱里的实验室页共用一份（SettingsBackupHelper）：
    // 注意 ActivityResultLauncher 必须在 Fragment 初始化阶段注册（STARTED 之前），
    // 不能在点击回调里 registerForActivityResult —— 那样会抛 IllegalStateException。

    private val backupLauncher = SettingsBackupHelper.registerBackup(this)

    private val restoreLauncher = SettingsBackupHelper.registerRestore(this)

    private fun backupSettings() {
        backupLauncher.launch("shizako-settings.json")
    }

    private fun restoreSettings() {
        restoreLauncher.launch(arrayOf("application/json", "*/*"))
    }
    private fun checkUpdate() {
        val context = requireContext()
        // 离线时先说清楚，不弹「正在检查」这种自欺欺人的提示
        if (!moe.shizuku.manager.utils.NetworkUtils.isOnline(context)) {
            Toast.makeText(context, R.string.update_no_network, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        UpdateChecker.checkForUpdate(context) { info ->
            if (info != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10086)
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.update_available_title)
                    .setMessage(context.getString(R.string.update_dialog_message, info.tagName, info.body))
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        startDownloadWithDialog(context, info)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .also { GlassWindow.applyIfGlass(it) }
            } else {
                Toast.makeText(context, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownloadWithDialog(context: Context, info: UpdateChecker.ReleaseInfo) {
        val progressDialog = DownloadProgressDialog.show(
            context,
            title = context.getString(R.string.update_downloading),
            version = info.tagName,
            onCancel = { UpdateChecker.cancelDownload() },
        )
        progressDialog.setState(context.getString(R.string.update_connecting))
        UpdateChecker.downloadAndInstall(context, info, object : UpdateChecker.DownloadListener {
            override fun onProgress(downloaded: Long, total: Long, speedBps: Long) {
                if (progressDialog.isShowing) progressDialog.update(downloaded, total, speedBps)
            }

            override fun onRetry(attempt: Int, max: Int) {
                if (progressDialog.isShowing) {
                    progressDialog.setState(context.getString(R.string.update_retrying, attempt, max))
                }
            }

            override fun onComplete(apkFile: java.io.File) {
                progressDialog.dismiss()
            }

            override fun onFailed(reason: String) {
                progressDialog.dismiss()
            }

            override fun onCancelled() {
                progressDialog.dismiss()
                Toast.makeText(context, R.string.update_download_cancelled, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showCrashLogsDialog() {
        val context = requireContext()
        val files = CrashLog.files()
        if (files.isEmpty()) {
            Toast.makeText(context, R.string.settings_crash_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = files.map { displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_crash_logs)
            .setItems(labels) { _, which -> showCrashLogContent(files[which]) }
            .setNegativeButton(R.string.settings_crash_logs_delete_all) { _, _ ->
                CrashLog.deleteAll()
                Toast.makeText(context, R.string.settings_crash_logs_deleted, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    private fun showCrashLogContent(file: java.io.File) {
        val context = requireContext()
        val content = file.readText()
        val padding = (context.resources.displayMetrics.density * 16).toInt()
        val textView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = content
        }
        val scrollView = ScrollView(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(textView)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(displayName(file))
            .setView(scrollView)
            .setNeutralButton(R.string.settings_crash_logs_share) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                try {
                    startActivity(Intent.createChooser(intent, file.name))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setPositiveButton(R.string.settings_crash_logs_delete) { _, _ ->
                file.delete()
                Toast.makeText(context, R.string.settings_crash_logs_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    private fun openApiLog() {
        val context = requireContext()
        context.startActivity(MainActivity.tabIntent(context, MainActivity.TAB_LOGS))
    }

    private fun displayName(file: java.io.File): String {
        return try {
            val name = file.name.removePrefix("crash-").removeSuffix(".txt")
            val sdf = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            out.format(sdf.parse(name)!!)
        } catch (e: Exception) {
            file.name
        }
    }

    private fun currentLanguageLabel(): String {
        val tag = ShizukuSettings.getPreferences().getString("language", "SYSTEM") ?: "SYSTEM"
        if (tag == "SYSTEM") return getString(R.string.follow_system)
        // 一级菜单里也显示本土语言名，保持一致；中文写全（简体中文 / 繁體中文）
        return moe.shizuku.manager.utils.LanguageNames.display(requireContext(), tag)
    }

    private fun currentThemeColorLabel(): String {
        val value = ShizukuSettings.getPreferences().getString("theme_color", "") ?: ""
        val names = resources.getStringArray(R.array.theme_color_names)
        val values = resources.getStringArray(R.array.theme_color_values)
        val index = values.indexOf(value)
        return if (index >= 0) names[index] else value
    }
}