package moe.shizuku.manager.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 无障碍服务常驻的核心控制器。
 *
 * 直接通过 ContentResolver 读写 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]。
 * 本应用持有 WRITE_SECURE_SETTINGS（由 Shizuku/ADB 授权），所以不需要走 shell 命令 ——
 * 同步、原子，且和系统自己的写入路径完全一致。
 *
 * 该设置项是一串以冒号分隔的扁平化 ComponentName：
 *   "com.example/com.example.MyService:com.other/com.other.Svc"
 *
 * [ComponentName.flattenToString] 产出 "pkg/pkg.ServiceName"，正是系统存储的格式；
 * [ComponentName.unflattenFromString] 反向解析。
 */
object AccessibilityManager {

    private const val TAG = "AccessibilityManager"
    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    /**
     * 写入前置位、由守护进程的 ContentObserver 在收到那一次回调时清除。
     * 这样守护进程不会把自己刚写入的结果又"恢复"一遍（否则自我循环）。
     * 对应参考实现里的 isSelfModification。
     */
    @Volatile
    private var selfWritePending = false

    fun isSelfWrite(): Boolean = selfWritePending

    fun clearSelfWrite() {
        selfWritePending = false
    }

    // -----------------------------------------------------------------
    // 读
    // -----------------------------------------------------------------

    /**
     * 返回已启用的服务 ComponentName 集合。
     *
     * 用应用自己的 ContentResolver 读系统设置字符串（不是查 PackageManager），
     * 所以不受包可见性（package visibility）过滤影响。
     */
    fun getEnabledServices(context: Context): Set<ComponentName> {
        val raw = Settings.Secure.getString(context.contentResolver, KEY) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .toSet()
    }

    /** [serviceId]（flattenToString 格式）当前是否已启用。 */
    fun isServiceEnabled(context: Context, serviceId: String): Boolean {
        val target = ComponentName.unflattenFromString(serviceId) ?: return false
        return getEnabledServices(context).contains(target)
    }

    // -----------------------------------------------------------------
    // 写（同步、原子）
    // -----------------------------------------------------------------

    /**
     * 启用 [serviceId]（flattenToString 格式）。
     * 原子操作：读当前列表 → 加入 → 写回。
     */
    fun enableService(context: Context, serviceId: String): Boolean {
        val cn = ComponentName.unflattenFromString(serviceId) ?: run {
            Log.w(TAG, "Cannot parse service id: $serviceId")
            return false
        }
        val enabled = getEnabledServices(context).toMutableSet()
        if (!enabled.add(cn)) return true // 本来就启用着
        return writeServices(context, enabled)
    }

    /**
     * 禁用 [serviceId]（flattenToString 格式）。
     * 原子操作：读当前列表 → 移除 → 写回。
     */
    fun disableService(context: Context, serviceId: String): Boolean {
        val cn = ComponentName.unflattenFromString(serviceId) ?: run {
            Log.w(TAG, "Cannot parse service id: $serviceId")
            return false
        }
        val enabled = getEnabledServices(context).toMutableSet()
        if (!enabled.remove(cn)) return true // 本来就禁用着
        return writeServices(context, enabled)
    }

    /**
     * 重新启用所有「已固定但当前缺失」的服务。
     * 返回被恢复的列表（不需要恢复或写入失败时返回空列表）。
     */
    fun restorePinnedServices(context: Context): List<String> {
        val pinnedRaw = AccessibilityKeepAliveStore.getKeepAliveIds()
        if (pinnedRaw.isEmpty()) return emptyList()

        val pinned = pinnedRaw.mapNotNull { ComponentName.unflattenFromString(it) }
        if (pinned.isEmpty()) return emptyList()

        val enabled = getEnabledServices(context).toMutableSet()
        val missing = pinned.filter { it !in enabled }
        if (missing.isEmpty()) return emptyList()

        enabled.addAll(missing)
        if (writeServices(context, enabled)) {
            Log.d(TAG, "Restored pinned accessibility services: $missing")
            return missing.map { it.flattenToString() }
        }
        Log.w(TAG, "Failed to restore pinned accessibility services: $missing")
        return emptyList()
    }

    // -----------------------------------------------------------------
    // 底层写入
    // -----------------------------------------------------------------

    /**
     * 把完整的启用列表写进 Settings.Secure。
     * 同时置位 [selfWritePending]，让守护进程的 ContentObserver 忽略这次自己造成的变化。
     */
    private fun writeServices(context: Context, services: Set<ComponentName>): Boolean {
        val value = services.joinToString(":") { it.flattenToString() }
        selfWritePending = true
        val ok = try {
            Settings.Secure.putString(context.contentResolver, KEY, value)
        } catch (e: Exception) {
            // 没有 WRITE_SECURE_SETTINGS 时系统会抛 SecurityException —— 当作写入失败上报给 UI
            Log.w(TAG, "Failed to write enabled accessibility services: ${e.message}")
            selfWritePending = false
            false
        }
        if (ok) {
            Log.d(TAG, "Wrote enabled accessibility services (${services.size})")
        } else {
            selfWritePending = false
        }
        return ok
    }
}
