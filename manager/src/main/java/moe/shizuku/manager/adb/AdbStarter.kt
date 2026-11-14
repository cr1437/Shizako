package moe.shizuku.manager.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.watchdog.WatchdogService
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * ADB 启动器（照搬 Shevery 的「持久 TCP 模式」设计）。
 *
 * 普通无线调试的端口是随机、易变的，而且一旦网络变化 / 无线调试被系统关掉就全断。
 * 这里在启动的那一刻顺手把 adbd 切到固定端口 [TCP_MODE_PORT]（`tcpip:5555`），
 * 之后只需要连 `127.0.0.1:5555` —— 不需要网络、不需要重配对、无线调试也可以关掉，
 * 直到设备重启（adbd 回到默认状态）。
 */
object AdbStarter {

    /** TCP 模式固定端口：无线调试之外，再多一条走本机回环的“持久”通道。 */
    const val TCP_MODE_PORT = 5555

    /**
     * 启动服务。
     *
     * @param host 连接地址（本机回环即可：`127.0.0.1`）
     * @param port 当前可用的 ADB 端口；TCP 模式开启时会被换成 [TCP_MODE_PORT]
     * @param context 用于切换成功后关掉无线调试（没有权限时静默跳过）
     * @param listener 启动脚本输出回调
     * @param log 过程日志回调
     */
    suspend fun start(
        host: String = "127.0.0.1",
        port: Int,
        context: Context? = null,
        listener: ((ByteArray) -> Unit)? = null,
        log: ((String) -> Unit)? = null,
    ) {
        // 接下来 adbd 可能被我们重启，服务会“预期内死亡”——先给看门狗打个招呼。
        WatchdogService.expectDeathWindow()

        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        val tcpMode = ShizukuSettings.isTcpMode()

        var started = false
        try {
            // 先在**调用方给的端口**上把服务拉起来。
            //
            // 顺序是刻意的：固定 TCP 5555 只是「以后免网络直连」的可选优化，不是激活的
            // 前置条件。以前先 `tcpip:5555` 切端口、再连 5555 启动 —— 只要切换在这台设备上
            // 不成立（adbd 没重启成 / 无线传输被收掉），整次无线调试激活就跟着失败，
            // 而这时配对其实已经成功了，用户只能看到「服务没起来」。现在改成：先保证激活，
            // 再做优化；切换失败只记日志，不影响本次已经起来的服务。
            startOnPort(host, port, key, listener, log)
            started = true

            if (tcpMode && port != TCP_MODE_PORT) {
                if (EnvironmentUtils.isAdbPortLive(TCP_MODE_PORT)) {
                    log?.invoke("TCP port $TCP_MODE_PORT is already live")
                    ShizukuSettings.setLastAdbPort(TCP_MODE_PORT)
                } else {
                    log?.invoke("Switching ADB from port $port to TCP port $TCP_MODE_PORT...")
                    try {
                        switchToTcp(host, port, TCP_MODE_PORT, key)
                        ShizukuSettings.setLastAdbPort(TCP_MODE_PORT)
                    } catch (e: Throwable) {
                        // 服务已经在跑了，切换失败不影响本次激活；下次仍从无线端口启动。
                        log?.invoke("Switch to TCP failed: ${e.message}")
                    }
                }
            }
        } finally {
            // 只有「确实是 TCP 模式」且「这次真正启动成功」才关无线调试；
            // 失败时保持原样，用户可以直接重试（普通无线调试启动也不动系统开关）。
            if (tcpMode && started) {
                disableWirelessDebugging(context)
            }
        }
    }

    /**
     * 在指定端口上连一次 adbd 并跑启动脚本。
     *
     * 单独抽出来是为了让「先用当前可用端口启动、再做 TCP 切换」这条顺序读起来清楚：
     * 失败的判定只围绕这一次连接，跟后面的优化无关。
     */
    private suspend fun startOnPort(
        host: String,
        port: Int,
        key: AdbKey,
        listener: ((ByteArray) -> Unit)?,
        log: ((String) -> Unit)?,
    ) {
        log?.invoke("Connecting to ADB on port $port...")
        connectWithRetry(host, port, key) { client ->
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
            ShizukuSettings.setLastAdbPort(port)
            client.shellCommand(Starter.internalCommand, listener)
        }
    }

    /** 供“当前已有无线调试端口”时单独切换用（比如刚配对完想立刻切成 TCP 5555）。 */
    suspend fun switchToTcpMode(
        host: String = "127.0.0.1",
        currentPort: Int,
        targetPort: Int = TCP_MODE_PORT,
    ) {
        WatchdogService.expectDeathWindow()
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        switchToTcp(host, currentPort, targetPort, key)
    }

    private fun switchToTcp(host: String, currentPort: Int, targetPort: Int, key: AdbKey) {
        AdbClient(host, currentPort, key).use { client ->
            client.connect()
            try {
                client.command("tcpip:$targetPort")
            } catch (e: EOFException) {
                // 预期内：从无线调试切到 TCP 时 adbd 会重启，连接被掐断是正常的。
            } catch (e: SocketException) {
                // 同上。
            } catch (e: SocketTimeoutException) {
                // 同上（连接被掐断的另一种表现形式）。
            }
        }
    }

    private suspend fun connectWithRetry(
        host: String,
        port: Int,
        key: AdbKey,
        retries: Int = 8,
        block: (AdbClient) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(retries) { attempt ->
            try {
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    block(client)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbAuthPendingException) {
                // 密钥还没被信任：重试只会反复弹系统确认框，直接交给上层提示用户。
                throw e
            } catch (e: Throwable) {
                lastError = e
                if (attempt == retries - 1) throw e
                delay(500L * (attempt + 1))
            }
        }
        lastError?.let { throw it }
    }

    private fun disableWirelessDebugging(context: Context?) {
        val appContext = context?.applicationContext ?: return
        if (appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 0)
        }
    }
}