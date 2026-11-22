package moe.shizuku.manager.storage

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import moe.shizuku.server.FileEntry
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IStorageProxy
import rikka.shizuku.Shizuku

/**
 * 管理器侧的 IStorageProxy 客户端（规格 F1）。
 *
 * 管理器 uid 在服务端被直接放行（它就是授权 UI），所以这里不需要「存储代理」能力位；
 * 能力位只约束第三方应用（规格 F5）。
 *
 * 所有调用都先探一次 binder：服务没启动时 `Shizuku.getBinder()` 会抛
 * IllegalStateException，直接崩界面不如返回一个能展示的 NO_SERVICE 错误。
 */
object StorageProxyClient {

    private const val TAG = "ShizakoStorage"

    /** /sdcard 的三种等价写法在设备上实际都存在，统一用 FUSE 视图这一条。 */
    const val EXTERNAL_DATA_ROOT = "/sdcard/Android/data"
    const val EXTERNAL_OBB_ROOT = "/sdcard/Android/obb"

    fun externalDataDir(packageName: String): String = "$EXTERNAL_DATA_ROOT/$packageName"

    fun externalObbDir(packageName: String): String = "$EXTERNAL_OBB_ROOT/$packageName"

    fun service(): IShizukuService? = try {
        val binder = Shizuku.getBinder()
        if (binder == null) null else IShizukuService.Stub.asInterface(binder)
    } catch (e: Throwable) {
        Log.w(TAG, "service not available: ${e.message}")
        null
    }

    fun available(): Boolean = proxy() != null

    fun proxy(): IStorageProxy? {
        val service = service() ?: return null
        return try {
            service.storageProxy
        } catch (e: Throwable) {
            Log.w(TAG, "storage proxy unavailable: ${e.message}")
            null
        }
    }

    private fun requireProxy(): IStorageProxy =
        proxy() ?: throw IllegalStateException("storage:no_service:service is not running")

    fun info(): String? = try {
        requireProxy().info()
    } catch (e: Throwable) {
        Log.w(TAG, "info failed: ${e.message}")
        null
    }

    fun lastLayer(): String? = try {
        requireProxy().lastLayer()
    } catch (e: Throwable) {
        null
    }

    fun listDirectory(path: String): List<FileEntry> =
        requireProxy().listDirectory(path).orEmpty()

    fun openFile(path: String, mode: Int = ParcelFileDescriptor.MODE_READ_ONLY): ParcelFileDescriptor =
        requireProxy().openFile(path, mode)

    /** 目录不存在时抛 IllegalStateException（`storage:not_found:…`）。 */
    fun tarDirectory(path: String): ParcelFileDescriptor = requireProxy().tarDirectory(path)

    fun restoreTarDirectory(path: String, tar: ParcelFileDescriptor) =
        requireProxy().restoreTarDirectory(path, tar)

    fun copy(source: String, dest: String) = requireProxy().copy(source, dest)

    fun delete(path: String) = requireProxy().delete(path)

    fun mkdir(path: String) = requireProxy().mkdir(path)

    fun exists(path: String): Boolean = requireProxy().exists(path)

    /**
     * 把一次调用的异常整理成人能看的失败原因（供队列的 reason 字段使用）。
     * 错误码由 [StorageError.parse] 解析，文案在 UI 层本地化。
     */
    fun describeFailure(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val code = StorageError.parse(message)
        return "${code.name}: ${trimForUi(message)}"
    }

    private fun trimForUi(message: String): String {
        val index = message.lastIndexOf("storage:")
        val text = if (index >= 0) message.substring(index) else message
        return if (text.length > 160) text.take(160) else text
    }

    /** 远程调用失败时的统一包装（RemoteException 不在 AIDL 方法签名里也能出现）。 */
    fun wrap(op: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            throw IllegalStateException("storage:io:$op: ${e.message}", e)
        }
    }
}
