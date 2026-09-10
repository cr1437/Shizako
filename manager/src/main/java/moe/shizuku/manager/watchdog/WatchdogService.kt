package moe.shizuku.manager.watchdog

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
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import rikka.shizuku.Shizuku

/**
 * Watchdog foreground service that monitors the Shizuku server process.
 *
 * When Shizuku was running and then unexpectedly dies (crash / killed by
 * the system), the watchdog automatically restarts it using the last known
 * launch method.  A short debounce delay prevents restart storms after
 * repeated crashes, and a retry cap (3 consecutive failures in a 5-minute
 * window) stops the watchdog from looping forever.
 */
class WatchdogService : Service() {

    companion object {
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1002

        private const val RESTART_DELAY_MS = 3_000L
        private const val MAX_CONSECUTIVE_CRASHES = 3
        private const val CRASH_WINDOW_MS = 5 * 60 * 1000L

        private const val ACTION_STOP = "com.churan.shizako.action.WATCHDOG_STOP"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WatchdogService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
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
        }

        binderDeadListener = Shizuku.OnBinderDeadListener {
            if (wasRunning) {
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(restartRunnable)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
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
                if (ServiceStartHelper.canAdbAutoStart(this)) {
                    ServiceStartHelper.startAdb(this)
                }
            }
        }
    }
}