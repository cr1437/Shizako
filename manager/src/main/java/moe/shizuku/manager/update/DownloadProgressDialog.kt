package moe.shizuku.manager.update

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import java.util.Locale

/**
 * In-app download progress dialog.
 *
 * ProgressDialog is deprecated since API 26; the recommended replacement is a
 * regular AlertDialog with a determinate horizontal ProgressBar. See
 * https://developer.android.com/reference/android/app/ProgressDialog
 *
 * All [update] / [setState] calls must be made from the main thread.
 */
class DownloadProgressDialog private constructor(
    private val context: Context,
    private val dialog: Dialog
) {

    private val versionText: TextView = dialog.findViewById(R.id.download_version)!!
    private val progressBar: ProgressBar = dialog.findViewById(R.id.download_progress_bar)!!
    private val detailText: TextView = dialog.findViewById(R.id.download_detail)!!
    private val speedText: TextView = dialog.findViewById(R.id.download_speed)!!

    var isShowing: Boolean = dialog.isShowing
        private set

    fun interface OnCancelListener {
        fun onCancel()
    }

    companion object {
        fun show(
            context: Context,
            title: String,
            version: String,
            onCancel: OnCancelListener
        ): DownloadProgressDialog {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_download_progress, null)
            view.findViewById<TextView>(R.id.download_version).text = version

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { d, _ ->
                    d.dismiss()
                    onCancel.onCancel()
                }
                .show()

            return DownloadProgressDialog(context, dialog)
        }
    }

    /** Determinate progress: [downloaded] and [total] in bytes, [speedBps] bytes per second (<= 0 to hide). */
    fun update(downloaded: Long, total: Long, speedBps: Long) {
        if (!dialog.isShowing) return

        val safeTotal = if (total > 0) total else downloaded
        val pct = if (safeTotal > 0) (downloaded * 100 / safeTotal).toInt() else 0

        progressBar.isIndeterminate = false
        progressBar.progress = pct.coerceIn(0, 100)

        detailText.text = context.getString(
            R.string.update_downloading_detail,
            formatSize(downloaded), formatSize(safeTotal), pct.coerceIn(0, 100)
        )
        speedText.text = if (speedBps > 0) {
            context.getString(R.string.update_speed, formatSize(speedBps))
        } else {
            ""
        }
    }

    /** Indeterminate state: connecting / retrying. */
    fun setState(text: String) {
        if (!dialog.isShowing) return

        progressBar.isIndeterminate = true
        detailText.text = text
        speedText.text = ""
    }

    fun dismiss() {
        try {
            dialog.dismiss()
        } catch (_: Exception) {
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
