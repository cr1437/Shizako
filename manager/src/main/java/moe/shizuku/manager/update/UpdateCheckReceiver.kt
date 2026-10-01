package moe.shizuku.manager.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.fdroid.FdroidBuild

/**
 * Handles the periodic update-check alarm and re-schedules it after a reboot.
 *
 * Registered actions:
 *   com.churan.shizako.action.UPDATE_CHECK  — perform the actual check, then queue the next
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