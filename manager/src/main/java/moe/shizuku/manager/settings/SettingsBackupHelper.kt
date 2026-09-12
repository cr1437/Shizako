package moe.shizuku.manager.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.BackupRestoreUtil

/**
 * 设置备份 / 恢复的**选择器注册**（实验室功能里那两个按钮用）。
 *
 * 原来只写在 [SettingsFragment] 里，现在实验室功能也能从工具箱直接打开，
 * 两边各写一份迟早会漂移 —— 统一放这里，谁要用谁注册。
 *
 * 注意：`registerForActivityResult` 必须在 Fragment 初始化阶段调用（STARTED 之前），
 * 所以这两个函数要在字段初始化时用，不能等到点按钮才调。
 */
object SettingsBackupHelper {

    /** 「备份设置」：选一个文件写 JSON */
    fun registerBackup(fragment: Fragment): ActivityResultLauncher<String> =
        fragment.registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = fragment.requireContext()
            Thread {
                val json = kotlinx.coroutines.runBlocking { BackupRestoreUtil.backup(context) }
                val ok = try {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Throwable) {
                    false
                }
                fragment.activity?.runOnUiThread {
                    Toast.makeText(
                        context,
                        if (ok) R.string.backup_done else R.string.backup_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.start()
        }

    /** 「恢复设置」：选一个 JSON 覆盖当前设置 */
    fun registerRestore(fragment: Fragment): ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = fragment.requireContext()
            Thread {
                val ok = try {
                    val json = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    kotlinx.coroutines.runBlocking { BackupRestoreUtil.restore(context, json) }
                } catch (e: Throwable) {
                    false
                }
                fragment.activity?.runOnUiThread {
                    Toast.makeText(
                        context,
                        if (ok) R.string.restore_done else R.string.restore_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.start()
        }

    /** 简单记一下：恢复完要重启应用才完全生效（实验室页里也是这么写的） */
    fun notifyRestoreNeedsRestart(context: Context, text: CharSequence) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
