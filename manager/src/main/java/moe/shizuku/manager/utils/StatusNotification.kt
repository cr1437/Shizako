package moe.shizuku.manager.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

/**
 * Persistent "Shizako is running" status notification with the character icon.
 *
 * Shizako keeps a persistent status notification so the user knows the service is running.
 * whenever the server binder arrives, and cancels it when the binder dies.
 */
object StatusNotification {

    private const val CHANNEL_ID = "status"

    // AdbPairingService uses id 1 (AppConstants.NOTIFICATION_ID_STATUS) for its
    // interactive pairing notifications, so use a dedicated id to never replace them.
    private const val NOTIFICATION_ID = 3

    fun install(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_status),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }

        Shizuku.addBinderReceivedListenerSticky {
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val title = context.getString(
                R.string.home_status_service_is_running,
                context.getString(R.string.app_name)
            )

            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            val notification = builder
                .setSmallIcon(R.drawable.ic_system_icon)
                .setColor(context.getColor(R.color.notification))
                .setContentTitle(title)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build()

            try {
                nm.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                // posting not allowed (e.g. notification permission not granted yet)
            }
        }

        Shizuku.addBinderDeadListener {
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
