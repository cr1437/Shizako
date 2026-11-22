package moe.shizuku.manager.update

import android.app.job.JobParameters
import android.app.job.JobService
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.fdroid.FdroidBuild

/**
 * 后台「检查更新」的 JobScheduler 载体。
 *
 * 为什么从 AlarmManager 换过来：以前是每 6 小时一次的 `RTC_WAKEUP +
 * setAndAllowWhileIdle` 闹钟 —— 那是**会主动把设备从 Doze 里叫醒**的精确闹钟。
 * 只为查一个版本号就把整机唤醒，代价明显不成比例。
 *
 * 换成 JobScheduler 之后：
 * - 系统会在合适的维护窗口批量执行，不再单独唤醒；
 * - `setRequiredNetworkType(NETWORK_TYPE_ANY)`：没网就不会被调起（省一次唤醒）；
 * - `setPersisted(true)`：重启后由系统恢复，不需要 BOOT_COMPLETED 再排；
 * - 仍然保持 6 小时节奏（周期下限是 15 分钟，远低于这个值）。
 */
class UpdateCheckJobService : JobService() {

    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!FdroidBuild.allowSelfUpdate || !ShizukuSettings.isAutoUpdateEnabled()) {
            return false
        }
        worker = Thread({
            runCatching { UpdateChecker.checkAndNotify(this) }
            jobFinished(params, false)
        }, "update-check-job").also { it.start() }
        // 返回 true：告诉系统「活还在后台线程里干」，干完自己调用 jobFinished
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.interrupt()
        worker = null
        // 被系统提前掐断就重排，保证下一轮还会查
        return true
    }
}
