package moe.shizuku.manager.starter

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
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
                Shell.cmd(Starter.internalCommand).exec()
                ok = true
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
     * No-op below Android 13; callers should check [canAdbAutoStart] first.
     *
     * @param onFinished always invoked once the attempt is over (success is
     * reported separately by the binder received listeners).
     */
    @JvmStatic
    fun startAdb(context: Context, onFinished: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onFinished?.invoke()
            return
        }

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

                // ② 自动开无线调试需要 WRITE_SECURE_SETTINGS；没有权限时不要写系统设置
                // （配对流程里无线调试本来就是开着的，直接连就行）
                if (canAdbAutoStart(appContext)) {
                    runCatching {
                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                    }
                }

                if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
                    return@launch
                }

                // ③ mDNS 找无线调试端口 → 交给 AdbStarter 连接并启动
                // （TCP 模式下它顺手把端口切到 5555 并关掉无线调试）
                val discovered = CompletableDeferred<Int>()
                val adbMdns = AdbMdns(appContext, AdbMdns.TLS_CONNECT) { port ->
                    if (port > 0) discovered.complete(port)
                }
                adbMdns.start()
                val port = try {
                    withTimeout(3_000L) { discovered.await() }
                } catch (_: TimeoutCancellationException) {
                    -1
                } finally {
                    adbMdns.stop()
                }
                if (port > 0) {
                    try {
                        AdbStarter.start("127.0.0.1", port, appContext)
                    } catch (e: AdbAuthPendingException) {
                        notifyAuthPending(appContext)
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                onFinished?.invoke()
            }
        }
    }

    /** 密钥还没被 adbd 信任时的统一提示（设备上可能有确认框，或需要重新配对）。 */
    private fun notifyAuthPending(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.adb_pair_required, Toast.LENGTH_LONG).show()
        }
    }
}