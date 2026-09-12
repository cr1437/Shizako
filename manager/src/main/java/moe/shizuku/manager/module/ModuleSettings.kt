package moe.shizuku.manager.module

import android.content.Context
import moe.shizuku.manager.ShizukuSettings

/**
 * ADB Modules 的**策略开关**（照搬 Shevery 的三档访问模式 + Full Trust）。
 *
 * 三档访问模式：
 * - [AccessMode.SAFE] 安全模式：只允许手动动作（Action），Service / 后台 / WebUI 桥 / 联网一律不放行；
 * - [AccessMode.CUSTOM] 自定义：逐项开关（Service、后台动作、WebUI 桥、WebView 联网、WebUI 下载）；
 * - [AccessMode.FULL] 完全访问：全都放行。
 *
 * **Full Trust**：单个模块的例外 —— 用户长按模块卡片显式信任后，
 * 该模块绕过上面所有策略门（路径穿越、超时、输出上限这些硬安全限制照旧生效）。
 */
object ModuleSettings {

    enum class AccessMode(val value: String) {
        SAFE("safe"),
        CUSTOM("custom"),
        FULL("full"),
    }

    private const val KEY_ACCESS_MODE = "adb_module_access_mode"
    private const val KEY_SERVICE = "adb_module_service"
    private const val KEY_BACKGROUND = "adb_module_background"
    private const val KEY_WEBUI_BRIDGE = "adb_module_webui_bridge"
    private const val KEY_WEBVIEW_INTERNET = "adb_module_webview_internet"
    private const val KEY_WEBUI_DOWNLOAD = "adb_module_webui_download"
    private const val KEY_RECOMMAND = "adb_module_recommand"
    private const val KEY_AI_CHECKER = "adb_module_ai_checker"
    private const val KEY_TRUSTED = "adb_module_trusted"

    private fun prefs() = ShizukuSettings.getPreferences()

    // ---------------- 全局策略 ----------------

    fun getAccessMode(): AccessMode {
        val raw = prefs().getString(KEY_ACCESS_MODE, AccessMode.SAFE.value)
        return AccessMode.entries.firstOrNull { it.value == raw } ?: AccessMode.SAFE
    }

    fun setAccessMode(mode: AccessMode) {
        prefs().edit().putString(KEY_ACCESS_MODE, mode.value).apply()
    }

    /** Service 开关（只在自定义模式下有意义） */
    fun isServiceEnabled(): Boolean = prefs().getBoolean(KEY_SERVICE, false)

    fun setServiceEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_SERVICE, enabled).apply()

    /** 后台动作：service.sh 必须开着这个才允许跑 */
    fun isBackgroundEnabled(): Boolean = prefs().getBoolean(KEY_BACKGROUND, false)

    fun setBackgroundEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_BACKGROUND, enabled).apply()

    /** WebUI 里的 window.Shizuku 桥 */
    fun isWebUiBridgeEnabled(): Boolean = prefs().getBoolean(KEY_WEBUI_BRIDGE, false)

    fun setWebUiBridgeEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_WEBUI_BRIDGE, enabled).apply()

    /** WebView 联网（默认关：本地 WebUI 不需要外网） */
    fun isWebViewInternetEnabled(): Boolean = prefs().getBoolean(KEY_WEBVIEW_INTERNET, false)

    fun setWebViewInternetEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_WEBVIEW_INTERNET, enabled).apply()

    /** WebUI 内下载 */
    fun isWebUiDownloadEnabled(): Boolean = prefs().getBoolean(KEY_WEBUI_DOWNLOAD, false)

    fun setWebUiDownloadEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_WEBUI_DOWNLOAD, enabled).apply()

    /** ReCommand：命令执行前的确认弹窗 */
    fun isReCommandEnabled(): Boolean = prefs().getBoolean(KEY_RECOMMAND, true)

    fun setReCommandEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_RECOMMAND, enabled).apply()

    /** Gemini 检查器（隐藏项：设置里连点翻译贡献者 5 次才出现） */
    fun isAiCheckerEnabled(): Boolean = prefs().getBoolean(KEY_AI_CHECKER, false)

    fun setAiCheckerEnabled(enabled: Boolean) =
        prefs().edit().putBoolean(KEY_AI_CHECKER, enabled).apply()

    // ---------------- Full Trust ----------------

    fun trustedModules(): Set<String> =
        prefs().getStringSet(KEY_TRUSTED, emptySet())?.toSet() ?: emptySet()

    fun isModuleTrusted(id: String): Boolean = trustedModules().contains(id)

    fun setModuleTrusted(id: String, trusted: Boolean) {
        val set = trustedModules().toMutableSet()
        if (trusted) set.add(id) else set.remove(id)
        prefs().edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    // ---------------- 策略门 ----------------

    private fun trustedBypass(module: AdbModule): Boolean = isModuleTrusted(module.id)

    /** 手动动作：三档都允许（"安全模式 = 只允许手动动作"就是这个意思） */
    fun canRunAction(module: AdbModule): Boolean = true

    /** service.sh：完全访问放行；自定义模式看 Service 开关；安全模式不放行 */
    fun canRunService(module: AdbModule): Boolean {
        if (trustedBypass(module)) return true
        return when (getAccessMode()) {
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> isServiceEnabled()
            AccessMode.SAFE -> false
        }
    }

    /** 后台动作：service.sh 的额外前置条件 */
    fun canRunBackground(module: AdbModule): Boolean {
        if (trustedBypass(module)) return true
        return when (getAccessMode()) {
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> isBackgroundEnabled()
            AccessMode.SAFE -> false
        }
    }

    /** 打开本地 WebUI */
    fun canOpenWebUi(module: AdbModule): Boolean {
        if (trustedBypass(module)) return true
        return getAccessMode() != AccessMode.SAFE
    }

    /** WebUI 里的 shell 桥：模块必须声明 usesShellBridge=true，且策略放行 */
    fun canUseShellBridge(module: AdbModule): Boolean {
        if (trustedBypass(module)) return true
        if (!module.declaresShellBridge) return false
        return when (getAccessMode()) {
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> isWebUiBridgeEnabled()
            AccessMode.SAFE -> false
        }
    }

    fun canWebViewInternet(module: AdbModule): Boolean {
        if (trustedBypass(module)) return true
        return when (getAccessMode()) {
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> isWebViewInternetEnabled()
            AccessMode.SAFE -> false
        }
    }

    fun canWebUiDownload(module: AdbModule): Boolean {
        if (trustedBypass(module)) return true
        return when (getAccessMode()) {
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> isWebUiDownloadEnabled()
            AccessMode.SAFE -> false
        }
    }

    /** 执行命令前是否弹确认（ReCommand） */
    fun needsCommandReview(module: AdbModule): Boolean = !trustedBypass(module) && isReCommandEnabled()

    /** 首次初始化：把默认值写进偏好，避免 UI 显示的开关和实际取值不一致 */
    fun ensureDefaults(context: Context) {
        if (!prefs().contains(KEY_ACCESS_MODE)) {
            prefs().edit()
                .putString(KEY_ACCESS_MODE, AccessMode.SAFE.value)
                .putBoolean(KEY_SERVICE, false)
                .putBoolean(KEY_BACKGROUND, false)
                .putBoolean(KEY_WEBUI_BRIDGE, false)
                .putBoolean(KEY_WEBVIEW_INTERNET, false)
                .putBoolean(KEY_WEBUI_DOWNLOAD, false)
                .putBoolean(KEY_RECOMMAND, true)
                .apply()
        }
    }
}
