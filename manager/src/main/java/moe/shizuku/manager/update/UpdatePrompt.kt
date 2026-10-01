package moe.shizuku.manager.update

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.fdroid.FdroidBuild
import moe.shizuku.manager.ui.glass.GlassWindow

/**
 * 「发现新版本」提示 + 下载进度。
 *
 * 抽成一处，是因为有两处要弹同一个东西：
 * 1. 设置页手动点「检查更新」；
 * 2. **每次打开 App** 的自动检查（以前这里只在通知栏丢一条提醒，
 *    用户明明开着 App 却看不到能直接下载的入口）。
 *
 * 两块 UI 完全共用，避免再出现「同一件事两套实现、行为还不一致」的老问题。
 */
object UpdatePrompt {

    /** 弹「发现新版本」对话框；点「下载」进入带进度的下载流程。 */
    fun show(context: Context, info: UpdateChecker.ReleaseInfo) {
        // 兜底：F-Droid 版不该有任何更新提示（入口都已关闭），这里再挡一层
        if (!FdroidBuild.allowSelfUpdate) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available_title)
            .setMessage(context.getString(R.string.update_dialog_message, info.tagName, info.body))
            .setPositiveButton(R.string.update_download) { _, _ -> downloadWithProgress(context, info) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    /** 下载并安装，全程显示进度对话框（用户取消时同时取消下载）。 */
    fun downloadWithProgress(context: Context, info: UpdateChecker.ReleaseInfo) {
        if (!FdroidBuild.allowSelfUpdate) return
        val progressDialog = DownloadProgressDialog.show(
            context,
            title = context.getString(R.string.update_downloading),
            version = info.tagName,
            onCancel = { UpdateChecker.cancelDownload() },
        )
        progressDialog.setState(context.getString(R.string.update_connecting))
        UpdateChecker.downloadAndInstall(context, info, object : UpdateChecker.DownloadListener {
            override fun onProgress(downloaded: Long, total: Long, speedBps: Long) {
                if (progressDialog.isShowing) progressDialog.update(downloaded, total, speedBps)
            }

            override fun onRetry(attempt: Int, max: Int) {
                if (progressDialog.isShowing) {
                    progressDialog.setState(context.getString(R.string.update_retrying, attempt, max))
                }
            }

            override fun onComplete(apkFile: java.io.File) {
                progressDialog.dismiss()
            }

            override fun onFailed(reason: String) {
                progressDialog.dismiss()
            }

            override fun onCancelled() {
                progressDialog.dismiss()
                Toast.makeText(context, R.string.update_download_cancelled, Toast.LENGTH_SHORT).show()
            }
        })
    }
}
