package moe.shizuku.manager.starter

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
     * Enable wireless debugging, discover the local ADB port via mDNS and
     * start the service through it, asynchronously.
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

        val cr = context.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

        CoroutineScope(Dispatchers.IO).launch {
            val latch = CountDownLatch(1)
            val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port > 0) {
                    try {
                        val keystore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
                        val key = AdbKey(keystore, "shizuku")
                        val client = AdbClient("127.0.0.1", port, key)
                        client.connect()
                        client.shellCommand(Starter.internalCommand, null)
                        client.close()
                    } catch (_: Exception) {
                    }
                }
                latch.countDown()
            }
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                adbMdns.start()
                latch.await(3, TimeUnit.SECONDS)
                adbMdns.stop()
            }
            onFinished?.invoke()
        }
    }
}
