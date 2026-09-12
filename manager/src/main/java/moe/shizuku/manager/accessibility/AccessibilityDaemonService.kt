package moe.shizuku.manager.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.logi

/**
 * 让已固定的无障碍服务常驻的前台守护进程。
 *
 * 三个触发点（双保险）：
 *  1. 挂在 ENABLED_ACCESSIBILITY_SERVICES 上的 [ContentObserver] —— 系统或用户改动列表时触发；
 *  2. 亮屏 / 解锁广播 —— 无障碍服务最常在后台被杀，亮屏是廉价的复查时机；
 *  3. 60 秒心跳 —— 兜住那些不产生任何广播的改动。
 *
 * 检查本身很便宜：读一次启用列表、和固定列表比对，只有真的缺了才通过 Settings 写回去。
 */
class AccessibilityDaemonService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var observer: ContentObserver? = null
    private var heartbeatJob: Job? = null
    private var lastRestore = 0L

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON ||
                intent.action == Intent.ACTION_USER_PRESENT
            ) {
                scheduleCheck(delayMs = 1500L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerObserver()
        registerScreenReceiver()
        scheduleCheck(delayMs = 0L)
        logi("AccessibilityDaemonService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台通知放在这里而不是 onCreate：Android 14 要求 startForegroundService 之后
        // 5 秒内调用 startForeground，并且要带上与 manifest 声明一致的 type。
        startAsForeground()
        // 系统杀掉再重建时，心跳协程也要跟着回来
        if (heartbeatJob?.isActive != true) {
            startHeartbeat()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterObserver()
        unregisterReceiverSafe(screenOnReceiver)
        heartbeatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 监听
    // ------------------------------------------------------------------

    private fun registerObserver() {
        val resolver: ContentResolver = contentResolver
        observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                // 忽略我们自己写入造成的变化，否则"恢复"会自己触发自己形成死循环
                if (AccessibilityManager.isSelfWrite()) {
                    AccessibilityManager.clearSelfWrite()
                    return
                }
                scheduleCheck(delayMs = 500L)
            }
        }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer!!
        )
    }

    private fun unregisterObserver() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Android 13 起动态注册必须显式指定导出性；这两个系统广播只有系统会发，
        // 所以新版本用 NOT_EXPORTED（老版本没有这个常量，退回 EXPORTED）
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            ContextCompat.RECEIVER_EXPORTED
        }
        ContextCompat.registerReceiver(this, screenOnReceiver, filter, receiverFlags)
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (ignore: IllegalArgumentException) {
            // 没注册成功时（例如重复 destroy）忽略即可
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                scheduleCheck(delayMs = 0L)
            }
        }
    }

    private fun scheduleCheck(delayMs: Long) {
        serviceScope.launch {
            if (delayMs > 0) delay(delayMs)
            runCheck()
        }
    }

    private fun runCheck() {
        // 限流：两次检查至少间隔 2 秒，避免广播风暴里反复读写系统设置
        val now = System.currentTimeMillis()
        if (now - lastRestore < MIN_CHECK_INTERVAL_MS) return
        lastRestore = now

        val restored = AccessibilityManager.restorePinnedServices(this@AccessibilityDaemonService)
        if (restored.isNotEmpty()) {
            logi("Keep-alive restored accessibility services: $restored")
        } else {
            logd("Keep-alive check: nothing to restore")
        }
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        createChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 101, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.accessibility_daemon_notification_title))
            .setContentText(getString(R.string.accessibility_daemon_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse：常驻守护不属于任何既有前台服务类型，
            // manifest 里必须同时声明 FOREGROUND_SERVICE_SPECIAL_USE 权限与 subtype 属性
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (channelCreated) return
        channelCreated = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.accessibility_daemon_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "accessibility_keep_alive"
        private const val NOTIFICATION_ID = 1006
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val MIN_CHECK_INTERVAL_MS = 2_000L
        private var channelCreated = false

        /**
         * 按当前开关让守护进程和设置保持一致：
         * 总开关关着就停掉；开着就拉起（哪怕暂时没固定任何服务，
         * 也要跑起来，这样用户在界面上刚固定就能立刻生效）。
         */
        fun reconcile(context: Context) {
            if (!AccessibilityKeepAliveStore.isKeepAliveEnabled()) {
                context.stopService(Intent(context, AccessibilityDaemonService::class.java))
                return
            }
            val intent = Intent(context, AccessibilityDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 后台启动前台服务在 Android 12+ 可能被拒（前台服务启动限制），
                // 调用方（界面点击 / 开机广播）都在允许的场景里
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
