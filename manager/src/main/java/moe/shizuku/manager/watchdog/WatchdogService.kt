package moe.shizuku.manager.watchdog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.starter.ServiceStartHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku

/**
 * Watchdog foreground service that monitors the Shizuku server process.
 *
 * 后台保活（两条腿走路）：
 * - 系统级：前台常驻服务 + 划掉时 onTaskRemoved 立刻自拉起 + 闹钟兜底；
 * - ADB 级：shell 侧小看门（[KeepAliveDaemon]，走本机 ADB 5555 驻守），
 *   App 被系统杀掉时由它用 `am` 把服务拉回来；
 * - 常驻通知上带「杀后台」按钮：想关的时候一键撤保活并完全退场。
 */
class WatchdogService : Service() {

    companion object {
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1002

        private const val RESTART_DELAY_MS = 3_000L
        private const val MAX_CONSECUTIVE_CRASHES = 3
        private const val CRASH_WINDOW_MS = 5 * 60 * 1000L

        private const val ACTION_STOP = "com.churan.shizako.action.WATCHDOG_STOP"
        private const val ACTION_KILL = "com.churan.shizako.action.WATCHDOG_KILL"

        /** 划掉 / 被杀后的闹钟自拉起（WatchdogRestartReceiver 接收）。 */
        private const val ACTION_RESTART = "com.churan.shizako.action.WATCHDOG_RESTART"
        private const val RESTART_REQUEST_CODE = 40
        private const val KILL_REQUEST_CODE = 41
        private const val RESTART_ALARM_DELAY_MS = 2_500L

        @Volatile
        var isRunning = false
            private set

        /**
         * 预期内的暂停窗口：AdbStarter 切 TCP 端口 / 重启 adbd 时，服务会短暂“死”一下。
         * 这个时间窗里收到的 binder 死亡事件看门狗直接放过，不当崩溃处理。
         */
        @Volatile
        private var expectedDeathUntilMs = 0L

        fun expectDeathWindow(ms: Long = 15_000L) {
            expectedDeathUntilMs = SystemClock.elapsedRealtime() + ms
        }

        private fun inExpectedDeathWindow(): Boolean =
            SystemClock.elapsedRealtime() < expectedDeathUntilMs

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, WatchdogService::class.java).apply { action = ACTION_STOP }
                context.startService(intent)
            }
        }

        /** 通知上的「杀后台」：撤保活 → 停服务 → 结束自己（App 完全退场）。 */
        fun killBackground(context: Context) {
            runCatching {
                val intent = Intent(context, WatchdogService::class.java).apply { action = ACTION_KILL }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** 划掉 / 被杀后的系统级兜底：闹钟几秒后把服务拉回来。 */
        fun scheduleRestart(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val at = SystemClock.elapsedRealtime() + RESTART_ALARM_DELAY_MS
            val pi = restartPendingIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (_: Throwable) {
                runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            }
        }

        fun cancelScheduledRestart(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                am.cancel(restartPendingIntent(context))
            }
        }

        private fun restartPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogRestartReceiver::class.java).setAction(ACTION_RESTART)
            return PendingIntent.getBroadcast(
                context, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wasRunning = false
    private var consecutiveCrashes = 0
    private var lastCrashTime = 0L

    private val restartRunnable = Runnable {
        if (!Shizuku.pingBinder()) {
            restartShizuku()
        }
    }

    private lateinit var binderReceivedListener: Shizuku.OnBinderReceivedListener
    private lateinit var binderDeadListener: Shizuku.OnBinderDeadListener

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        binderReceivedListener = Shizuku.OnBinderReceivedListener {
            wasRunning = true
            consecutiveCrashes = 0
            lastCrashTime = 0L
            updateNotification()
            // 服务回来了 → 确认 shell 小看门在岗（「用 ADB 保活」）
            KeepAliveDaemon.ensureRunningAsync(this)
        }

        binderDeadListener = Shizuku.OnBinderDeadListener {
            if (inExpectedDeathWindow()) {
                // 预期内的死亡（TCP 切换 / 重启 adbd）：就地消掉窗口，不安排重启
                expectedDeathUntilMs = 0L
                wasRunning = false
            } else if (wasRunning) {
                val now = System.currentTimeMillis()
                if (now - lastCrashTime > CRASH_WINDOW_MS) {
                    consecutiveCrashes = 0
                }
                consecutiveCrashes++
                lastCrashTime = now

                if (consecutiveCrashes <= MAX_CONSECUTIVE_CRASHES) {
                    handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                }
                wasRunning = false
            }
            updateNotification()
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        wasRunning = Shizuku.pingBinder()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardownKeepAlive()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_KILL -> {
                teardownKeepAlive()
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        // 后台保活：确保 shell 小看门在岗（用 ADB spawn；拿不到 ADB / root 时静默失败，下次触发再试）
        KeepAliveDaemon.ensureRunningAsync(this)

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!ShizukuSettings.isWatchdogEnabled()) return

        // ① 系统级兜底：立刻尝试自拉起（能成就成）
        runCatching {
            val svc = Intent(this, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        }
        // ② 系统级兜底：几秒后闹钟再拉一次（防进程被秒杀）
        scheduleRestart(this)
        // ③ ADB 兜底：确保 shell 小看门在岗（它会在下一轮把 App 拉回来）
        KeepAliveDaemon.ensureRunningAsync(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(restartRunnable)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    /** 「杀后台」/ 关闭保活：撤掉闹钟与 shell 小看门（脚本每轮自检，看到 flag 没了就退出）。 */
    private fun teardownKeepAlive() {
        cancelScheduledRestart(this)
        KeepAliveDaemon.disable(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.watchdog_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val killPi = PendingIntent.getService(
            this, KILL_REQUEST_CODE,
            Intent(this, WatchdogService::class.java).setAction(ACTION_KILL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val running = Shizuku.pingBinder()
        val title = getString(R.string.watchdog_notification_title)
        val text = if (running) {
            getString(R.string.watchdog_notification_running)
        } else {
            if (consecutiveCrashes > 0) {
                getString(R.string.watchdog_notification_crashed, consecutiveCrashes)
            } else {
                getString(R.string.watchdog_notification_waiting)
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(getColor(R.color.notification))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.watchdog_action_kill),
                killPi
            )
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun restartShizuku() {
        when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> {
                ServiceStartHelper.startRoot()
            }
            ShizukuSettings.LaunchMethod.ADB -> {
                if (ShizukuSettings.isTcpMode() && EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT)) {
                    // TCP 模式：本机 5555 还活着，直接连回来（不用网络、不用无线调试）——照搬 Shevery
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            AdbStarter.start("127.0.0.1", AdbStarter.TCP_MODE_PORT, applicationContext)
                        }
                    }
                } else if (ServiceStartHelper.canAdbAutoStart(this)) {
                    ServiceStartHelper.startAdb(this)
                }
            }
        }
    }
}