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

    /**
     * Device Admin 组件，作为 `dpm set-device-owner` / `setDelegatedScopes` 的目标。
     *
     * 必须用**真实类名**：applicationId 是 `com.churan.shizako`，Java 包名却是
     * `moe.shizuku.manager`（manifest 里的 `.dhizuku.DhizukuAdminReceiver` 是相对
     * namespace 解析的）。以前这里拼成 `com.churan.shizako.dhizuku.DhizukuAdminReceiver`，
     * 于是 `dpm set-device-owner` 报 "Unknown admin"、`setDelegatedScopes` 抛
     * SecurityException（授权应用拿不到设备所有者权限），用户只能用完整类名手动激活
     * —— 见 issue #13。
     */
    val adminComponent: ComponentName
        get() = ComponentName(BuildConfig.APPLICATION_ID, DhizukuAdminReceiver::class.java.name)

    /** `dpm` 命令里的 `包名/全限定类名` */
    private val adminComponentString: String
        get() = "${adminComponent.packageName}/${adminComponent.className}"

    /**
     * 一键设置 Device Owner 的**设备内**命令。
     *
     * 注意这里**不能**带 `adb shell ` 前缀：这条命令由 [moe.shizuku.manager.activation.ActivationRunner]
     * 用设备上的 `sh -c` 以服务身份直接执行，而设备上并没有 `adb` 可执行文件 ——
     * 带上前缀必然 `adb: not found` 失败（这正是「一键激活 Dhizuku 从来没成功过」的原因）。
     * 要给用户在电脑上跑的版本用 [pcSetDeviceOwnerCommand]。
     */
    val setDeviceOwnerCommand: String
        get() = "dpm set-device-owner $adminComponentString"

    /** 给用户在**电脑**上执行的版本（「查看命令」对话框、复制/发送用） */
    val pcSetDeviceOwnerCommand: String
        get() = "adb shell $setDeviceOwnerCommand"

    /**
     * 清除 Device Owner 状态的设备内命令（降级/卸载前用）。
     *
     * 注意：`dpm remove-active-admin` 只对 **testOnly / debuggable** 的 admin 生效，
     * 正式包执行会抛 `SecurityException: Attempt to remove non-test admin`
     * （issue #13 里那条堆栈就是它）。正式包请走 [clearDeviceOwner]。
     */
    val clearDeviceOwnerCommand: String
        get() = "dpm remove-active-admin $adminComponentString"

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
     * **不会撤销 Device Owner 身份**。撤销走 [clearDeviceOwner]：只有 Device Owner
     * 自己能清掉自己；`adb shell dpm remove-active-admin` 对非 testOnly 的 admin
     * 一律抛 SecurityException，靠它救不回来（issue #12）。
     */
    fun isModeEnabled(): Boolean = ShizukuSettings.isDhizukuEnabled()

    /**
     * 撤销 Device Owner 身份（issue #12：成为 Device Owner 后无路可退）。
     *
     * 走 [DevicePolicyManager.clearDeviceOwnerApp]（API 21+；虽被标记 deprecated，
     * 但它是 App 主动退出 Device Owner 的**唯一**途径 —— "Only the device owner
     * will be able to clear itself"）。别指望 adb 兜底：`dpm remove-active-admin`
     * 对非 testOnly 的 admin 一律抛 SecurityException（issue #13 的堆栈即此）。
     *
     * 撤销是 best-effort：Android 只清掉部分策略，其余设置可能残留；最干净的仍是
     * 恢复出厂。拿回控制权后可以直接卸载 Shizako。
     *
     * @return null 表示已经不是 Device Owner；否则返回错误信息，交给 UI 提示
     */
    @Suppress("DEPRECATION")
    fun clearDeviceOwner(context: Context): String? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return "DevicePolicyManager unavailable"
        if (!dpm.isDeviceOwnerApp(BuildConfig.APPLICATION_ID)) return null
        return try {
            dpm.clearDeviceOwnerApp(BuildConfig.APPLICATION_ID)
            // 软开关与本地白名单一起复位，否则 UI 还显示「已激活」、授权列表还留着旧 uid
            ShizukuSettings.setDhizukuEnabled(false)
            runCatching { prefs().edit().remove(KEY_GRANTED_UIDS).commit() }
            if (dpm.isDeviceOwnerApp(BuildConfig.APPLICATION_ID)) "still device owner" else null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

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
