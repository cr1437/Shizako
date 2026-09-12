package moe.shizuku.manager.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 网络可用性判断。
 *
 * 用途：**没网的时候也要能用** —— 更新检查、下载这类"锦上添花"的功能应当
 * 立刻返回一句人话，而不是卡在 15 秒超时之后才报错，更不该在启动时弹一堆失败提示。
 *
 * 判断不出来的情况（拿不到 ConnectivityManager、权限异常）**一律当成有网**：
 * 宁可让它去试一次失败，也不要因为判断失败把功能整个关掉。
 */
object NetworkUtils {

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return true

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = manager.activeNetwork ?: return false
                val caps = manager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                manager.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Throwable) {
            true
        }
    }
}
