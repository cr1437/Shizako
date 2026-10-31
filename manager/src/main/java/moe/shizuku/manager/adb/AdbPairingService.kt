package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import rikka.core.ktx.unsafeLazy
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLException

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val activationRequestId = 4
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"

        /**
         * 配对端口用的 Intent extra key，**必须**和 [remoteInputResultKey] 分开。
         *
         * 以前两者共用 `"paring_code"`：一个装 CharSequence（配对码）、一个装 Int（端口），
         * 同一个 key 两种类型，依靠的是「RemoteInput 的结果走 ClipData、不会落进 extras」
         * 这条隐含前提。现场出现过「mDNS 明明报出 46499，点发送后却去连
         * `127.0.0.1:0`」，这种写法一旦前提不成立，现象就是端口丢掉或串味，
         * 而且报错看起来完全像是连不上。分开之后两者互不影响。
         */
        private const val portKey = "pairing_port"

        /**
         * 「输入配对码」这条 PendingIntent 的 requestCode 基数：每个端口用
         * `replyRequestBase + port`。见 [replyNotificationAction]。
         */
        private const val replyRequestBase = 10_000

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
        }
    }

    private var adbMdns: AdbMdns? = null

    private val observer = Observer<Int> { port ->
        Log.i(tag, "Pairing service port: $port")
        if (port <= 0) return@Observer

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 「根本没开始连」这类失败要等 startForeground **之后**再处理。
        // 这条意图是 PendingIntent.getForegroundService 拉起的，系统要求 5 秒内
        // startForeground；如果在这个 when 里直接 handleResult（它只发通知、不再进前台），
        // 就会撞上 ForegroundServiceDidNotStartInTimeException —— 为了报告一个配对失败，
        // 反而把整个进程崩掉。
        var pendingFailure: Throwable? = null

        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                // 现场排查用：端口有没有传过来、传成了几。配对码本身不进日志。
                Log.i(tag, "pairing reply received: port=$port, code length=${code.length}")
                if (port in 1..65535) {
                    onInput(code.toString(), port)
                } else {
                    // 端口拿不到就**不要**去 connect：0 会被 Socket 原样当成「连 0 号端口」，
                    // 抛出来是 "failed to connect to /127.0.0.1 (port 0)" —— 看起来像
                    // 「连不上」，实际是「端口丢了」，排查方向会被完全带偏。
                    Log.w(tag, "pairing reply carries no usable port ($port), refusing to connect")
                    pendingFailure = PairingAbortedException(
                        getString(R.string.pairing_port_missing)
                    )
                    workingNotification
                }
            }
            stopAction -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException) {
                    getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                }
            }
        }
        pendingFailure?.let { handleResult(false, it) }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    override fun onTimeout(startId: Int) {
        // A shortService foreground service must stop within ~3 minutes,
        // otherwise the system raises an ANR. Stop everything gracefully.
        super.onTimeout(startId)
        stopSearch()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification
    }

    private fun onInput(code: String, port: Int): Notification {
        GlobalScope.launch(Dispatchers.IO) {
            // 动手之前先确认那个端口还有人听。mDNS 报出端口之后、用户输完码之前，
            // 系统「使用配对码配对设备」对话框一旦被关掉，adbd 就把这个口收回去 ——
            // 这时去 connect 只会得到 ECONNREFUSED（现场报告里最常见的一句），
            // 而用户看到的是一句「连不上」，不知道该去重新打开对话框。
            if (!AdbMdns.isListening(port)) {
                Log.w(tag, "pairing service stopped listening on port $port before connect")
                handleResult(false, PairingAbortedException(getString(R.string.pairing_service_stopped, port)))
                return@launch
            }

            // 候选地址：回环优先，回环连不上时改用本机局域网地址兜底
            // （部分设备/ROM 上应用连不上自己的 127.0.0.1，见 AdbPairingClient 类注释）
            val hosts = AdbPairingClient.candidateHosts(this@AdbPairingService)

            val preferences = ShizukuSettings.getPreferences()
            // 配对期间的密钥只留内存，配对成功才落盘 —— 一次失败的尝试不该留在本地，
            // 否则激活页会把它当成「已配对」的证据（adbd 根本不认识它）
            val keyStore = DeferredAdbKeyStore(PreferenceAdbKeyStore(preferences))
            val key = try {
                AdbKey(keyStore, "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                // 以前这里直接 return，通知会永远停在「配对中」，用户等不出任何结果
                handleResult(false, AdbKeyException(e))
                return@launch
            }

            // use{} 保证 socket 与原生配对上下文一定被回收（start() 内部也做了 finally 清理，
            // 这里再套一层是防止将来有人改动 start() 的清理逻辑）。原先这里既没 use 也没 close：
            // 每次配对（含失败重试）都泄漏两个 fd + 一块持有 SPAKE2 密钥的原生内存。
            val result = runCatching {
                AdbPairingClient(hosts, port, code, key).use { client ->
                    client.start()
                }
            }
            result.onFailure {
                handleResult(false, it)
            }.onSuccess {
                keyStore.commit()
                AdbPairingState.markPaired(preferences)
                handleResult(true, null)
            }
        }

        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            Log.i(tag, "Pair succeed")

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = getString(R.string.notification_adb_pairing_succeed_text)

            stopSearch()
        } else {
            title = getString(R.string.notification_adb_pairing_failed_title)

            text = when (exception) {
                is PairingAbortedException -> {
                    // 这类失败不是「连不上」，而是根本没开始连 —— 文案在异常里，
                    // 直接照实说出来（端口丢了 / 配对服务已经停了）
                    exception.message
                }
                is ConnectException -> {
                    getString(R.string.cannot_connect_port)
                }
                is AdbInvalidPairingCodeException -> {
                    getString(R.string.paring_code_is_wrong)
                }
                is AdbKeyException -> {
                    getString(R.string.adb_error_key_store)
                }
                is AdbPairingFailedException -> {
                    getString(R.string.pairing_failed_protocol)
                }
                is SSLException -> {
                    getString(R.string.pairing_handshake_failed)
                }
                else -> {
                    // 原因不明时给一句能照做的提示，别把堆栈甩给用户
                    getString(R.string.pairing_failed_unknown)
                }
            }

            if (exception != null) {
                Log.w(tag, "Pair failed", exception)
            } else {
                Log.w(tag, "Pair failed")
            }
        }

        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            Notification.Builder(this, notificationChannel)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(title)
                .setContentText(text)
                .apply {
                    if (success) {
                        // 配对成功：点通知本体或点按钮都直接落到「一站式激活」页
                        setContentIntent(activationPendingIntent)
                        setAutoCancel(true)
                        addAction(enterActivationAction)
                    }
                    /*if (!success) {
                        addAction(retryNotificationAction)
                    }*/
                }
                .build()
        )

        // 配对码输入完成 → 自动跳转「一站式激活」页，并直接跑启动脚本把服务拉起来
        // （配对只是配对；「激活」= 用无线调试跑 [Starter.internalCommand] 起服务）
        if (success) {
            openActivationPage()
            startServiceAfterPairing()
        }

        stopSelf()
    }

    /**
     * 配对成功后自动「激活」：走无线调试把启动脚本跑一遍（[ServiceStartHelper.startAdb]），
     * 服务起来后把通知改成「服务已启动」；起不来就保留「配对成功」那条通知，
     * 主人点「进入激活」可以手动再试。
     */
    private fun startServiceAfterPairing() {
        notifySimple(R.string.notification_adb_pairing_starting_service)

        ServiceStartHelper.startAdb(this) {
            var running = Shizuku.pingBinder()
            var waited = 0L
            while (!running && waited < 8000) {
                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    break
                }
                waited += 250
                running = Shizuku.pingBinder()
            }

            if (running) {
                notifySimple(R.string.notification_adb_pairing_service_started, withActivationAction = true)
            } else {
                Log.w(tag, "Service did not come up after pairing")
            }
        }
    }

    private fun notifySimple(titleRes: Int, withActivationAction: Boolean = false) {
        val builder = Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(titleRes))
        if (withActivationAction) {
            builder.setContentIntent(activationPendingIntent)
                .setAutoCancel(true)
                .addAction(enterActivationAction)
        }
        getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
    }

    /**
     * 配对成功后自动打开激活页。
     *
     * 输入配对码时通知栏是展开的、Shizako 仍然是可见窗口，所以直接 startActivity 一般放行；
     * 万一被后台启动限制拦住，成功通知上还挂了 [activationPendingIntent] / [enterActivationAction]，
     * 用户点一下同样进激活页。
     */
    private fun openActivationPage() {
        // 首次引导还没完成时不打扰：主界面此刻会重定向回引导页，
        // 而引导页自己的「叫醒她」步骤会实时显示状态。
        if (!ShizukuSettings.isSetupCompleted()) return

        val intent = MainActivity.destinationIntent(this, R.id.activation_fragment)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivity(
                    intent,
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                        .toBundle()
                )
            } else {
                startActivity(intent)
            }
        }.onFailure {
            Log.w(tag, "Unable to open activation page", it)
        }
    }

    private val activationPendingIntent by unsafeLazy {
        PendingIntent.getActivity(
            this,
            activationRequestId,
            MainActivity.destinationIntent(this, R.id.activation_fragment),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
    }

    private val enterActivationAction by unsafeLazy {
        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_enter_activation),
            activationPendingIntent
        ).build()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_retry),
            pendingIntent
        )
            .build()
    }

    /**
     * 通知上的「输入配对码」动作。
     *
     * 每个端口建**各自**的 PendingIntent（requestCode = [replyRequestBase] + port），
     * 不再沿用「先建一个 port=-1 的、再靠 `FLAG_UPDATE_CURRENT` 就地改 extra」——
     * 后者依赖「requestCode 与 filterEquals 都相同才会更新」这条隐式规则，而且
     * Intent 的 filterEquals **不比较 extras**：一旦某次没走到更新分支，送出去的就是
     * 建这个 PendingIntent 时的 -1（或更糟，一个 0），连接阶段只会得到一句
     * 「连不上」，看不出端口根本没送到。
     */
    private fun replyNotificationAction(port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.dialog_adb_pairing_paring_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestBase + port,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

/**
 * 配对**还没开始**就被判定做不下去（端口丢失、配对服务已经停止监听）。
 *
 * 它和 [ConnectException] / [SSLException] 的区别很重要：后两者表示「连了但没成」，
 * 这个表示「根本没去连」，[message] 就是给用户看的原因 —— 界面要照实说出来，
 * 不能笼统地报一句「连不上」。
 */
private class PairingAbortedException(message: String) : IllegalStateException(message)
