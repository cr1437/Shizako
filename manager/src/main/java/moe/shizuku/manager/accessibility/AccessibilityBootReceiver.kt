package moe.shizuku.manager.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ktx.logd

/**
 * 开机后拉起无障碍常驻守护进程（仅在用户开了"开机自启"时）。
 *
 * 接收 LOCKED_BOOT_COMPLETED 是为了「直接启动」（direct boot）场景，
 * 因此 manifest 里这个 receiver 要带 directBootAware。
 */
class AccessibilityBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        if (!AccessibilityKeepAliveStore.isAutoBootEnabled()) return
        if (!AccessibilityKeepAliveStore.isKeepAliveEnabled()) return

        logd("Boot completed — starting accessibility keep-alive daemon")
        // 广播接收器里只有 applicationContext 是安全的；直接传出去避免持有 Activity
        AccessibilityDaemonService.reconcile(context.applicationContext)
    }
}
