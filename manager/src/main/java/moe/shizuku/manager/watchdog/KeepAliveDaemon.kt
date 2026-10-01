package moe.shizuku.manager.watchdog

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.utils.EnvironmentUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 「用 ADB 保活」：在 shell 侧驻一个极小的守护脚本（走本机 ADB 5555 通道 spawn）。
 *
 * 键盘安全设计（v2）：
 * - 拉活 / 唤醒优先走 exported 的 KeepAliveBridgeService（`am start-service`）：
 *   不启动任何界面、不抢焦点、不影响输入法（Activity 仅作最后兜底）；
 * - App 被杀 → 下一轮（约 12 秒内）拉起；
 * - App 被系统冻结 → 通过「服务投递」尝试解冻；
 * - 每 ~36 秒一次无感「保温」轻推，尽量让局域网服务保持可连。
 * shell 身份发起，不受后台启动限制；想停掉时删掉 flag 文件即可（脚本每轮自检）。
 */
object KeepAliveDaemon {

    private const val FLAG_NAME = "keepalive.on"
    private const val PID_NAME = "keepalive.pid"
    private const val HEARTBEAT_NAME = "keepalive.hb"
    private const val HEARTBEAT_FRESH_MS = 120_000L

    private fun dir(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir)

    fun flagFile(context: Context) = File(dir(context), FLAG_NAME)

    private fun pidFile(context: Context) = File(dir(context), PID_NAME)

    private fun heartbeatFile(context: Context) = File(dir(context), HEARTBEAT_NAME)

    /** 打标记：守护脚本看到它才活着；删掉即退出（用户「杀后台」时用）。 */
    fun enable(context: Context) {
        runCatching { flagFile(context).writeText("1") }
    }

    /** 撤标记（脚本拉回逻辑每轮都复查 flag，会自行退出并清理）。 */
    fun disable(context: Context) {
        runCatching { flagFile(context).delete() }
        runCatching { pidFile(context).delete() }
        runCatching { heartbeatFile(context).delete() }
    }

    /** 心跳新鲜 = 守护脚本还在岗。 */
    fun isRunning(context: Context): Boolean {
        return runCatching {
            val hb = heartbeatFile(context).readText().trim().toLongOrNull() ?: return false
            val now = System.currentTimeMillis() / 1000
            (now - hb) * 1000 < HEARTBEAT_FRESH_MS
        }.getOrDefault(false)
    }

    fun ensureRunningAsync(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ensureRunning(appContext) }
        }
    }

    fun ensureRunning(context: Context): Boolean {
        if (!ShizukuSettings.isWatchdogEnabled()) return false
        enable(context)
        if (isRunning(context)) return true
        return spawn(context)
    }

    private fun spawn(context: Context): Boolean {
        val flag = flagFile(context).absolutePath
        val pid = pidFile(context).absolutePath
        val hb = heartbeatFile(context).absolutePath

        // 注意：内层脚本里不要出现单引号（外层用单引号包裹）；$ 相关交给内层运行时求值。
        val bridge = "com.churan.shizako/moe.shizuku.manager.watchdog.KeepAliveBridgeService"
        val trampoline = "com.churan.shizako/moe.shizuku.manager.watchdog.KeepAliveTrampolineActivity"
        val pokeService = "/system/bin/am start-service -n $bridge >/dev/null 2>&1 || /system/bin/am startservice -n $bridge >/dev/null 2>&1"

        // 注意：内层脚本里不要出现单引号（外层用单引号包裹）；$ 相关交给内层运行时求值。
        //
        // ★ 频率是耗电的关键，别随手调小：每轮要 fork `date` / `pidof` / `grep` 各一次，
        //   并往闪存写一次心跳。原来 sleep 12 —— 一天约 3.6 万次 fork + 7200 次闪存写入；
        //   改成 45 秒后降到约 1/4（心跳新鲜度阈值见 HEARTBEAT_FRESH_MS，必须同步放大）。
        // ★ 同时去掉了「每 3 轮保温轻推」：那是给已剥离的局域网功能保持可连用的，
        //   现在只剩副作用 —— 每 36 秒 `am start-service` 一次，把前台服务和通知反复刷一遍。
        //   现在只在「进程不在」或「进程被冻结（D/T 态）」时才推。
        val inner = "if [ -f $pid ] && kill -0 \$(cat $pid) 2>/dev/null; then exit 0; fi; " +
                "echo \$\$ > $pid; date +%s > $hb; " +
                "while [ -f $flag ]; do sleep 45; date +%s > $hb; " +
                "P=\$(pidof com.churan.shizako); " +
                "if [ -z \"\$P\" ]; then " +
                "$pokeService; sleep 8; " +
                "P=\$(pidof com.churan.shizako); " +
                "if [ -z \"\$P\" ]; then /system/bin/am start -n $trampoline >/dev/null 2>&1; sleep 8; fi; " +
                "else " +
                "S=\$(grep -m1 ^State: /proc/\$P/status 2>/dev/null); " +
                "case \"\$S\" in *D*|*T*) $pokeService ;; esac; " +
                "fi; done; rm -f $pid $hb"

        val cmdline = "nohup sh -c '$inner' </dev/null >/dev/null 2>&1 & echo SPAWNED"

        // ① 首选：本机 ADB 5555（「用 ADB 保活」正路子；spawn 出的进程归 shell）
        if (EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT)) {
            val ok = runCatching {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                AdbClient("127.0.0.1", AdbStarter.TCP_MODE_PORT, key).use { client ->
                    client.connect()
                    val out = ByteArrayOutputStream()
                    client.command("shell:$cmdline") { chunk ->
                        if (out.size() < 4096) out.write(chunk)
                    }
                    out.toString().contains("SPAWNED")
                }
            }.getOrDefault(false)
            if (ok) return true
        }

        // ② 兜底：Root（有 su 的话，直接以 root 驻守）
        val rooted = runCatching { EnvironmentUtils.isRooted() }.getOrDefault(false)
        if (rooted) {
            val ok = runCatching {
                com.topjohnwu.superuser.Shell.cmd(cmdline).exec()
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        return false
    }
}