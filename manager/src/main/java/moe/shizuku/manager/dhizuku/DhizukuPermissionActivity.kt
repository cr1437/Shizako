package moe.shizuku.manager.dhizuku

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.ConfirmationDialogBinding
import moe.shizuku.manager.utils.Logger.LOGGER
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.html.text.HtmlCompat

/**
 * Dhizuku 授权对话框。
 *
 * Dhizuku-API 应用调用 Dhizuku.requestPermission() 时，
 * 通过 action `{applicationId}.action.REQUEST_DHIZUKU_PERMISSION` 拉起本页。
 * 同意后 uid 写入 Dhizuku 本地白名单，并回调客户端授权结果。
 */
class DhizukuPermissionActivity : AppActivity() {

    private var uid = -1
    private var listener: IBinder? = null
    private var resultSent = false
    private lateinit var dialog: Dialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = listOfNotNull(intent.extras, intent.getBundleExtra("bundle"))
            .firstOrNull { it.containsKey(DhizukuConstants.PARAM_CLIENT_UID) }
        if (extras == null) {
            finish()
            return
        }

        uid = extras.getInt(DhizukuConstants.PARAM_CLIENT_UID, -1)
        listener = extras.getBinder(DhizukuConstants.PARAM_CLIENT_REQUEST_PERMISSION_BINDER)
        if (uid == -1 || listener == null) {
            finish()
            return
        }

        val ai = packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
            runCatching {
                packageManager.getApplicationInfo(pkg, 0)
            }.getOrNull()
        }
        if (ai == null) {
            setResult(false)
            finish()
            return
        }

        if (DhizukuSettings.isGranted(uid)) {
            setResult(true)
            finish()
            return
        }

        showDialog(ai)
    }

    private fun showDialog(ai: ApplicationInfo) {
        val label = runCatching { ai.loadLabel(packageManager) }.getOrNull() ?: ai.packageName

        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            button1.setOnClickListener {
                setResult(true)
                dialog.dismiss()
            }
            button3.setOnClickListener {
                setResult(false)
                dialog.dismiss()
            }
            title.text = HtmlCompat.fromHtml(
                getString(
                    R.string.dhizuku_permission_warning_template,
                    label,
                    getString(R.string.permission_group_description)
                )
            )
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(false)
            .setOnDismissListener {
                setResult(false)
                finish()
            }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    override fun onPause() {
        super.onPause()
        // 与上游授权对话框一致：离开页面即视为拒绝
        setResult(false)
        finish()
    }

    private fun setResult(granted: Boolean) {
        if (resultSent) return
        resultSent = true
        if (granted) {
            DhizukuSettings.grant(uid)
        }
        // oneway 回调，客户端拿到授权结果后自行决定是否重连
        val callback = listener ?: return
        try {
            val data = android.os.Parcel.obtain()
            data.writeInterfaceToken("com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener")
            data.writeInt(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            callback.transact(IBinder.FIRST_CALL_TRANSACTION + 0, data, null, IBinder.FLAG_ONEWAY)
            data.recycle()
        } catch (t: Throwable) {
            LOGGER.w("Dhizuku: request permission callback failed: ${t.message}")
        }
    }

    override fun finish() {
        super.finish()
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
    }
}
