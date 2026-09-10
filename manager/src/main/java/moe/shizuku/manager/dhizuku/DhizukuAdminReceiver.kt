package moe.shizuku.manager.dhizuku

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import moe.shizuku.manager.R

/**
 * Shizako 的 Device Owner 激活入口。
 *
 * 激活方式（电脑上执行一次）：
 * ```
 * adb shell dpm set-device-owner com.churan.shizako/.dhizuku.DhizukuAdminReceiver
 * ```
 *
 * 激活后 Shizako 以 Device Owner 身份替已授权应用转发系统级 binder，
 * 无需 Root、无需常驻无线调试。
 */
class DhizukuAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_enabled, Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_disabled, Toast.LENGTH_SHORT).show()
    }
}
