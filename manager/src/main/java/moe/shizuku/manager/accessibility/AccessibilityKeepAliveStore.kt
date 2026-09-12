package moe.shizuku.manager.accessibility

import moe.shizuku.manager.ShizukuSettings

/**
 * 无障碍常驻（固定）列表的持久化。
 *
 * 用应用自己的 SharedPreferences 存以冒号分隔的服务 ID
 * （例如 "pkg/cls:pkg2/cls2:"），格式与 Android 的
 * [android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 保持一致。
 *
 * 本项目没有 Hilt/Koin，直接复用全局的 [ShizukuSettings] 偏好文件 ——
 * 守护进程、开机广播、UI 三处都从这里取同一份状态，不需要注入。
 */
object AccessibilityKeepAliveStore {

    private const val KEY_KEEP_ALIVE_LIST = "accessibility_keep_alive_list"
    private const val KEY_KEEP_ALIVE_ENABLED = "accessibility_keep_alive_enabled"
    private const val KEY_AUTO_BOOT = "accessibility_keep_alive_auto_boot"

    /** 总开关：常驻守护进程是否应该运行。 */
    fun isKeepAliveEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_KEEP_ALIVE_ENABLED, false)
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply()
    }

    /** 是否在设备开机后自动拉起守护进程。 */
    fun isAutoBootEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_AUTO_BOOT, true)
    }

    fun setAutoBootEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_AUTO_BOOT, enabled).apply()
    }

    /** 当前已固定（常驻）的服务 ID 集合。 */
    fun getKeepAliveIds(): Set<String> {
        val raw = ShizukuSettings.getPreferences().getString(KEY_KEEP_ALIVE_LIST, "") ?: ""
        return parseIds(raw)
    }

    fun isPinned(serviceId: String): Boolean = getKeepAliveIds().contains(serviceId)

    fun addPinned(serviceId: String): Set<String> {
        val ids = getKeepAliveIds().toMutableSet()
        ids.add(serviceId)
        writeIds(ids)
        return ids
    }

    fun removePinned(serviceId: String): Set<String> {
        val ids = getKeepAliveIds().toMutableSet()
        ids.remove(serviceId)
        writeIds(ids)
        return ids
    }

    private fun writeIds(ids: Set<String>) {
        // 末尾补一个冒号：和系统那串"以冒号分隔"的格式对齐，解析时靠 filter 兜住空段
        val raw = ids.joinToString(":") { it } + if (ids.isNotEmpty()) ":" else ""
        ShizukuSettings.getPreferences().edit().putString(KEY_KEEP_ALIVE_LIST, raw).apply()
    }

    private fun parseIds(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(":")
            .filter { it.isNotBlank() }
            .toSet()
    }
}
