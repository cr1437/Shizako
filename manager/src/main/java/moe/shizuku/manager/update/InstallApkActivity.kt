package moe.shizuku.manager.update

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import moe.shizuku.manager.R

/**
 * 通知点击后的安装中转：前台 activity 拿到 FileProvider URI 后
 * 直接拉起系统安装器（通知 PendingIntent 直接跨进程授权在部分
 * 国产 ROM 上失效，多一跳中转最可靠）。本页无 UI，随即 finish。
 */
class InstallApkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apk = dir.listFiles { f ->
            f.isFile && f.name.startsWith("shizako-") && f.name.endsWith(".apk")
        }?.maxByOrNull { it.lastModified() }

        if (apk == null) {
            Toast.makeText(this, R.string.tasker_install_apk_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.update_file_provider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, R.string.tasker_install_apk_missing, Toast.LENGTH_SHORT).show()
            }
        finish()
    }
}
