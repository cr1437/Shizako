package moe.shizuku.manager.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings

/** 划掉 / 被杀后，由闹钟（AlarmManager）触发的自拉起入口。 */
class WatchdogRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ShizukuSettings.isWatchdogEnabled()) return
        WatchdogService.start(context)
    }
}