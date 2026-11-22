package moe.shizuku.manager.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.fdroid.FdroidBuild

/**
 * Schedules (or cancels) the periodic background update check.
 *
 * 周期检查现在走 [JobScheduler]（见 [UpdateCheckJobService]），不再用会唤醒 Doze 的
 * `RTC_WAKEUP` 闹钟。间隔仍是 [CHECK_INTERVAL_MS]（6 小时），并且只在有网时执行。
 * 重启后由系统恢复持久任务（`setPersisted(true)`），不再依赖 `BOOT_COMPLETED`。
 *
 * 兼容旧版本：升级前可能已经排下一条 AlarmManager 闹钟（[LEGACY_ACTION_CHECK]），
 * 这里在调度/取消时顺手把它清掉，避免升级后还被旧的唤醒闹钟打扰一次。
 */
object AutoUpdateScheduler {

    private const val ACTION_CHECK = "com.churan.shizako.action.UPDATE_CHECK"
    private const val ALARM_REQUEST_CODE = 2001
    private const val JOB_ID = 2001

    /** 后台检查间隔：6 小时（从 24 小时缩短，发布的更新能更快推到用户手上）。 */
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private fun legacyPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateCheckReceiver::class.java).apply {
            action = ACTION_CHECK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    fun schedule(context: Context) {
        // F-Droid 版不排任何更新检查：周期检查会把用户引向「下载安装新版本」，
        // 而那条路在 F-Droid 版上必然失败（签名不同）—— 见 fdroid/FdroidBuild.kt
        if (!FdroidBuild.allowSelfUpdate) return
        if (!ShizukuSettings.isAutoUpdateEnabled()) return

        // 清掉旧版本留下的 AlarmManager 闹钟（只清一次，无代价）
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.cancel(legacyPendingIntent(context))
        }

        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
            .setPeriodic(CHECK_INTERVAL_MS)
            // 没网就不调起：避免为了一个必然失败的请求唤醒设备
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            // 重启后由系统恢复，不需要 BOOT_COMPLETED 再排一次
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(job) }
    }

    fun cancel(context: Context) {
        runCatching {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)
                ?.cancel(JOB_ID)
        }
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.cancel(legacyPendingIntent(context))
        }
    }
}
