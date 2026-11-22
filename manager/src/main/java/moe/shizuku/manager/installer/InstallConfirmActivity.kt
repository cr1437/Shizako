package moe.shizuku.manager.installer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity

/**
 * 「XX 应用请求安装 YY，是否允许？」确认框（规格 F2）。
 *
 * 为什么要一个独立 Activity 而不是在 [ApkInstallActivity] 里直接弹：后者用的是
 * `Theme.Translucent.NoTitleBar`（平台主题，没有 Material 属性），`MaterialAlertDialogBuilder`
 * 在那种上下文里建不出对话框；而静默安装路径又不该给用户看任何窗口（装大包要几分钟，
 * 一直压着一层浮动窗很难看）。所以「需要问」的时候才起这个 Activity，
 * 主题用 `GrantPermissions`（与授权弹窗一致），问完就 finish。
 */
class InstallConfirmActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callerLabel = intent.getStringExtra(EXTRA_CALLER_LABEL).orEmpty()
        val callerPackage = intent.getStringExtra(EXTRA_CALLER_PACKAGE).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val sizeText = intent.getStringExtra(EXTRA_SIZE_TEXT).orEmpty()
        val format = intent.getStringExtra(EXTRA_FORMAT).orEmpty()

        val message = buildString {
            append(getString(R.string.installer_confirm_message, callerLabel, fileName))
            append("\n\n")
            append(getString(R.string.installer_confirm_caller)).append(": ")
            if (callerPackage.isBlank()) append("?") else append(callerPackage)
            append("\n")
            append(getString(R.string.installer_confirm_format)).append(": ").append(format)
            if (sizeText.isNotBlank()) {
                append("\n")
                append(getString(R.string.installer_confirm_size)).append(": ").append(sizeText)
            }
            append("\n\n")
            append(getString(R.string.installer_confirm_hint))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.installer_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.installer_confirm_allow_once) { _, _ -> finishWith(DECISION_ONCE) }
            .setNeutralButton(R.string.installer_confirm_trust) { _, _ -> finishWith(DECISION_TRUST) }
            .setNegativeButton(R.string.installer_confirm_deny) { _, _ -> finishWith(DECISION_DENY) }
            .setOnCancelListener { finishWith(DECISION_DENY) }
            .create()
        // 点外面不算回答：外部应用可以连续拉起，误触关掉会被当成拒绝，反而更安全；
        // 但为了避免"点了没反应"的困惑，明确只有按钮/返回键能结束
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun finishWith(decision: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DECISION, decision))
        finish()
    }

    companion object {
        private const val EXTRA_CALLER_LABEL = "caller_label"
        private const val EXTRA_CALLER_PACKAGE = "caller_package"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_SIZE_TEXT = "size_text"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_DECISION = "decision"

        const val DECISION_ONCE = "once"
        const val DECISION_TRUST = "trust"
        const val DECISION_DENY = "deny"

        fun intent(
            context: Context,
            callerLabel: String,
            callerPackage: String?,
            fileName: String,
            sizeText: String,
            format: PackageFormat,
        ): Intent = Intent(context, InstallConfirmActivity::class.java)
            .putExtra(EXTRA_CALLER_LABEL, callerLabel)
            .putExtra(EXTRA_CALLER_PACKAGE, callerPackage)
            .putExtra(EXTRA_FILE_NAME, fileName)
            .putExtra(EXTRA_SIZE_TEXT, sizeText)
            .putExtra(EXTRA_FORMAT, format.name)

        /** 从返回的 Intent 里取决定；拿不到按拒绝处理（fail closed）。 */
        fun decisionOf(resultCode: Int, data: Intent?): String {
            if (resultCode != Activity.RESULT_OK || data == null) return DECISION_DENY
            return data.getStringExtra(EXTRA_DECISION) ?: DECISION_DENY
        }
    }
}
