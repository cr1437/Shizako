package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.utils.Logger.LOGGER
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Dhizuku 协议服务端：让 Dhizuku-API 应用把 Shizako 当作 Device Owner 特权提供者。
 *
 * 客户端（Dhizuku-API）通过 ContentResolver.call("client") 拿到 [DhizukuBinder]，
 * 之后所有特权操作（remoteTransact / binderWrapper）都在 Shizako 进程内转发，
 * 系统服务看到的是 Shizako 的 uid —— Device Owner 特权由此生效。
 *
 * 协议实现为手写 onTransact，不依赖 Dhizuku-API 库（其 GPL 许可与
 * Shizako 的 Apache-2.0 不兼容）。
 */
class DhizukuProvider : ContentProvider() {

    private fun isDeviceOwnerActive(): Boolean {
        val context = context ?: return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return dpm.isDeviceOwnerApp(BuildConfig.APPLICATION_ID)
    }

    /** Shizaku 授权记录（server 在线时）∪ Dhizuku 本地白名单 */
    private fun isCallerAuthorized(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) return true
        val packageName = context?.packageManager?.getPackagesForUid(callingUid)?.firstOrNull()
            ?: return false
        val viaShizaku = try {
            AuthorizationManager.granted(packageName, callingUid)
        } catch (t: Throwable) {
            // server 未运行时查授权记录会失败，落到本地白名单
            LOGGER.w("Dhizuku: Shizaku grant check failed: ${t.message}")
            false
        }
        return viaShizaku || DhizukuSettings.isGranted(callingUid)
    }

    private inner class DhizukuBinder : Binder() {

        override fun getInterfaceDescriptor(): String = DhizukuConstants.BINDER_DESCRIPTOR

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == Binder.FIRST_CALL_TRANSACTION + 10) {
                // TRANSACT_CODE_REMOTE_BINDER：替客户端转发 transact。
                // 目标 binder 由客户端指定，若不做身份校验，任意应用都能借
                // Shizako 的 Device Owner 身份操作系统服务（confused-deputy）。
                data.enforceInterface(DhizukuConstants.REMOTE_BINDER_DESCRIPTOR)
                if (!isDeviceOwnerActive() || !isCallerAuthorized()) return false
                val target = data.readStrongBinder() ?: return false
                val targetCode = data.readInt()
                val targetFlags = data.readInt()
                return target.transact(targetCode, data, reply, targetFlags)
            }

            data.enforceInterface(DhizukuConstants.BINDER_DESCRIPTOR)
            when (code) {
                // int getVersionCode()
                Binder.FIRST_CALL_TRANSACTION + 0 -> {
                    reply?.writeNoException()
                    reply?.writeInt(DhizukuConstants.VERSION_CODE)
                    return true
                }

                // String getVersionName()
                Binder.FIRST_CALL_TRANSACTION + 1 -> {
                    reply?.writeNoException()
                    reply?.writeString(DhizukuConstants.VERSION_NAME)
                    return true
                }

                // boolean isPermissionGranted()
                Binder.FIRST_CALL_TRANSACTION + 2 -> {
                    reply?.writeNoException()
                    reply?.writeInt(if (isCallerAuthorized()) 1 else 0)
                    return true
                }

                // IDhizukuRemoteProcess remoteProcess(String[] cmd, String[] env, String dir)
                Binder.FIRST_CALL_TRANSACTION + 11 -> {
                    reply?.writeNoException()
                    val cmd = data.createStringArray()
                    val env = data.createStringArray()
                    val dir = data.readString()
                    val binder = if (isDeviceOwnerActive() && isCallerAuthorized()) {
                        startRemoteProcess(cmd, env, dir)
                    } else null
                    reply?.writeStrongBinder(binder)
                    return true
                }

                // void bindUserService(connection, bundle) / unbindUserService(bundle)
                // / unbindUserServiceByConnection(connection, bundle)
                Binder.FIRST_CALL_TRANSACTION + 12,
                Binder.FIRST_CALL_TRANSACTION + 13,
                Binder.FIRST_CALL_TRANSACTION + 14 -> {
                    reply?.writeNoException()
                    return true
                }

                // String[] getDelegatedScopes(String packageName)
                Binder.FIRST_CALL_TRANSACTION + 15 -> {
                    reply?.writeNoException()
                    val packageName = data.readString()
                    val scopes = if (packageName != null && isDeviceOwnerActive() && isCallerAuthorized()) {
                        dpm()?.getDelegatedScopes(DhizukuSettings.adminComponent, packageName)
                            ?.toTypedArray()
                    } else null
                    reply?.writeStringArray(scopes)
                    return true
                }

                // void setDelegatedScopes(String packageName, String[] scopes)
                Binder.FIRST_CALL_TRANSACTION + 16 -> {
                    reply?.writeNoException()
                    val packageName = data.readString()
                    val scopes = data.createStringArray()
                    if (packageName != null && scopes != null && isDeviceOwnerActive() && isCallerAuthorized()) {
                        dpm()?.setDelegatedScopes(
                            DhizukuSettings.adminComponent,
                            packageName,
                            scopes.toList()
                        )
                    }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun dpm(): DevicePolicyManager? =
        context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    /**
     * 替已授权的 Dhizuku 客户端启动进程。进程跑在 Shizako 的 uid 下，
     * 只有已授权应用可以触发（否则任意应用都能借 Shizako 身份跑代码）。
     */
    private fun startRemoteProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IBinder? {
        if (cmd.isNullOrEmpty()) return null
        return try {
            val builder = ProcessBuilder(*cmd)
            env?.forEach { pair ->
                val i = pair.indexOf('=')
                if (i > 0) {
                    builder.environment()[pair.substring(0, i)] = pair.substring(i + 1)
                }
            }
            if (!dir.isNullOrEmpty()) builder.directory(File(dir))
            RemoteProcessBinder(builder.start())
        } catch (t: Throwable) {
            LOGGER.w("Dhizuku: remote process failed: ${t.message}")
            null
        }
    }

    /**
     * IDhizukuRemoteProcess 协议实现（code = FIRST_CALL_TRANSACTION + 0..7）：
     * - +0 getOutputStream() → 客户端往进程 stdin 写的 fd
     * - +1 getInputStream()  → 读进程 stdout 的 fd
     * - +2 getErrorStream()  → 读进程 stderr 的 fd
     * - +3 exitValue() / +4 destroy() / +5 alive() / +6 waitFor()
     * - +7 waitForTimeout(long, String)
     *
     * 进程 IO 通过 ParcelFileDescriptor 管道桥接：桥接线程（daemon）把
     * 管道一端的数据泵向进程流，进程退出后线程随流关闭自然结束。
     */
    private class RemoteProcessBinder(private val process: Process) : Binder() {

        override fun getInterfaceDescriptor(): String = "com.rosan.dhizuku.aidl.IDhizukuRemoteProcess"

        // createPipe() 返回 [read, write]
        private val stdinPipe = ParcelFileDescriptor.createPipe()
        private val stdoutPipe = ParcelFileDescriptor.createPipe()
        private val stderrPipe = ParcelFileDescriptor.createPipe()

        init {
            // 客户端写入 stdinPipe[1] → 桥接线程读出 → 进程 stdin
            pump(FileInputStream(stdinPipe[0].fileDescriptor), process.outputStream)
            // 进程 stdout → 桥接线程 → stdoutPipe[1]，客户端从 stdoutPipe[0] 读
            pump(process.inputStream, FileOutputStream(stdoutPipe[1].fileDescriptor))
            pump(process.errorStream, FileOutputStream(stderrPipe[1].fileDescriptor))
        }

        private fun pump(input: InputStream, output: OutputStream) {
            val thread = Thread {
                try {
                    input.copyTo(output)
                } catch (_: Throwable) {
                } finally {
                    runCatching { input.close() }
                    runCatching { output.close() }
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        /** dup 管道端写入 reply，随后关闭发送方副本（否则每次调用泄漏一个 fd）。 */
        private fun sendDup(reply: Parcel?, source: ParcelFileDescriptor) {
            if (reply == null) return
            runCatching {
                val dup = ParcelFileDescriptor.dup(source.fileDescriptor)
                dup.use { reply.writeParcelable(it, 0) }
            }
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(getInterfaceDescriptor())
            when (code) {
                Binder.FIRST_CALL_TRANSACTION + 0 -> {
                    reply?.writeNoException()
                    sendDup(reply, stdinPipe[1])
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 1 -> {
                    reply?.writeNoException()
                    sendDup(reply, stdoutPipe[0])
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 2 -> {
                    reply?.writeNoException()
                    sendDup(reply, stderrPipe[0])
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 3 -> {
                    reply?.writeNoException()
                    reply?.writeInt(
                        runCatching { process.exitValue() }.getOrDefault(-1)
                    )
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 4 -> {
                    reply?.writeNoException()
                    process.destroyForcibly()
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 5 -> {
                    reply?.writeNoException()
                    reply?.writeInt(if (process.isAlive) 1 else 0)
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 6 -> {
                    reply?.writeNoException()
                    reply?.writeInt(runCatching { process.waitFor() }.getOrDefault(-1))
                    return true
                }

                Binder.FIRST_CALL_TRANSACTION + 7 -> {
                    reply?.writeNoException()
                    val timeout = data.readLong()
                    val unitName = data.readString()
                    val unit = runCatching { TimeUnit.valueOf(unitName?.uppercase() ?: "SECONDS") }
                        .getOrDefault(TimeUnit.SECONDS)
                    reply?.writeInt(
                        if (runCatching { process.waitFor(timeout, unit) }.getOrDefault(false)) 1 else 0
                    )
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onCreate(): Boolean {
        DhizukuSettings.initialize(requireNotNull(context))
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != DhizukuConstants.PROVIDER_METHOD_CLIENT) return null
        val bundle = Bundle()
        bundle.putBinder(DhizukuConstants.PARAM_DHIZUKU_BINDER, DhizukuBinder())
        return bundle
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
