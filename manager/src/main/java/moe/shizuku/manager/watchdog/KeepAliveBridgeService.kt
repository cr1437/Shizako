package moe.shizuku.manager.watchdog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import moe.shizuku.manager.ShizukuSettings

/**
 * 键盘安全的「拉起 / 唤醒」入口（exported Service）。
 *
 * 为什么是 Service 而不是 Activity：
 * - 启动 Activity 会抢一次焦点 —— 正在打字时会把输入法顶下去（键盘缩回）；
 * - Service 完全没有界面，不抢焦点、不缩键盘，适合被 ADB/shell 侧的小看门
 *   （KeepAliveDaemon）反复调用。
 *
 * 用途：
 * 1. 冷启动：App 被杀后用 `am start-service` 把进程拉回来；
 * 2. 唤醒：App 被系统（如 vivo fast_freezer）冻结后，用 `am start-service`
 *    触发一次「投递」，让系统解冻进程执行本服务；
 * 3. 兜底：顺带把需要常驻的服务（看门狗）带起来。
 *
 * shell 侧调用示例：
 *   am start-service -n com.churan.shizako/moe.shizuku.manager.watchdog.KeepAliveBridgeService
 */
class KeepAliveBridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            if (ShizukuSettings.isWatchdogEnabled()) {
                WatchdogService.start(this)
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
