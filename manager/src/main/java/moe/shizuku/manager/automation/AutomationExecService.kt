package moe.shizuku.manager.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 执行 EXEC_SHELL 请求的前台服务。
 *
 * 为什么需要它（而不是直接在广播接收器里跑命令）：
 * - `onReceive` 里有 10s（前台广播）/60s（后台广播）的硬上限，规格允许的 300s 超时
 *   必然撞线，撞线就是 ANR；
 * - 前台服务把进程钉在 FOREGROUND_SERVICE 状态，命令跑再久也不会被低内存回收。
 *
 * 冷启动限制：Android 12+ 禁止「后台应用启动前台服务」，被拦时
 * [start] 返回 false，接收器会退回 `goAsync()` + 线程，并把超时压到 50s
 * （见 `ExecShellReceiver.dispatchFallback`）。
 */
class AutomationExecService : Service() {

    companion object {
        private const val TAG = "ShizakoAutomation"
        private const val CHANNEL_ID = "shizako_automation"
        private const val NOTIFICATION_ID = 2101

        private const val EXTRA_REQUEST_ID = "svc_request_id"
        private const val EXTRA_COMMAND = "svc_command"
        private const val EXTRA_TIMEOUT_MS = "svc_timeout_ms"
        private const val EXTRA_TOKEN_ID = "svc_token_id"
        private const val EXTRA_CALLER_PACKAGE = "svc_caller_package"
        private const val EXTRA_CALLER_UID = "svc_caller_uid"
        private const val EXTRA_VERIFY_MODE = "svc_verify_mode"

        /**
         * 尝试以前台服务方式执行。
         *
         * @return false 表示系统拒绝了前台服务启动（Android 12+ 后台限制），
         *         调用方需要自己降级执行。
         */
        fun start(
            context: Context,
            token: TokenRecord,
            request: AutomationRequest,
            callerPackage: String?,
            callerUid: Int,
            verifyMode: String,
        ): Boolean {
            val intent = Intent(context, AutomationExecService::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, request.requestId)
                putExtra(EXTRA_COMMAND, request.command)
                putExtra(EXTRA_TIMEOUT_MS, request.timeoutMs)
                putExtra(EXTRA_TOKEN_ID, token.id)
                putExtra(EXTRA_CALLER_PACKAGE, callerPackage)
                putExtra(EXTRA_CALLER_UID, callerUid)
                putExtra(EXTRA_VERIFY_MODE, verifyMode)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException：后台不允许起前台服务。
                // 这不是错误，是平台策略，交给接收器降级。
                Log.w(TAG, "foreground service start rejected: ${t.javaClass.simpleName}")
                false
            }
        }
    }

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "shizako-automation-exec").apply { isDaemon = true }
    }
    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 必须在 5 秒内 startForeground，否则系统会抛 ForegroundServiceDidNotStartInTime。
        // 万一这里也失败（类型/权限被 ROM 砍），服务继续跑但不带前台标记 ——
        // 命令通常很快就结束，比直接丢弃请求好。
        startForegroundSafe()

        running.incrementAndGet()
        executor.execute {
            try {
                handle(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "handle failed", t)
            } finally {
                if (running.decrementAndGet() <= 0) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun handle(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val tokenId = intent.getStringExtra(EXTRA_TOKEN_ID) ?: return
        val timeoutMs = AutomationContract.clampTimeout(intent.getIntExtra(EXTRA_TIMEOUT_MS, 0))
        val callerPackage = intent.getStringExtra(EXTRA_CALLER_PACKAGE)
        val callerUid = intent.getIntExtra(EXTRA_CALLER_UID, -1)
        val verifyMode = intent.getStringExtra(EXTRA_VERIFY_MODE)
            ?: HistoryEntry.VERIFY_STATIC

        val token = AutomationStore.findTokenById(applicationContext, tokenId)
        if (token == null) {
            // 令牌在排队/启动期间被撤销了：不能执行，按令牌无效回报
            AutomationRunner.reject(
                applicationContext,
                requestId = requestId,
                callerPackage = callerPackage,
                callerUid = callerUid,
                tokenLabel = "",
                command = command,
                error = AutomationContract.ERR_DENIED_TOKEN,
                detail = "令牌已被撤销",
                verifyMode = verifyMode,
                notifyPackage = callerPackage,
            )
            return
        }

        AutomationRunner.executeAuthorized(
            context = applicationContext,
            token = token,
            request = AutomationRequest(requestId, command, timeoutMs),
            callerPackage = callerPackage,
            callerUid = callerUid,
            verifyMode = verifyMode,
        )
    }

    private fun startForegroundSafe() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed: ${t.javaClass.simpleName}")
        }
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(true)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.automation_notify_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.automation_notify_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            2101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.automation_notify_title))
            .setContentText(getString(R.string.automation_notify_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
