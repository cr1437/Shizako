package moe.shizuku.manager.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings

/**
 * Handles the periodic update-check alarm and re-schedules it after a reboot.
 *
 * Registered actions:
 *   com.churan.shizako.action.UPDATE_CHECK  — perform the actual check
 *   android.intent.action.BOOT_COMPLETED    — re-schedule the alarm
 */
class UpdateCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-schedule the alarm after a reboot if auto-update is enabled.
                AutoUpdateScheduler.schedule(context)
            }
            else -> {
                // Daily update check triggered by the alarm.
                if (ShizukuSettings.isAutoUpdateEnabled()) {
                    UpdateChecker.checkAndNotify(context)
                }
            }
        }
    }
}