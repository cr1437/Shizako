package moe.shizuku.manager.starter

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbAuthPendingException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.utils.EnvironmentUtils

/**
 * Shared service start logic, used by both [moe.shizuku.manager.receiver.BootCompleteReceiver]
 * and the quick settings tiles, so behavior never drifts between entry points.
 */
object ServiceStartHelper {

    private const val TAG = "ServiceStartHelper"

    /**
     * Whether Shizako is able to enable wireless debugging by itself, so an
     * ADB (wireless debugging) start can be performed without user interaction.
     *
     * Requires Android 13+ (https://r.android.com/2128832) and the
     * WRITE_SECURE_SETTINGS permission, which the user grants once via
     * `adb shell pm grant com.churan.shizako android.permission.WRITE_SECURE_SETTINGS`.
     */
    @JvmStatic
    fun canAdbAutoStart(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start the service with root, asynchronously.
     *
     * @param onFinished invoked with true if a root shell was obtained and the
     * start command was sent, false otherwise.
     */
    @JvmStatic
    fun startRoot(onFinished: ((Boolean) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            var ok = false
            if (Shell.getShell().isRoot) {
                // 只看「有没有 root shell」是不够的：starter 自己失败时退出码同样非 0。
                // 而且**必须记下启动方式** —— 否则看门狗 / 开机自启 / 磁贴里的
                // `when (getLastLaunchMode())` 全部落空，服务死了不会被拉回来，
                // 用户只会看到「运行中」凭空变成「未运行」。
                val result = runCatching { Shell.cmd(Starter.internalCommand).exec() }.getOrNull()
                ok = result?.isSuccess == true
                if (ok) {
                    ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ROOT)
                }
            } else {
                Shell.getCachedShell()?.close()
            }
            onFinished?.invoke(ok)
        }
    }

    /**
     * Start the service through ADB, asynchronously.
     *
     * 顺序（照搬 Shevery 的 TCP 模式设计）：
     * 1. TCP 模式且 5555 还活着 → 直接本机连上，不需要任何网络；
     * 2. 尽力打开无线调试（需要 [canAdbAutoStart]），等 mDNS 报出端口；
     * 3. 交给 [AdbStarter] 连接启动 —— TCP 模式下会顺手切到 5555 并关掉无线调试。
     *
     * Android 10 及以下没有无线调试，只能走 ① TCP 直连；Android 11~12 会在 ② 让步
     * （拿不到 WRITE_SECURE_SETTINGS 自动开无线调试），直接走「无线调试已开 + 已配对」
     * 的 mDNS 连接 —— 所以这里**不能**按 Android 13 一刀切地 return，否则 11/12 上
     * 用户点了「启动服务」会毫无反应。
     *
     * @param onFinished always invoked once the attempt is over (success is
     * reported separately by the binder received listeners).
     */
    @JvmStatic
    fun startAdb(context: Context, onFinished: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val cr = appContext.contentResolver
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ① TCP 模式且 5555 还活着：直接本机连上——断网也能启动（照搬 Shevery 的 TCP 模式设计）
                if (ShizukuSettings.isTcpMode() && EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT)) {
                    try {
                        AdbStarter.start("127.0.0.1", AdbStarter.TCP_MODE_PORT, appContext)
                        return@launch
                    } catch (e: AdbAuthPendingException) {
                        notifyAuthPending(appContext)
                        return@launch
                    } catch (_: Throwable) {
                        // TCP 直连失败：继续走无线调试路径兜底（比如 adbd 状态不稳的时候）
                    }
                }

                // 无线调试（mDNS `_adb-tls-connect._tcp`）是 Android 11 才有的能力，
                // 更早的版本没有这条路，TCP 直连又没成功就只能收工。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@launch

                // ② 自动开无线调试需要 WRITE_SECURE_SETTINGS；没有权限时不要写系统设置
                // （配对流程里无线调试本来就是开着的，直接连就行）
                if (canAdbAutoStart(appContext)) {
                    runCatching {
                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                    }
                }

                var port = -1

                if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                    // ③ mDNS 找无线调试端口 → 交给 AdbStarter 连接并启动
                    // （TCP 模式下它顺手把端口切到 5555 并关掉无线调试）
                    //
                    // 6 秒而不是 3 秒：adbd 在「无线调试」刚打开/刚配对完时重新注册
                    // `_adb-tls-connect._tcp` 有一小段空窗，NsdManager 的解析回调也可能
                    // 排队（Android 11~13 同一时刻只允许一个 resolve）。3 秒在部分机型上
                    // 就是差一口气，结果是「配对成功但启动时找不到端口」→ 什么都不发生。
                    val discovered = CompletableDeferred<Int>()
                    val adbMdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT) { p ->
                        if (p > 0) discovered.complete(p)
                    }
                    adbMdns.start()
                    port = try {
                        withTimeout(6_000L) { discovered.await() }
                    } catch (_: TimeoutCancellationException) {
                        Log.w(TAG, "mDNS discovery of " + AdbMdns.TLS_CONNECT + " timed out")
                        -1
                    } finally {
                        adbMdns.stop()
                    }
                }

                // ④ mDNS 没结果时的兜底：本机已经有一个活着的 ADB TCP 端口就直接连它。
                //
                //    mDNS 走组播，任何「防泄漏」策略都能把它掐掉 —— GrapheneOS 开着 VPN 时
                //    组播不会进 App，华为 / 鸿蒙部分机型同样拦；系统不给 WRITE_SECURE_SETTINGS
                //    时无线调试也开不起来。以前这几种情况都是静默 return（点了没反应），
                //    现在至少还能连上 adbd 已经监听的端口。
                if (port <= 0) {
                    port = resolveLivePort()
                    if (port > 0) Log.i(TAG, "falling back to live ADB port $port")
                }

                if (port > 0) {
                    try {
                        AdbStarter.start("127.0.0.1", port, appContext)
                    } catch (e: AdbAuthPendingException) {
                        notifyAuthPending(appContext)
                    } catch (e: Throwable) {
                        // 以前这里把异常整个吞掉：用户只看到「点了没反应」。
                        // 至少要让现场日志能定位到是哪一步、哪个端口失败的。
                        Log.w(TAG, "ADB start failed on port $port", e)
                    }
                } else {
                    Log.w(TAG, "no usable ADB port (wifi debugging disabled or mDNS blocked)")
                }
            } finally {
                onFinished?.invoke()
            }
        }
    }

    /**
     * 找一个「已经活着」的 ADB TCP 端口。
     *
     * [EnvironmentUtils.getLiveAdbTcpPort] 是阻塞式 socket 探测（每个候选端口最多 250ms），
     * 必须在 IO 线程调用，所以这里显式切到 [Dispatchers.IO]。
     */
    private suspend fun resolveLivePort(): Int = withContext(Dispatchers.IO) {
        runCatching { EnvironmentUtils.getLiveAdbTcpPort() }.getOrDefault(-1)
    }

    /** 密钥还没被 adbd 信任时的统一提示（设备上可能有确认框，或需要重新配对）。 */
    private fun notifyAuthPending(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.adb_pair_required, Toast.LENGTH_LONG).show()
        }
    }
}