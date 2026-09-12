package moe.shizuku.manager.connector

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import moe.shizuku.manager.ShizukuSettings
import java.io.File

/**
 * Shizuku Connectors 的开关与命令组装。
 *
 * 为什么单独放在这里而不是复用 `ModuleSettings`：实验室功能的开关属于「本功能自己的状态」，
 * 和 ADB 模块策略没有关系；集中在一个 object 里，Provider 和 Compose 页面读的是同一份偏好，
 * 不会出现「页面显示开着、Provider 却返回空」这种错位。
 *
 * 偏好直接落在 [ShizukuSettings] 的 `settings` SharedPreferences 里，因此**天然会被
 * BackupRestoreUtil 一起备份**，不需要额外的备份通路。
 */
object ShizukuConnectors {

    private const val TAG = "ShizukuConnectors"

    /** Provider 的 authority 后缀，完整形式是 `<包名>.connector`（本项目即 com.churan.shizako.connector）。 */
    const val AUTHORITY_SUFFIX = "connector"

    /** query 返回的唯一列名，客户端按这个名字取命令。 */
    const val COLUMN_COMMAND = "command"

    private const val KEY_ENABLED = "lab_shizuku_connectors_enabled"
    private const val KEY_WARNING_ACCEPTED = "lab_shizuku_connectors_warning_accepted"

    /** 取偏好前先保证 ShizukuSettings 初始化（Provider 可能先于 Application 的逻辑被调用）。 */
    private fun prefs(context: Context): android.content.SharedPreferences {
        ShizukuSettings.initialize(context)
        return ShizukuSettings.getPreferences()
    }

    fun authority(context: Context): String = "${context.packageName}.$AUTHORITY_SUFFIX"

    fun uri(context: Context): String = "content://${authority(context)}"

    /** 默认关闭 —— 对外暴露「启动 server 的命令」这件事必须由用户显式打开。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 用户是否已经在实验室功能里点过「我了解风险」。没点过就不给开关打开。 */
    fun isWarningAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WARNING_ACCEPTED, false)

    fun setWarningAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_WARNING_ACCEPTED, accepted).apply()
    }

    /** 开关真正生效的条件：已开启 **且** 已接受安全警告（两个条件缺一不可）。 */
    fun isActive(context: Context): Boolean = isEnabled(context) && isWarningAccepted(context)

    /**
     * 组装「启动本地 Shizuku server」的完整 shell 命令。
     *
     * 命令来源（照抄，不新造）：`manager/src/main/java/moe/shizuku/manager/starter/Starter.kt`
     * ```
     * private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")
     * val userCommand = starterFile.absolutePath
     * val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"
     * ```
     * 也就是 `<nativeLibraryDir>/libshizuku.so --apk=<本 APK 的路径>`：
     * `libshizuku.so` 其实是打包进 jniLibs 的 starter 可执行文件，`--apk=` 告诉它从哪个 APK
     * 里取 server dex。`ServiceStartHelper.startRoot()`（root 身份）与 `ServiceStartHelper.startAdb()`
     * （无线调试身份）真正拉起服务时执行的都是这条命令，所以对外给 Activator 的必须是同一条。
     *
     * 这里用传进来的 [context] 重新拼一遍，而不是读 `Starter.internalCommand`：
     * `Starter` 依赖全局 `moe.shizuku.manager.application`，在 ContentProvider 进程/时机下读全局
     * 变量不够稳妥；用 context 拼出来的字符串与 `Starter.internalCommand` 完全等价。
     */
    fun buildStartCommand(context: Context): String {
        val starterFile = File(context.applicationInfo.nativeLibraryDir, "libshizuku.so")
        return "${starterFile.absolutePath} --apk=${context.applicationInfo.sourceDir}"
    }
}

/**
 * 对第三方「Activator」应用暴露的本地 ContentProvider：
 *
 * ```
 * content://com.churan.shizako.connector
 * ```
 *
 * query 返回**恰好一列** `command`、**恰好一行**，内容就是启动本地 Shizuku server 的完整命令；
 * 未开启时返回**空结果集**（0 行），不抛异常 —— 客户端拿到的永远是一个合法 Cursor，
 * 不需要为「null 还是空」写两套分支。
 *
 * 安全性：默认关闭，且必须在实验室功能页里接受安全警告后才返回命令。
 * 拿到命令就能以特权身份拉起 server，所以这个口子不能默认开。
 *
 * 注意：需要在 AndroidManifest.xml 里注册（本项目不改 manifest，片段见交付说明）：
 * ```xml
 * <provider
 *     android:name=".connector.ShizukuConnectorProvider"
 *     android:authorities="${applicationId}.connector"
 *     android:directBootAware="true"
 *     android:enabled="true"
 *     android:exported="true" />
 * ```
 * `directBootAware` 与 [ShizukuSettings] 用 device-protected storage 存偏好一致，
 * 开机后未解锁也能读到开关状态。
 */
class ShizukuConnectorProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // ShizukuSettings 存的是 device-protected storage，这里初始化好，
        // 后面 query 里再调是空操作（initialize 内部只在 null 时创建）。
        context?.let { ShizukuSettings.initialize(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return emptyCursor()

        // 只认自己的 authority：别的 authority（比如 ShizukuProvider 那条）走到这里就返回空，
        // 免得同一个进程里被复用出意料之外的结果。
        if (uri.authority != ShizukuConnectors.authority(context)) {
            return emptyCursor()
        }

        if (!ShizukuConnectors.isActive(context)) {
            Log.i(TAG, "query: connectors disabled, returning empty result")
            return emptyCursor()
        }

        return MatrixCursor(arrayOf(ShizukuConnectors.COLUMN_COMMAND)).apply {
            addRow(arrayOf(ShizukuConnectors.buildStartCommand(context)))
        }
    }

    /** 关闭时也要返回合法的空 Cursor（不是 null）：有些客户端会对 null 直接 NPE。 */
    private fun emptyCursor(): Cursor = MatrixCursor(arrayOf(ShizukuConnectors.COLUMN_COMMAND))

    /** 只读接口，其余方法全部不支持。 */
    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "ShizukuConnectors"
    }
}
