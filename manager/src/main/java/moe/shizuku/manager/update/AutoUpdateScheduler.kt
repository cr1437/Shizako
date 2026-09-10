package moe.shizuku.manager.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import moe.shizuku.manager.ShizukuSettings

/**
 * Schedules (or cancels) a daily background update check via AlarmManager.
 *
 * The alarm fires every 24 hours (inexact, battery-friendly).  When the device
 * reboots the alarm is re-scheduled by [UpdateCheckReceiver]'s
 * `BOOT_COMPLETED` handler, so the user never needs to re-enable it.
 */
object AutoUpdateScheduler {

    private const val ACTION_CHECK = "com.churan.shizako.action.UPDATE_CHECK"
    private const val ALARM_REQUEST_CODE = 2001

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateCheckReceiver::class.java).apply {
            action = ACTION_CHECK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    fun schedule(context: Context) {
        if (!ShizukuSettings.isAutoUpdateEnabled()) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val triggerAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000L

        try {
            am.cancel(pi)
            am.setInexactRepeating(
                AlarmManager.RTC,
                triggerAt,
                AlarmManager.INTERVAL_DAY,
                pi
            )
        } catch (_: SecurityException) { }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }
}