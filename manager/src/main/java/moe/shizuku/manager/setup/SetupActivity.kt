package moe.shizuku.manager.setup

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.ui.glass.GlassWindow
import moe.shizuku.manager.ui.style.UiStyle
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * 首次启动引导（**全面重构 · Compose 版**）。
 *
 * 七页 / 五步，**一步都不给跳过**：欢迎 → 免责声明 → 语言 → 外观（MD3 / 玻璃 + 深色模式）
 * → 激活方式 → 叫醒她（真激活）→ 完成。界面全部在 [SetupScreen] 里（Compose + 双风格）。
 *
 * 本类只干四件事：
 * 1. 持有全部页面状态（Compose 侧直接读，改了立刻重组）；
 * 2. 把设置写进和「调教设置」同一套偏好（语言 / 风格 / 深色 / 启动方式，两边永远一致）；
 * 3. 干真活：Root / 无线调试（含现场配对）/ 电脑 ADB / Dhizuku 设备所有者；
 * 4. 负责重建（语言 / 深色 / 黑色夜间 / 系统取色）时把当前步骤保住。
 */
class SetupActivity : AppActivity() {

    companion object {
        private const val KEY_STEP = "setup_step"
        private const val KEY_DISCLAIMER_AGREED = "setup_disclaimer_agreed"

        /** 连点保护窗口：一次切页动画内不再接受新的切页请求（和底栏切页的合并窗口同一思路）。 */
        private const val NAV_LOCK_MS = 360L
    }

    // ---------- 页面状态（Compose 直接读这些可变状态） ----------

    private var step by mutableStateOf(SetupStep.WELCOME)
    private var disclaimerAgreed by mutableStateOf(false)
    private var shizukuRunning by mutableStateOf(false)
    private var dhizukuActive by mutableStateOf(false)
    private var activatingDhizuku by mutableStateOf(false)
    /** 缺了这个 mutableStateOf，方法卡点了 UI 不刷新（表现为「点不了」）——已修。 */
    private var preferredMethod by mutableStateOf(ShizukuSettings.StartMethod.UNSET)
    private var paired by mutableStateOf(false)
    private var canAutoAdb by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var nightMode by mutableIntStateOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    private var blackNight by mutableStateOf(false)
    private var systemColor by mutableStateOf(true)

    /** 切页锁：防止连点把转场叠成一团 */
    private var navLockUntil = 0L

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStates() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStates() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        step = SetupStep.entries.getOrNull(savedInstanceState?.getInt(KEY_STEP, 0) ?: 0)
            ?: SetupStep.WELCOME
        disclaimerAgreed = savedInstanceState?.getBoolean(KEY_DISCLAIMER_AGREED, false) ?: false

        refreshStates()
        detectDefaultMethodIfNeeded()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 返回键 = 上一步；已经在第一页就退出（下次进来还在引导）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step != SetupStep.WELCOME) navigateBack() else finish()
            }
        })

        // 用 ComposeView + setContentView（本项目没有引入 activity-compose，ComposeView 是零依赖做法）
        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(composeView)

        composeView.setContent {
            SetupFlow(
                state = SetupUiState(
                    step = step,
                    disclaimerAgreed = disclaimerAgreed,
                    shizukuRunning = shizukuRunning,
                    dhizukuActive = dhizukuActive,
                    activatingDhizuku = activatingDhizuku,
                    preferredMethod = preferredMethod,
                    paired = paired,
                    canAutoAdb = canAutoAdb,
                    notificationsAllowed = notificationsAllowed,
                    nightMode = nightMode,
                    blackNight = blackNight,
                    systemColor = systemColor,
                ),
                onBack = { navigateBack() },
                onNext = { navigateNext() },
                onAgreeChange = { disclaimerAgreed = it },
                onSelectLanguage = { tag -> applyLanguage(tag) },
                onSelectUiStyle = { style -> applyUiStyle(style) },
                onSelectNightMode = { mode -> applyNightMode(mode) },
                onToggleBlackNight = { enabled -> applyBlackNight(enabled) },
                onToggleSystemColor = { enabled -> applySystemColor(enabled) },
                onSelectMethod = { method ->
                    ShizukuSettings.setPreferredStartMethod(method)
                    preferredMethod = method
                },
                onStartRoot = { startWithRoot() },
                onStartWireless = { startWithWireless() },
                onStartPairing = { startWirelessPairing() },
                onOpenDeveloperOptions = { openDeveloperOptions() },
                onOpenNotificationSettings = { openNotificationSettings() },
                onViewCommand = { showAdbCommand() },
                onActivateDhizuku = { activateDhizuku() },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step.ordinal)
        outState.putBoolean(KEY_DISCLAIMER_AGREED, disclaimerAgreed)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置 / 配对流程回来时，状态可能已经变了
        refreshStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    // ---------- 状态刷新 ----------

    private fun refreshStates() {
        shizukuRunning = Shizuku.pingBinder()
        dhizukuActive = DhizukuSettings.isDeviceOwner(this)
        preferredMethod = ShizukuSettings.getPreferredStartMethod()
        paired = runCatching {
            PreferenceAdbKeyStore(ShizukuSettings.getPreferences()).get() != null
        }.getOrDefault(false)
        canAutoAdb = ServiceStartHelper.canAdbAutoStart(this)
        notificationsAllowed = isNotificationEnabled()
        nightMode = ShizukuSettings.getNightMode()
        blackNight = ThemeHelper.isBlackNightTheme(this)
        systemColor = ThemeHelper.isUsingSystemColor()
    }

    /** 启动方式的默认值：有 root 就 root，Android 11+ 默认无线调试，否则电脑 ADB。 */
    private fun detectDefaultMethodIfNeeded() {
        if (preferredMethod != ShizukuSettings.StartMethod.UNSET) return
        val detected = when {
            EnvironmentUtils.isRooted() -> ShizukuSettings.StartMethod.ROOT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> ShizukuSettings.StartMethod.WIRELESS_ADB
            else -> ShizukuSettings.StartMethod.COMPUTER_ADB
        }
        ShizukuSettings.setPreferredStartMethod(detected)
        preferredMethod = detected
    }

    // ---------- 步骤导航 ----------

    private fun markNav() {
        navLockUntil = SystemClock.uptimeMillis() + NAV_LOCK_MS
    }

    private fun canNav(): Boolean = SystemClock.uptimeMillis() >= navLockUntil

    private fun navigateBack() {
        if (!canNav() || step == SetupStep.WELCOME) return
        markNav()
        step = SetupStep.entries[step.ordinal - 1]
    }

    private fun navigateNext() {
        if (!canNav()) return
        when (step) {
            SetupStep.DISCLAIMER -> if (!disclaimerAgreed) return else advance()
            SetupStep.FINISH -> finishSetup()
            else -> advance()
        }
    }

    private fun advance() {
        markNav()
        step = SetupStep.entries[step.ordinal + 1]
        refreshStates()
    }

    private fun finishSetup() {
        ShizukuSettings.setSetupCompleted(true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- 各步设置项的写入（和「调教设置」同一套偏好） ----------

    private fun applyUiStyle(style: String) {
        if (ThemeHelper.getUiStyle() == style) return
        ShizukuSettings.getPreferences().edit()
            .putString(ThemeHelper.KEY_UI_STYLE, style)
            .apply()
        // 运行时状态：Compose 页面 / 调色板立刻按新风格重组，不需要重建 Activity
        UiStyle.apply(style)
    }

    private fun applyLanguage(tag: String) {
        ShizukuSettings.getPreferences().edit()
            .putString(ShizukuSettings.LANGUAGE, tag)
            .apply()
        LocaleDelegate.defaultLocale = if ("SYSTEM" == tag) {
            LocaleDelegate.systemLocale
        } else {
            Locale.forLanguageTag(tag)
        }
        recreate()
    }

    private fun applyNightMode(mode: Int) {
        ShizukuSettings.getPreferences().edit()
            .putInt(ShizukuSettings.NIGHT_MODE, mode)
            .apply()
        nightMode = mode
        // 真的发生变化时系统会重建本 Activity；勾选状态从状态字段恢复
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun applyBlackNight(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(ThemeHelper.KEY_BLACK_NIGHT_THEME, enabled)
            .apply()
        blackNight = enabled
        if (ResourceUtils.isNightMode(resources.configuration)) recreate()
    }

    private fun applySystemColor(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(ThemeHelper.KEY_USE_SYSTEM_COLOR, enabled)
            .apply()
        systemColor = enabled
        recreate()
    }

    // ---------- 激活动作（真做事） ----------

    private fun startWithRoot() {
        if (Shizuku.pingBinder()) {
            refreshStates()
            return
        }
        ServiceStartHelper.startRoot { ok ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.setup_activate_started else R.string.setup_activate_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStates()
            }
        }
    }

    private fun startWithWireless() {
        if (Shizuku.pingBinder()) {
            refreshStates()
            return
        }
        if (canAutoAdb || paired) {
            Toast.makeText(this, R.string.setup_activate_started, Toast.LENGTH_SHORT).show()
            ServiceStartHelper.startAdb(this) { runOnUiThread { refreshStates() } }
        } else {
            // 还没配对：直接进入配对流程，而不是弹一个死路提示
            startWirelessPairing()
        }
    }

    /**
     * 进入 ADB 配对流程：和「配对教程」页同一套做法 ——
     * 通知可用就启动 [AdbPairingService]（前台服务 + 带 RemoteInput 的通知），
     * 用户在系统配对对话框拿到配对码后从通知栏输入，配对成功后服务会自动启动。
     */
    private fun startWirelessPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        if (!notificationsAllowed) {
            Toast.makeText(this, R.string.setup_wadb_need_notification, Toast.LENGTH_LONG).show()
            openNotificationSettings()
            return
        }

        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
            Toast.makeText(this, R.string.setup_wadb_pairing_started, Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow(
                        "android:start_foreground",
                        android.os.Process.myUid(),
                        packageName,
                        null,
                        null,
                    )
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(
                        this,
                        "OP_START_FOREGROUND is denied. What are you doing?",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                startService(intent)
            }
        }
    }

    private fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 部分厂商 ROM（ColorOS / MIUI / OriginOS）会抛 SecurityException
            e.printStackTrace()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showAdbCommand() {
        val command = Starter.adbCommand
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_activate_view_command)
            .setMessage(
                rikka.html.text.HtmlCompat.fromHtml(
                    getString(R.string.home_adb_dialog_view_command_message, command),
                ),
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (rikka.core.util.ClipboardUtils.put(this, command)) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_copied_to_clipboard, command),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    private fun activateDhizuku() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.setup_activate_dhizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }
        if (activatingDhizuku) return
        activatingDhizuku = true

        Thread {
            val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
            runOnUiThread {
                activatingDhizuku = false
                refreshStates()
                val active = DhizukuSettings.isDeviceOwner(this)
                Toast.makeText(
                    this,
                    if (result.success || active) {
                        R.string.setup_activate_dhizuku_success
                    } else {
                        R.string.setup_activate_dhizuku_failed
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    // ---------- 通知可用性（和配对教程页同一判断） ----------

    private fun isNotificationEnabled(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
}