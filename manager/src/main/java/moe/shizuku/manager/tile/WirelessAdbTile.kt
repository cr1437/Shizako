package moe.shizuku.manager.tile

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import moe.shizuku.manager.R

/**
 * Quick settings tile that toggles wireless debugging (adb over Wi-Fi).
 *
 * Only usable when Shizako holds WRITE_SECURE_SETTINGS, granted once via
 * `adb shell pm grant com.churan.shizako android.permission.WRITE_SECURE_SETTINGS`
 * (the same precondition as starting on boot). Enabling through this tile
 * also disables the auto-timeout, so wireless debugging survives network
 * changes, matching the boot start behavior.
 */
class WirelessAdbTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (!isAvailable()) return

        val cr = contentResolver
        val enabled = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        if (enabled) {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
        } else {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        }
        updateTile()
    }

    private fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false // no wireless debugging below Android 11
        return checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (!isAvailable()) {
            tile.state = Tile.STATE_UNAVAILABLE
        } else {
            val enabled = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_wireless_adb_subtitle)
            }
        }
        tile.updateTile()
    }
}
