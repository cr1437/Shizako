package moe.shizuku.manager.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.fdroid.FdroidBuild

/**
 * Schedules (or cancels) the periodic background update check via AlarmManager.
 *
 * 间隔 [CHECK_INTERVAL_MS]（6 小时），用 [AlarmManager.setAndAllowWhileIdle] 单次触发：
 * 即使在 Doze 打盹里也会按时醒来跑一次，触发后由 [UpdateCheckReceiver] 重排下一次。
 * 设备重启后由 [UpdateCheckReceiver] 的 `BOOT_COMPLETED` 分支重新排上，用户不需要手动再开。
 */
object AutoUpdateScheduler {

    private const val ACTION_CHECK = "com.churan.shizako.action.UPDATE_CHECK"
    private const val ALARM_REQUEST_CODE = 2001

    /** 后台检查间隔：6 小时（从 24 小时缩短，发布的更新能更快推到用户手上）。 */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateCheckReceiver::class.java).apply {
            action = ACTION_CHECK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    fun schedule(context: Context) {
        // F-Droid 版不排任何更新闹钟：周期检查会把用户引向「下载安装新版本」，
        // 而那条路在 F-Droid 版上必然失败（签名不同）—— 见 fdroid/FdroidBuild.kt
        if (!FdroidBuild.allowSelfUpdate) return
        if (!ShizukuSettings.isAutoUpdateEnabled()) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val triggerAt = System.currentTimeMillis() + CHECK_INTERVAL_MS

        try {
            am.cancel(pi)
            if (Build.VERSION.SDK_INT >= 23) {
                // 单次 + 允许 Doze 触发；跑完由 Receiver 再排下一次
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) { }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }
}