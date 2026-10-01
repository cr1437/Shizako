package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings

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

    /**
     * 一键设置 Device Owner 的**设备内**命令。
     *
     * 注意这里**不能**带 `adb shell ` 前缀：这条命令由 [moe.shizuku.manager.activation.ActivationRunner]
     * 用设备上的 `sh -c` 以服务身份直接执行，而设备上并没有 `adb` 可执行文件 ——
     * 带上前缀必然 `adb: not found` 失败（这正是「一键激活 Dhizuku 从来没成功过」的原因）。
     * 要给用户在电脑上跑的版本用 [pcSetDeviceOwnerCommand]。
     */
    val setDeviceOwnerCommand: String
        get() = "dpm set-device-owner ${BuildConfig.APPLICATION_ID}/.dhizuku.DhizukuAdminReceiver"

    /** 给用户在**电脑**上执行的版本（「查看命令」对话框、复制/发送用） */
    val pcSetDeviceOwnerCommand: String
        get() = "adb shell $setDeviceOwnerCommand"

    /** 清除 Device Owner 状态的设备内命令（降级/卸载前用） */
    val clearDeviceOwnerCommand: String
        get() = "dpm remove-active-admin ${BuildConfig.APPLICATION_ID}/.dhizuku.DhizukuAdminReceiver"

    /** 清除 Device Owner 状态的电脑端命令 */
    val pcClearDeviceOwnerCommand: String
        get() = "adb shell $clearDeviceOwnerCommand"

    /** Shizako 是否为当前设备的 Device Owner */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(BuildConfig.APPLICATION_ID)
    }

    /**
     * Dhizuku 模式总开关（设置页「Dhizuku 模式」那个开关）。
     *
     * 这是**软开关**：关掉后不再对外提供 Dhizuku 特权、相关入口不再显示，但
     * **不会撤销 Device Owner 身份** —— Device Owner 只能由
     * `adb shell dpm remove-active-admin <pkg>/.dhizuku.DhizukuAdminReceiver` 撤销，
     * App 即使身为 Device Owner 也撤销不了自己，部分 ROM 还会直接拒绝。
     */
    fun isModeEnabled(): Boolean = ShizukuSettings.isDhizukuEnabled()

    /**
     * Dhizuku 特权是否**真正可用**：开关打开 **且** 确实是 Device Owner。
     *
     * UI 判定一律用这个，不要再用裸的 [isDeviceOwner] —— 否则开关关掉后
     * 首页显示「未激活」而激活页显示「已激活」，状态会打架。
     */
    fun isActive(context: Context): Boolean = isModeEnabled() && isDeviceOwner(context)

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
