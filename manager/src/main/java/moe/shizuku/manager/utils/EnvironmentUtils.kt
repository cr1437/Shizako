package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemProperties
import moe.shizuku.manager.ShizukuSettings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object EnvironmentUtils {

    @JvmStatic
    fun isWatch(context: Context): Boolean {
        return (context.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_WATCH)
    }

    fun isRooted(): Boolean {
        return System.getenv("PATH")?.split(File.pathSeparatorChar)?.find { File("$it/su").exists() } != null
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        return port
    }

    /** 本机回环上这个 TCP 端口是否已经活着（照搬 Shevery 的 TCP 模式判断）。 */
    fun isAdbPortLive(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 250)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 找一个“已经活着”的 ADB TCP 端口：系统属性 > 上次启动用的端口 > 默认 5555。
     * TCP 模式下它就是“免网络直连”的入口。
     */
    fun getLiveAdbTcpPort(): Int {
        val configuredPort = getAdbTcpPort()
        val lastPort = ShizukuSettings.getLastAdbPort()
        val candidates = sequenceOf(configuredPort, lastPort, 5555)
            .filter { it > 0 }
            .distinct()
        return candidates.firstOrNull { isAdbPortLive(it) } ?: -1
    }
}