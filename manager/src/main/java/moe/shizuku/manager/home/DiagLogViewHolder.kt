package moe.shizuku.manager.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeDiagLogBinding
import moe.shizuku.manager.diag.DiagLog
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * 首页的「诊断日志」卡 —— **只出现在 `-PlogBuild` 的排查包里**。
 *
 * 目的很具体：让反馈者不需要电脑、不需要记 logcat 命令，也能把完整日志交出来。
 * 插件式开关（[DiagLog]）默认在 App 启动时就已经打开，这张卡负责三件事：
 * 显示「录了多少」，把日志导出成文件（走系统分享），或者直接复制到剪贴板。
 */
class DiagLogViewHolder(
    private val binding: HomeDiagLogBinding,
    root: View,
) : BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val binding = HomeDiagLogBinding.inflate(inflater, parent, false)
            DiagLogViewHolder(binding, binding.root)
        }
    }

    /** 正在用代码同步开关位置，避免和监听器互相触发。 */
    private var syncing = false

    init {
        binding.diagSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            if (checked) DiagLog.start(itemView.context) else DiagLog.stop()
            sync()
        }
        binding.diagExport.setOnClickListener { shareLog() }
        binding.diagCopy.setOnClickListener { copyLog() }
    }

    override fun onBind() {
        sync()
    }

    private fun sync() {
        syncing = true
        binding.diagSwitch.isChecked = DiagLog.recording
        syncing = false

        val context = itemView.context
        binding.text2.text = when {
            DiagLog.recording && DiagLog.lineCount > 0 ->
                context.getString(R.string.diag_log_summary_recording, DiagLog.lineCount, DiagLog.source)
            DiagLog.recording ->
                context.getString(R.string.diag_log_summary_starting)
            DiagLog.lineCount > 0 ->
                context.getString(R.string.diag_log_summary_stopped, DiagLog.lineCount)
            else ->
                context.getString(R.string.diag_log_summary_idle)
        }
    }

    /** 导出：写成文件后走系统分享面板（能直接发邮件 / 发聊天，也能存到文件管理器）。 */
    private fun shareLog() {
        val context = itemView.context
        val file = DiagLog.exportTo(context)
        if (file == null) {
            Toast.makeText(context, R.string.diag_log_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".update_file_provider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Shizako diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.diag_log_export)))
        }.onFailure {
            Toast.makeText(context, R.string.diag_log_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 复制：整段日志进剪贴板，直接粘贴到 Discussion / 邮件里。 */
    private fun copyLog() {
        val context = itemView.context
        val text = DiagLog.snapshot()
        if (text.isBlank()) {
            Toast.makeText(context, R.string.diag_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Shizako diagnostics", text))
        Toast.makeText(context, R.string.diag_log_copied, Toast.LENGTH_SHORT).show()
    }
}
