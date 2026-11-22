package moe.shizuku.manager.authorization

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.ConfirmationDialogBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuState
import rikka.core.res.resolveColor
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RequestPermissionActivity : AppActivity() {

    private lateinit var dialog: Dialog

    private fun setResult(requestUid: Int, requestPid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        val data = Bundle()
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        try {
            Shizuku.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: Throwable) {
            LOGGER.e("dispatchPermissionConfirmationResult")
        }
    }

    private fun checkSelfPermission(): Boolean {
        val permission = Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        if (permission) return true

        val icon = getDrawable(R.drawable.ic_system_icon)
        icon?.setTint(theme.resolveColor(android.R.attr.colorAccent))

        val dialog = MaterialAlertDialogBuilder(this)
                .setIcon(icon)
                .setTitle("Shizuku: ${getString(R.string.app_management_dialog_adb_is_limited_title)}")
                .setMessage(getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get()).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE))
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { finish() }
                .create()
        dialog.setOnShowListener {
            (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        }
        try {
            dialog.show()
        } catch (ignored: Throwable) {
        }
        return false
    }

    /**
     * 等 binder 连上。
     *
     * 修了两个坑：
     * 1. `CountDownLatch.await(timeout)` 返回 **boolean**，不会抛 TimeoutException —— 原实现
     *    把「等了 5 秒也没等到」当成成功继续往下走，于是没 binder 也照样弹框；用户点「允许」
     *    后 [setResult] 里 dispatch 抛异常被 log 吞掉，表现就是「点了确定没授权」。
     * 2. 进程里已经有 binder 时不再空等（常见路径，弹窗能立刻出来）。
     */
    private fun waitForBinder(): Boolean {
        // 常见情况：binder 已在进程里（例如刚在管理器里操作过），直接过
        if (ShizukuState.safePing()) return true

        val countDownLatch = CountDownLatch(1)

        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                countDownLatch.countDown()
                Shizuku.removeBinderReceivedListener(this)
            }
        }

        Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

        return try {
            if (countDownLatch.await(3, TimeUnit.SECONDS)) {
                true
            } else {
                // 没有服务时不要弹一个「点了也没用」的授权框：直接结束，交给客户端走
                // 「服务未运行」分支（客户端会提示用户先激活）。
                LOGGER.e("Binder not received in 3s, finish request dialog")
                Shizuku.removeBinderReceivedListener(listener)
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOGGER.e(e, "waitForBinder interrupted")
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!waitForBinder()) {
            finish()
            return
        }

        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        // applicationInfo 正常由服务端塞进来；万一没带（旧版本客户端 / 调试时 am start），
        // 按 uid 反查一次，免得直接关掉弹窗、让用户觉得「授权框怎么不出现」。
        val ai = intent.getParcelableExtra<ApplicationInfo>("applicationInfo")
            ?: packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
                runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
            }
        if (uid == -1 || pid == -1 || ai == null) {
            finish()
            return
        }
        if (!checkSelfPermission()) {
            setResult(uid, pid, requestCode, allowed = false, onetime = true)
            return
        }

        val label = try {
            ai.loadLabel(packageManager)
        } catch (e: Exception) {
            ai.packageName
        }

        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            button1.setOnClickListener {
                setResult(uid, pid, requestCode, allowed = true, onetime = false)
                dialog.dismiss()
            }
            button3.setOnClickListener {
                setResult(uid, pid, requestCode, allowed = false, onetime = true)
                dialog.dismiss()
            }
            title.text = HtmlCompat.fromHtml(getString(R.string.permission_warning_template,
                    label, getString(R.string.permission_group_description)))
        }

        dialog = MaterialAlertDialogBuilder(this)
                .setView(binding.root)
                .setCancelable(false)
                .setOnDismissListener { finish() }
                .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }
}
