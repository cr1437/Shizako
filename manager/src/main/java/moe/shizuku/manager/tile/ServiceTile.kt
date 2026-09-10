package moe.shizuku.manager.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import rikka.shizuku.Shizuku

/**
 * Quick settings tile that starts/stops the Shizako service.
 *
 * Tap while running: stop the service. Tap while stopped: start it with the
 * last used launch method (root, or wireless debugging when auto start is
 * possible). If neither works without user interaction, open the main
 * activity instead so the user can start manually.
 */
class ServiceTile : TileService() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateTile() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { updateTile() }

    override fun onStartListening() {
        super.onStartListening()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onStopListening() {
        super.onStopListening()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (Shizuku.pingBinder()) {
            try {
                Shizuku.exit()
            } catch (_: Throwable) {
            }
            updateTile()
            return
        }

        when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> {
                ServiceStartHelper.startRoot { updateTile() }
            }
            ShizukuSettings.LaunchMethod.ADB -> {
                if (ServiceStartHelper.canAdbAutoStart(this)) {
                    ServiceStartHelper.startAdb(this) { updateTile() }
                } else {
                    openMainActivity()
                }
            }
            else -> openMainActivity()
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = Shizuku.pingBinder()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (running) R.string.tile_service_subtitle_running
                else R.string.tile_service_subtitle_stopped
            )
        }
        tile.updateTile()
    }
}
