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
 * adb shell dpm set-device-owner com.churan.shizako/moe.shizuku.manager.dhizuku.DhizukuAdminReceiver
 * ```
 *
 * 注意组件名要用**真实类名** `moe.shizuku.manager.dhizuku.DhizukuAdminReceiver`：
 * manifest 里的 `.dhizuku.DhizukuAdminReceiver` 是相对 namespace（`moe.shizuku.manager`）
 * 解析的，不是相对 applicationId。拼错的话 `dpm set-device-owner` 会报 Unknown admin，
 * 授权应用的 `setDelegatedScopes` 也会抛 SecurityException（issue #13）。
 *
 * 不想再要 Device Owner 时，在应用内「Dhizuku 应用」页点「取消设备所有者」即可
 * （App 自己调 `clearDeviceOwnerApp`）；`adb shell dpm remove-active-admin` 对
 * 正式包这种非 testOnly 的 admin 无效，会抛 SecurityException（issue #12）。
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
