package moe.shizuku.manager.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

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

    /**
     * 取本机在局域网里的 IPv4 地址（拿不到返回 null）。
     *
     * 用途：配对时作为回环地址的**备选**。adbd 的配对服务会把地址显示成
     * `192.168.x.x:端口`，回环通常也能连，但个别设备/ROM 上应用连不上自己的
     * `127.0.0.1`，这时用这个地址就能连上。
     *
     * 只挑「已启用、非回环」的接口，且排除 `169.254.x.x`（链路本地，没有意义）
     * 与 `0.0.0.0`。任何异常都当作拿不到 —— 调用方会退回只用回环。
     */
    fun findLanIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.map { it.hostAddress }
                ?.filterNotNull()
                ?.firstOrNull { it != "0.0.0.0" && !it.startsWith("169.254.") }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 取本机**所有**可作为局域网目标的 IPv4 地址（Wi-Fi 接口优先，去重）。
     *
     * 为什么需要多个：配对兜底要连 adbd 的配对端口，而多网卡设备上「第一个」地址
     * 不一定在无线调试所在的那张网卡上（模拟器就是 eth0=10.0.2.15、wlan0=10.0.2.16）。
     * 逐个试才能真正兜住。拿不到就返回空列表。
     */
    fun findAllLanIpv4(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.sortedByDescending { it.name.startsWith("wlan") }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.mapNotNull { it.hostAddress }
                ?.filter { it != "0.0.0.0" && !it.startsWith("169.254.") }
                ?.distinct()
                ?.toList()
                ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
