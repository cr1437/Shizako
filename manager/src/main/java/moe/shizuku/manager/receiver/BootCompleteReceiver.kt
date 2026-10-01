package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.watchdog.WatchdogService
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        // 后台保活：把看门狗也带到岗（用户开着看门狗的话；通知上有「杀后台」按钮）
        if (ShizukuSettings.isWatchdogEnabled()) {
            runCatching { WatchdogService.start(context) }
        }

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

        when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> ServiceStartHelper.startRoot()
            ShizukuSettings.LaunchMethod.ADB -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // https://r.android.com/2128832
                    && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                    adbStart(context)
                } else {
                    Log.w(AppConstants.TAG, "No support start on boot")
                }
            }
            else -> Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStart(context: Context) {
        val pending = goAsync()
        ServiceStartHelper.startAdb(context) { pending.finish() }
    }
}