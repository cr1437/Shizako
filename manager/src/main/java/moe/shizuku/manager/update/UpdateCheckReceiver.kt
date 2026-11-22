package moe.shizuku.manager.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.fdroid.FdroidBuild

/**
 * 旧版「更新检查」AlarmManager 闹钟的接收器，以及开机兜底。
 *
 * - `com.churan.shizako.action.UPDATE_CHECK`：**只用于兼容升级前已经排下的闹钟**。
 *   现在周期检查已改用 JobScheduler（[UpdateCheckJobService]），这里收到旧闹钟时
 *   跑一次检查，然后调 [AutoUpdateScheduler.schedule]（它会把旧闹钟取消、排上新任务）。
 * - `BOOT_COMPLETED`：任务本身是 `setPersisted(true)`，理论上系统会自己恢复；
 *   这里再排一次当作兜底（幂等，重复 schedule 只会覆盖同一个 job id）。
 */
class UpdateCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-schedule the alarm after a reboot if auto-update is enabled.
                AutoUpdateScheduler.schedule(context)
            }
            else -> {
                // F-Droid 版没有自更新：闹钟可能还是上个版本排下的，收到就把自己取消掉
                if (!FdroidBuild.allowSelfUpdate) {
                    AutoUpdateScheduler.cancel(context)
                    return
                }
                // 定时检查：单次闹钟触发，跑完立刻排下一次（保持 6 小时节奏）
                if (ShizukuSettings.isAutoUpdateEnabled()) {
                    UpdateChecker.checkAndNotify(context)
                    AutoUpdateScheduler.schedule(context)
                } else {
                    AutoUpdateScheduler.cancel(context)
                }
            }
        }
    }
}