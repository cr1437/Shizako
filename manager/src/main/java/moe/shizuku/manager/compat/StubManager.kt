package moe.shizuku.manager.compat

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 「兼容性 Stub」安装 / 卸载。
 *
 * 背景：不少第三方应用把 Shizuku 的包名 `moe.shizuku.privileged.api` 写死在代码里，
 * 装不上（或者查不到）这个包名就判定"没有 Shizuku"，于是拒绝工作。本类的做法是：
 * 把一个只有清单、没有实现的占位 APK 装成那个包名，让这类应用"看得见"。
 * 真正的 binder / 权限仍然由 Shizako 本体（[moe.shizuku.manager.ShizukuManagerProvider]）提供。
 *
 * 三条安装通道依次尝试（服务器 → root → ADB），任意一条成功即返回；全部失败才报最后一次的错误：
 * - Shizuku server：把 APK 字节经 `newProcess` 的 stdin 管道喂给 `cat > /data/local/tmp/...`，
 *   再 `pm install`。这是最干净的一条——不需要 App 自己有高权限，
 *   而且 shell uid 读得到本体私有目录里的 APK。
 * - root：libsu `Shell.cmd`，直接 `pm install` 私有目录里的 APK 路径。
 * - ADB：复用 `AdbClient`（和 WirelessAdb 启动服务同一条链路）。
 *   ADB shell 读不到 /data/ 下面本体的私有文件，只能走外部存储 /sdcard/Android/data/... 再 `cp` 到 /data/local/tmp。
 *
 * 每次安装 / 卸载都轮询 [isInstalled] 确认结果：`pm install` 返回 0 也可能被 PackageManager
 * 悄悄拒绝（签名冲突、用户空间限制），只看退出码会误报成功。
 */
object StubManager {

    /** 写死原版 Shizuku 包名的应用认的就是它 */
    const val STUB_PACKAGE = "moe.shizuku.privileged.api"

    /** 由 manager 的 copyStubApk 任务从 :stub 模块复制进来 */
    private const val ASSET_PATH = "shizako-stub.apk"

    /** 中间路径：pm 在 /data/local/tmp 下有读权限 */
    private const val REMOTE_TMP_PATH = "/data/local/tmp/shizako-stub.apk"

    private const val CHANNEL_NONE = "none"
    private const val CHANNEL_SERVER = "Shizako server"
    private const val CHANNEL_ROOT = "root"
    private const val CHANNEL_ADB = "ADB"

    /** 远端 sh 脚本：整个脚本只写一次，sh 只 fork 一次，安装能吃满 CPU */
    private const val PM_INSTALL_SCRIPT = "cat > $REMOTE_TMP_PATH && pm install -r -d -t $REMOTE_TMP_PATH; rm -f $REMOTE_TMP_PATH"

    private const val PM_UNINSTALL = "pm uninstall $STUB_PACKAGE"

    /** pm install 完成后 PackageManager 通常秒级生效，3 秒足够；卡住就往下一条通道走 */
    private const val POLL_TIMEOUT_MS = 3_000L

    private const val TAG = "StubManager"

    data class Result(val ok: Boolean, val channel: String, val error: String? = null)

    /**
     * 是否已经装过占位包。
     *
     * 用 [PackageManager.getPackageInfo] 而不是 `pm list packages`：本体带 QUERY_ALL_PACKAGES
     * （见 manager 的 AndroidManifest），可见性没问题，而且不用起 shell，UI 里可以随便调。
     */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(STUB_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun install(context: Context): Result = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext Result(true, CHANNEL_NONE)

        val apkBytes = readAsset(context) ?: return@withContext Result(
            false, CHANNEL_NONE, "assets 里的 $ASSET_PATH 读不出来（可能是构建时没跑 copyStubApk）"
        )

        // 私有目录里的明文 APK：server / root 两条通道直接用它，省一次解压
        val privateApk = writeToPrivateStorage(context, apkBytes)

        var last: Result? = null

        val server = runViaServer(apkBytes)
        if (server.ok && pollInstalled(context, wantInstalled = true)) {
            return@withContext server
        }
        if (server.error != null) last = server

        val root = runViaRoot(privateApk?.absolutePath)
        if (root.ok && pollInstalled(context, wantInstalled = true)) {
            return@withContext root
        }
        if (root.error != null) last = root

        val adb = runViaAdb(context, apkBytes)
        if (adb.ok && pollInstalled(context, wantInstalled = true)) {
            return@withContext adb
        }
        if (adb.error != null) last = adb

        last ?: Result(false, CHANNEL_NONE, "命令执行了但系统里查不到安装结果，请稍后重试")
    }

    suspend fun uninstall(context: Context): Result = withContext(Dispatchers.IO) {
        if (!isInstalled(context)) return@withContext Result(true, CHANNEL_NONE)

        var last: Result? = null

        val server = runViaServer(null, PM_UNINSTALL)
        if (server.ok && pollInstalled(context, wantInstalled = false)) return@withContext server
        if (server.error != null) last = server

        val root = runViaRoot(null, PM_UNINSTALL)
        if (root.ok && pollInstalled(context, wantInstalled = false)) return@withContext root
        if (root.error != null) last = root

        val adb = runViaAdb(context, null, PM_UNINSTALL)
        if (adb.ok && pollInstalled(context, wantInstalled = false)) return@withContext adb
        if (adb.error != null) last = adb

        last ?: Result(false, CHANNEL_NONE, "命令执行了但系统里还看得到占位包，请稍后重试")
    }

    // ══════════ 三条通道 ══════════

    /**
     * 走 Shizuku server 的 `newProcess`。
     *
     * @param apkBytes 非 null 时写进子进程 stdin（给 `cat >` 用）；null 表示不需要喂数据。
     */
    private fun runViaServer(apkBytes: ByteArray?, command: String = PM_INSTALL_SCRIPT): Result {
        if (apkBytes == null && command == PM_INSTALL_SCRIPT) {
            return Result(false, CHANNEL_SERVER, "没有 APK 数据")
        }
        return try {
            val binder = Shizuku.getBinder() ?: return Result(false, CHANNEL_SERVER, "服务未启动")
            val service = IShizukuService.Stub.asInterface(binder)
                ?: return Result(false, CHANNEL_SERVER, "binder 转接口失败")

            val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = StringBuilder()
            val reader = Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
                        .bufferedReader().forEachLine { stdout.appendLine(it) }
                }
            }
            reader.isDaemon = true
            reader.start()

            // 必须有写端：`cat >` 要读到 EOF 才会把文件收尾。
            // use{} 保证关闭，否则子进程会一直挂在读 stdin 上，waitForTimeout 必然超时。
            ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream).use { out ->
                if (apkBytes != null) {
                    out.write(apkBytes)
                    out.flush()
                }
            }

            val finished = remote.waitForTimeout(60, TimeUnit.SECONDS.name)
            if (!finished) {
                runCatching { remote.destroy() }
                return Result(false, CHANNEL_SERVER, "安装超时")
            }
            val exit = runCatching { remote.exitValue() }.getOrDefault(-1)
            if (exit == 0) Result(true, CHANNEL_SERVER)
            else Result(false, CHANNEL_SERVER, stdout.toString().trim().ifBlank { "退出码 $exit" })
        } catch (e: Throwable) {
            logd(TAG, "server 通道失败", e)
            Result(false, CHANNEL_SERVER, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 走 root（libsu）。
     *
     * @param apkPath 安装用的 APK 绝对路径；卸载时传 null。
     */
    private fun runViaRoot(apkPath: String?, command: String = PM_INSTALL_SCRIPT): Result {
        return try {
            val shell = Shell.getShell()
            if (!shell.isRoot) return Result(false, CHANNEL_ROOT, "没有 root")

            val cmd = if (apkPath == null || command != PM_INSTALL_SCRIPT) {
                command
            } else {
                // root 直接读得到本体私有目录，不用先 cp 到 /data/local/tmp
                // -d：允许版本号回落（占位包 versionCode 固定为 1）
                // -t：允许 test-only 包，某些 ROM 对非市场通道装的包会按 test 处理
                "pm install -r -d -t '$apkPath'"
            }
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) Result(true, CHANNEL_ROOT)
            else Result(
                false, CHANNEL_ROOT,
                (result.err?.firstOrNull { it.isNotBlank() } ?: result.out.firstOrNull { it.isNotBlank() } ?: "命令失败")
            )
        } catch (e: Throwable) {
            logd(TAG, "root 通道失败", e)
            Result(false, CHANNEL_ROOT, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 走 ADB（无线调试）。
     *
     * 为什么要先把 APK 解到外部存储：ADB shell 是 shell uid，读不了 /data/user/0/com.churan.shizako/files 下的文件，
     * 但 /sdcard/Android/data/<包名>/files 是 shell 可读的（不需要任何存储权限）。
     */
    private suspend fun runViaAdb(
        context: Context,
        apkBytes: ByteArray?,
        command: String = PM_INSTALL_SCRIPT,
    ): Result {
        val ports = resolveAdbPorts(context)
        if (ports.isEmpty()) return Result(false, CHANNEL_ADB, "没找到 ADB 端口")

        val remoteCommand = if (apkBytes == null || command != PM_INSTALL_SCRIPT) {
            command
        } else {
            val staged = stageForAdb(context, apkBytes)
                ?: return Result(false, CHANNEL_ADB, "外部存储不可用，无法给 ADB 准备 APK")
            // shell 读得到 staged 路径，再 cp 到 /data/local/tmp 后安装
            "cp -f '$staged' $REMOTE_TMP_PATH && $PM_INSTALL_SCRIPT"
        }

        val key = ShizukuSettings.getPreferences()?.let { AdbKey(PreferenceAdbKeyStore(it), "shizuku") }
            ?: return Result(false, CHANNEL_ADB, "ADB 密钥存储不可用")

        var lastError = "所有 ADB 端口都连不上"
        for (port in ports) {
            try {
                val output = StringBuilder()
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    client.shellCommand(remoteCommand) { data ->
                        synchronized(output) { output.append(String(data, Charsets.UTF_8)) }
                    }
                }
                val text = output.toString().trim()
                // ADB 的 shell 通道不回传退出码，只能看 pm 的输出文本
                if (text.contains("Success")) return Result(true, CHANNEL_ADB)
                return Result(false, CHANNEL_ADB, text.ifBlank { "端口 $port 没有输出" })
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                logd(TAG, "ADB 端口 $port 失败", e)
            }
        }
        return Result(false, CHANNEL_ADB, lastError)
    }

    // ══════════ 辅助 ══════════

    private fun readAsset(context: Context): ByteArray? = try {
        context.assets.open(ASSET_PATH).use { it.readBytes() }
    } catch (e: Throwable) {
        logd(TAG, "读取 assets/$ASSET_PATH 失败", e)
        null
    }

    private fun writeToPrivateStorage(context: Context, bytes: ByteArray): File? = try {
        val file = File(context.filesDir, ASSET_PATH)
        if (!file.exists() || file.length() != bytes.size.toLong()) {
            file.outputStream().use { it.write(bytes) }
        }
        file
    } catch (e: Throwable) {
        logd(TAG, "写私有目录失败", e)
        null
    }

    private fun stageForAdb(context: Context, bytes: ByteArray): File? = try {
        val dir = context.getExternalFilesDir(null) ?: return null
        val file = File(dir, ASSET_PATH)
        if (!file.exists() || file.length() != bytes.size.toLong()) {
            dir.mkdirs()
            file.outputStream().use { it.write(bytes) }
        }
        file
    } catch (e: Throwable) {
        logd(TAG, "写外部存储失败", e)
        null
    }

    /** ADB 端口候选：mDNS 发现的无线调试端口优先，再补上系统属性里配的端口和默认 5555 */
    private suspend fun resolveAdbPorts(context: Context): List<Int> {
        val ports = linkedSetOf<Int>()
        discoverAdbPort(context)?.let { if (it > 0) ports.add(it) }
        val configured = runCatching { EnvironmentUtils.getAdbTcpPort() }.getOrDefault(-1)
        if (configured > 0) ports.add(configured)
        ports.add(5555)
        return ports.toList()
    }

    /**
     * 用 mDNS 找 `_adb-tls-connect._tcp` 的端口。
     *
     * 为什么用反射而不是直接调：Android 14（API 34）起 NsdManager 把「回调走 Handler」的那套重载
     * 改成了 `@SystemApi` / `@hide`，编译到 android-36 时对编译器不可见
     * （javap 里只剩 `discoverServices(String,int,DiscoveryListener)` 和带 Executor/Network 的新版），
     * 但运行时这些方法还在，反射能调到。这里只要「一次性拿个端口」，不值得为此引依赖。
     *
     * 为什么不用现成的 [AdbMdns]：它的回调是 `androidx.lifecycle.Observer`，得按 Lifecycle 注册，
     * 对一次性查询来说太重。
     */
    private suspend fun discoverAdbPort(context: Context): Int? {
        val nsdManager = context.getSystemService(NsdManager::class.java) ?: return null
        val thread = HandlerThread("stub-adb-mdns")
        thread.start()
        val handler = Handler(thread.looper)

        var discovery: Any? = null
        return try {
            withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine { cont ->
                    val resolveClass = Class.forName("android.net.nsd.NsdManager\$ResolveListener")
                    val discoverClass = Class.forName("android.net.nsd.NsdManager\$DiscoveryListener")

                    val resolveListener = Proxy.newProxyInstance(
                        resolveClass.classLoader, arrayOf(resolveClass)
                    ) { _, method, args ->
                        when (method.name) {
                            "onServiceResolved" -> {
                                // NsdServiceInfo.getPort() 同样是隐藏 API 里的普通方法，直接反射取值
                                val port = args?.getOrNull(0)
                                    ?.let { info ->
                                        runCatching {
                                            info.javaClass.getMethod("getPort").invoke(info) as Int
                                        }.getOrNull()
                                    }
                                if (port != null && port > 0 && cont.isActive) cont.resume(port)
                            }
                        }
                        null
                    }

                    val discoveryListener = Proxy.newProxyInstance(
                        discoverClass.classLoader, arrayOf(discoverClass)
                    ) { _, method, args ->
                        when (method.name) {
                            "onServiceFound" -> {
                                val info = args?.getOrNull(0) ?: return@newProxyInstance null
                                runCatching {
                                    nsdManager.javaClass.getMethod(
                                        "resolveService", NsdServiceInfo::class.java, resolveClass, Handler::class.java
                                    ).invoke(nsdManager, info, resolveListener, handler)
                                }
                            }

                            "onStartDiscoveryFailed" -> if (cont.isActive) cont.resume(null)
                        }
                        null
                    }

                    discovery = discoveryListener
                    runCatching {
                        nsdManager.javaClass.getMethod(
                            "discoverServices", String::class.java, Int::class.javaPrimitiveType,
                            discoverClass, Handler::class.java
                        ).invoke(
                            nsdManager, AdbMdns.TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, discoveryListener, handler
                        )
                    }.onFailure {
                        logd(TAG, "启动 mDNS 发现失败", it)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } catch (e: Throwable) {
            logd(TAG, "mDNS 找 ADB 端口失败", e)
            null
        } finally {
            val listener = discovery
            if (listener != null) {
                runCatching {
                    nsdManager.javaClass.getMethod("stopServiceDiscovery", discoverClassFrom(listener))
                        .invoke(nsdManager, listener)
                }
            }
            thread.quitSafely()
        }
    }

    /** 反射调用 stopServiceDiscovery 时要从实例上取它实现的接口类型，不能写死类名字符串 */
    private fun discoverClassFrom(listener: Any): Class<*> =
        listener.javaClass.interfaces.firstOrNull() ?: listener.javaClass

    /** 装 / 卸之后轮询确认：`pm` 的退出码不等于 PackageManager 的最终状态 */
    private suspend fun pollInstalled(context: Context, wantInstalled: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + POLL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isInstalled(context) == wantInstalled) return true
            delay(200L)
        }
        return isInstalled(context) == wantInstalled
    }
}
