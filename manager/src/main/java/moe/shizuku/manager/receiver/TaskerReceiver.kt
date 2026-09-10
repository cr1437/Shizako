package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import rikka.shizuku.Shizuku

/**
 * Broadcast receiver for automation apps (Tasker, MacroDroid, etc.).
 *
 * Supported actions:
 *   com.churan.shizako.action.START            — start with the last-used launch mode
 *   com.churan.shizako.action.STOP             — stop the Shizuku service
 *   com.churan.shizako.action.START_ROOT       — start with root
 *   com.churan.shizako.action.START_ADB        — start with ADB (wireless debugging)
 *   com.churan.shizako.action.ACTIVATE_DHIZUKU — activate Device Owner mode
 *   com.churan.shizako.action.QUERY_STATUS     — reply with extras running/dhizuku
 *   com.churan.shizako.action.NOTIFY_INSTALL   — post a notification that opens
 *                                                the latest APK in Download/ when
 *                                                tapped (one tap → installer)
 *
 * All actions are exported so Tasker can send intents to them directly.
 */
class TaskerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START = "com.churan.shizako.action.START"
        const val ACTION_STOP = "com.churan.shizako.action.STOP"
        const val ACTION_START_ROOT = "com.churan.shizako.action.START_ROOT"
        const val ACTION_START_ADB = "com.churan.shizako.action.START_ADB"
        const val ACTION_ACTIVATE_DHIZUKU = "com.churan.shizako.action.ACTIVATE_DHIZUKU"
        const val ACTION_QUERY_STATUS = "com.churan.shizako.action.QUERY_STATUS"
        const val ACTION_NOTIFY_INSTALL = "com.churan.shizako.action.NOTIFY_INSTALL"
        const val ACTION_DOWNLOAD_UPDATE = "com.churan.shizako.action.DOWNLOAD_UPDATE"

        private val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_START -> {
                    if (Shizuku.pingBinder()) {
                        showToast(context, R.string.tasker_already_running)
                        return
                    }
                    when (ShizukuSettings.getLastLaunchMode()) {
                        ShizukuSettings.LaunchMethod.ROOT -> {
                            ServiceStartHelper.startRoot { ok ->
                                if (ok) showToast(context, R.string.tasker_started_root)
                                else showToast(context, R.string.tasker_start_failed)
                            }
                        }
                        ShizukuSettings.LaunchMethod.ADB -> {
                            if (ServiceStartHelper.canAdbAutoStart(context)) {
                                ServiceStartHelper.startAdb(context)
                                showToast(context, R.string.tasker_started_adb)
                            } else {
                                showToast(context, R.string.tasker_adb_unavailable)
                            }
                        }
                        else -> showToast(context, R.string.tasker_no_launch_mode)
                    }
                }
                ACTION_STOP -> {
                    if (!Shizuku.pingBinder()) {
                        showToast(context, R.string.tasker_not_running)
                        return
                    }
                    try {
                        Shizuku.exit()
                        showToast(context, R.string.tasker_stopped)
                    } catch (e: Exception) {
                        showToast(context, R.string.tasker_stop_failed)
                    }
                }
                ACTION_START_ROOT -> {
                    if (Shizuku.pingBinder()) {
                        showToast(context, R.string.tasker_already_running)
                        return
                    }
                    ServiceStartHelper.startRoot { ok ->
                        if (ok) showToast(context, R.string.tasker_started_root)
                        else showToast(context, R.string.tasker_start_failed)
                    }
                }
                ACTION_START_ADB -> {
                    if (Shizuku.pingBinder()) {
                        showToast(context, R.string.tasker_already_running)
                        return
                    }
                    if (ServiceStartHelper.canAdbAutoStart(context)) {
                        ServiceStartHelper.startAdb(context)
                        showToast(context, R.string.tasker_started_adb)
                    } else {
                        showToast(context, R.string.tasker_adb_unavailable)
                    }
                }
                ACTION_ACTIVATE_DHIZUKU -> {
                    val active = DhizukuSettings.isDeviceOwner(context)
                    if (active) {
                        showToast(context, R.string.tasker_dhizuku_already)
                        return
                    }
                    if (!Shizuku.pingBinder()) {
                        showToast(context, R.string.tasker_dhizuku_not_running)
                        return
                    }
                    Thread {
                        val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
                        mainHandler.post {
                            if (result.success || DhizukuSettings.isDeviceOwner(context)) {
                                showToast(context, R.string.tasker_dhizuku_success)
                            } else {
                                showToast(context, R.string.tasker_dhizuku_failed)
                            }
                        }
                    }.start()
                }
                ACTION_QUERY_STATUS -> {
                    val result = Bundle().apply {
                        putBoolean("running", Shizuku.pingBinder())
                        putBoolean("dhizuku", DhizukuSettings.isDeviceOwner(context))
                    }
                    resultCode = if (Shizuku.pingBinder()) 1 else 0
                    setResultExtras(result)
                }
                ACTION_NOTIFY_INSTALL -> {
                    notifyInstall(context)
                }
                ACTION_DOWNLOAD_UPDATE -> {
                    val url = intent.getStringExtra("url")
                    if (url.isNullOrBlank() || !url.startsWith("http")) {
                        showToast(context, R.string.tasker_download_bad_url)
                        return
                    }
                    moe.shizuku.manager.update.UpdateChecker.downloadFromUrl(
                        context, url, "shizako-update.apk"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "TaskerReceiver: ${e.message}", e)
        }
    }

    private fun showToast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * 发一条可点击的通知：点击直接用系统安装器打开 Download/ 里最新的 APK。
     * 推送流程的 adb 侧只需 am broadcast 本 action。
     */
    private fun notifyInstall(context: Context) {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val apk = dir.listFiles { f ->
            f.isFile && f.name.startsWith("shizako-") && f.name.endsWith(".apk")
        }?.maxByOrNull { it.lastModified() }

        if (apk == null) {
            showToast(context, R.string.tasker_install_apk_missing)
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "shizako_update"
        val channel = android.app.NotificationChannel(
            channelId,
            context.getString(R.string.notify_install_channel),
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(channel)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".update_file_provider",
            apk
        )
        // 通知点击 → 中转 Activity（前台拉起安装器，授权更可靠）
        val installIntent = android.content.Intent(context, moe.shizuku.manager.update.InstallApkActivity::class.java)
            .putExtra("apk_path", apk.absolutePath)
        val pending = android.app.PendingIntent.getActivity(
            context,
            2002,
            installIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notify_install_title))
            .setContentText(context.getString(R.string.notify_install_text, apk.name))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(2002, notification)
        } catch (t: Throwable) {
            Log.w(AppConstants.TAG, "notifyInstall: ${t.message}", t)
            showToast(context, R.string.tasker_install_apk_missing)
        }
    }
}