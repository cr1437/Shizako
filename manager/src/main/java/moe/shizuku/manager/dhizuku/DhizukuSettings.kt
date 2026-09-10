package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import moe.shizuku.manager.BuildConfig

/**
 * Dhizuku（Device Owner）模式的本地设置：
 * 激活状态检测 + 本地授权白名单（不依赖 shell server 的授权记录）。
 */
object DhizukuSettings {

    private const val NAME = "dhizuku"
    private const val KEY_GRANTED_UIDS = "granted_uids"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences == null) {
            synchronized(this) {
                if (preferences == null) {
                    preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun prefs(): SharedPreferences = preferences
        ?: throw IllegalStateException("DhizukuSettings not initialized")

    /** Device Admin 组件，作为 set-device-owner 的目标 */
    val adminComponent: ComponentName
        get() = ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.dhizuku.DhizukuAdminReceiver")

    /** 一键设置 Device Owner 的 ADB 命令 */
    val setDeviceOwnerCommand: String
        get() = "adb shell dpm set-device-owner ${BuildConfig.APPLICATION_ID}/.dhizuku.DhizukuAdminReceiver"

    /** 清除 Device Owner 状态（降级/卸载前用） */
    val clearDeviceOwnerCommand: String
        get() = "adb shell dpm remove-active-admin ${BuildConfig.APPLICATION_ID}/.dhizuku.DhizukuAdminReceiver"

    /** Shizako 是否为当前设备的 Device Owner */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(BuildConfig.APPLICATION_ID)
    }

    /** 本地授权白名单（uid 维度） */
    fun isGranted(uid: Int): Boolean =
        prefs().getStringSet(KEY_GRANTED_UIDS, emptySet())?.contains(uid.toString()) == true

    fun grant(uid: Int) {
        val set = HashSet(prefs().getStringSet(KEY_GRANTED_UIDS, emptySet()) ?: emptySet())
        set.add(uid.toString())
        prefs().edit().putStringSet(KEY_GRANTED_UIDS, set).commit()
    }

    fun revoke(uid: Int) {
        val set = HashSet(prefs().getStringSet(KEY_GRANTED_UIDS, emptySet()) ?: emptySet())
        set.remove(uid.toString())
        prefs().edit().putStringSet(KEY_GRANTED_UIDS, set).commit()
    }

    fun grantedUids(): List<Int> =
        (prefs().getStringSet(KEY_GRANTED_UIDS, emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }
            .sorted()
}
