package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.provider.Settings
import moe.shizuku.manager.ShizukuSettings

/**
 * 开机后自动关上 USB 调试（照搬 Shevery 的做法）。
 *
 * 为什么：用 ADB 启动过 Shizuku 之后，USB 调试一直开着是个安全口子 ——
 * 插上任何一台电脑都能拿到 shell。这里在开机时把它关掉，需要时再自己开。
 *
 * 前提：本应用得先有 WRITE_SECURE_SETTINGS（ADB 给过一次就有）；
 * 另外只在「开机自启的接收器已经被禁用」时才动手 —— 那说明用户不需要常驻监听，
 * 不然会和开机自启逻辑打架。
 */
class AutoDisableUsbDebuggingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        ShizukuSettings.initialize(context)
        if (!ShizukuSettings.getAutoDisableUsbDebugging()) return

        val bootReceiver = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
        val bootReceiverState = context.packageManager.getComponentEnabledSetting(bootReceiver)
        if (bootReceiverState == COMPONENT_ENABLED_STATE_ENABLED) return
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
    }
}
