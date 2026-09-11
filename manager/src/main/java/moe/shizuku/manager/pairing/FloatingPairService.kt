package moe.shizuku.manager.pairing

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.databinding.FloatingPairBinding
import moe.shizuku.manager.starter.Starter
import java.net.ConnectException

/**
 * 无线调试「悬浮窗配对」服务：替代旧的通知栏 RemoteInput 输入方式。
 *
 * 悬浮窗始终显示在系统设置界面之上，用户在系统「无线调试」配对对话框里
 * 看到配对码后，不用下拉通知栏、不用来回切换应用，直接在悬浮窗里输入：
 *
 * 配对阶段：mDNS(_adb-tls-pairing) 自动发现配对端口并填入（也可手动输入），
 * 输入 6 位配对码 → 点「配对并启动」。
 *
 * 启动阶段：配对成功后自动用 mDNS(_adb-tls-connect) 查找连接端口，
 * 找到即自动连接并执行 starter 启动 Shizako；找不到时可手动输入
 * 无线调试首页显示的端口再点「启动」。
 */
@RequiresApi(Build.VERSION_CODES.R)
class FloatingPairService : Service() {

    companion object {

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        /** 悬浮窗显示超时（避免用户忘记关闭后一直悬浮） */
        private const val WINDOW_TIMEOUT_MS = 5 * 60 * 1000L

        fun startIntent(context: Context): Intent =
            Intent(context, FloatingPairService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, FloatingPairService::class.java).setAction(ACTION_STOP)

        /** 悬浮窗权限是否已授予 */
        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** 跳转系统「显示在其他应用上层」授权页 */
        fun overlayPermissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
    }

    /** 配对阶段：输入配对码完成配对；启动阶段：获取连接端口并启动服务 */
    private enum class Phase { PAIR, CONNECT }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private var binding: FloatingPairBinding? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var pairMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null

    private var phase = Phase.PAIR
    private var working = false

    private val stopSelfRunnable = Runnable { stopSelf() }
    private val timeoutRunnable = Runnable {
        updateStatus(getString(R.string.floating_pair_status_timeout))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> showWindowOnce()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showWindowOnce() {
        if (binding != null) return
        if (!canShow(this)) {
            Toast.makeText(this, R.string.floating_pair_no_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val b = FloatingPairBinding.inflate(LayoutInflater.from(this))
        binding = b

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL：窗外的触摸照常落到系统设置上，用户可以
            // 同时操作系统配对对话框和本悬浮窗；窗口保持可聚焦以弹出键盘。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = resources.getDimensionPixelSize(R.dimen.ksu_nav_margin_bottom) * 4
        }
        windowParams = params

        setupDrag(b)
        setupInputs(b)
        setupActions(b)

        windowManager.addView(b.root, params)
        enterPairPhase()
        handler.postDelayed(stopSelfRunnable, WINDOW_TIMEOUT_MS)
    }

    // ---------- UI 装配 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(b: FloatingPairBinding) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        b.dragHandle.setOnTouchListener { _, event ->
            val params = windowParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(b.root, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupInputs(b: FloatingPairBinding) {
        // 输入时把窗口提为可聚焦（键盘弹出），输入完成收起键盘
        val imm = getSystemService(InputMethodManager::class.java)
        val editTexts = listOfNotNull(
            b.pairingCode.editText, b.pairingPort.editText
        )
        editTexts.forEach { edit ->
            edit.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun setupActions(b: FloatingPairBinding) {
        b.closeButton.setOnClickListener { stopSelf() }
        b.actionButton.setOnClickListener {
            when (phase) {
                Phase.PAIR -> onPairClicked()
                Phase.CONNECT -> onStartClicked()
            }
        }
    }

    // ---------- 阶段一：配对 ----------

    private fun enterPairPhase() {
        phase = Phase.PAIR
        val b = binding ?: return
        b.pairingCode.isVisible = true
        b.pairingCode.editText?.setText("")
        b.pairingPort.editText?.setText("")
        b.actionButton.text = getString(R.string.floating_pair_action_pair)
        updateStatus(getString(R.string.floating_pair_status_discovering))
        handler.postDelayed(timeoutRunnable, 60_000)

        pairMdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
            handler.post {
                if (port > 0) {
                    handler.removeCallbacks(timeoutRunnable)
                    binding?.pairingPort?.editText?.setText(port.toString())
                    updateStatus(getString(R.string.floating_pair_status_ready))
                }
            }
        }.apply { start() }
    }

    private fun onPairClicked() {
        val b = binding ?: return
        val code = b.pairingCode.editText?.text?.toString()?.trim().orEmpty()
        val port = b.pairingPort.editText?.text?.toString()?.trim()?.toIntOrNull() ?: -1

        if (code.length != 6) {
            b.pairingCode.error = getString(R.string.floating_pair_error_code)
            return
        }
        b.pairingCode.error = null
        if (port !in 1..65535) {
            b.pairingPort.error = getString(R.string.floating_pair_error_port)
            return
        }
        b.pairingPort.error = null

        setWorking(true, getString(R.string.floating_pair_status_pairing))
        GlobalScope.launch(Dispatchers.IO) {
            // 与 StarterFragment / AdbPairingService 同一密钥别名，保证
            // 无论从哪个入口配对/连接，用的是同一把持久化的 ADB 密钥。
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                postFailure(AdbKeyException(e))
                return@launch
            }
            AdbPairingClient("127.0.0.1", port, code, key).runCatching {
                start()
            }.onSuccess { ok ->
                if (ok) {
                    handler.post { enterConnectPhase() }
                } else {
                    postFailure(null)
                }
            }.onFailure {
                postFailure(it)
            }
        }
    }

    // ---------- 阶段二：连接并启动 ----------

    private fun enterConnectPhase() {
        phase = Phase.CONNECT
        setWorking(false, getString(R.string.floating_pair_status_paired))
        val b = binding ?: return
        b.pairingCode.isVisible = false
        b.pairingPort.editText?.setText("")
        b.actionButton.text = getString(R.string.floating_pair_action_start)
        hideKeyboard()
        handler.postDelayed(timeoutRunnable, 60_000)

        connectMdns = AdbMdns(this, AdbMdns.TLS_CONNECT) { port ->
            handler.post {
                if (port > 0 && phase == Phase.CONNECT && !working) {
                    handler.removeCallbacks(timeoutRunnable)
                    binding?.pairingPort?.editText?.setText(port.toString())
                    // 连接端口一到手就直接启动，全自动
                    doStart(port)
                }
            }
        }.apply { start() }
    }

    private fun onStartClicked() {
        val b = binding ?: return
        val port = b.pairingPort.editText?.text?.toString()?.trim()?.toIntOrNull() ?: -1
        if (port !in 1..65535) {
            b.pairingPort.error = getString(R.string.floating_pair_error_port)
            return
        }
        b.pairingPort.error = null
        doStart(port)
    }

    private fun doStart(port: Int) {
        setWorking(true, getString(R.string.floating_pair_status_starting))
        GlobalScope.launch(Dispatchers.IO) {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                postFailure(AdbKeyException(e))
                return@launch
            }
            AdbClient("127.0.0.1", port, key).runCatching {
                connect()
                shellCommand(Starter.internalCommand) { /* 输出不显示在悬浮窗 */ }
                close()
            }.onSuccess {
                handler.post { onStarted() }
            }.onFailure {
                postFailure(it)
            }
        }
    }

    private fun onStarted() {
        val b = binding
        working = false
        updateStatus(getString(R.string.floating_pair_status_success))
        b?.actionButton?.isVisible = false
        b?.pairingPort?.isVisible = false
        // 启动成功后停留 3 秒自动关闭
        handler.removeCallbacks(stopSelfRunnable)
        handler.postDelayed(stopSelfRunnable, 3000)
    }

    // ---------- 状态与收尾 ----------

    private fun postFailure(t: Throwable?) {
        handler.post {
            setWorking(false, null)
            val msg = when (t) {
                is ConnectException -> getString(R.string.cannot_connect_port)
                is AdbInvalidPairingCodeException -> getString(R.string.paring_code_is_wrong)
                is AdbKeyException -> getString(R.string.adb_error_key_store)
                null -> getString(R.string.floating_pair_status_failed_unknown)
                else -> getString(R.string.floating_pair_status_failed, t.message ?: t.javaClass.simpleName)
            }
            updateStatus(msg)
        }
    }

    private fun setWorking(w: Boolean, status: String?) {
        working = w
        val b = binding ?: return
        b.actionButton.isEnabled = !w
        b.pairingCode.isEnabled = !w
        b.pairingPort.isEnabled = !w
        status?.let { updateStatus(it) }
    }

    private fun updateStatus(text: String) {
        binding?.statusText?.text = text
    }

    private fun hideKeyboard() {
        val b = binding ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.root.windowToken, 0)
        b.root.clearFocus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pairMdns?.stop()
        connectMdns?.stop()
        binding?.let {
            runCatching { windowManager.removeView(it.root) }
        }
        binding = null
        super.onDestroy()
    }
}
