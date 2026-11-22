package moe.shizuku.manager.automation

import android.os.ParcelFileDescriptor
import android.util.Log
import com.topjohnwu.superuser.Shell
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 真正把命令跑起来的地方（规格 F4）。
 *
 * 两条特权通道，**都不新起 app_process**：
 * 1. Shizuku 在跑 → `IShizukuService.newProcess(["sh","-c",cmd])`。服务本身就是
 *    shell/root 身份，所以「Shizuku shell 身份」和「Root 模式 root 身份」是同一个调用，
 *    身份由服务启动方式决定（`ActivationRunner` 用的就是这个通道）。
 * 2. Shizuku 没跑但设备有 root → `su -c`。libsu 负责判断 root 是否可用（并让 root
 *    管理器弹一次授权），执行本身用普通进程，因为：
 *    - libsu 的 `Job.exec()` 会把输出**全量**收进 List（`dumpsys` 能到几 MB），
 *      而规格要求 64KB 截断，必须边读边丢；
 *    - libsu 没有超时 API，没法满足「超时终止进程」。
 *
 * 流处理有一条容易写错的地方：**超过 64KB 之后还要继续读、只是不存**。
 * 只把 buffer 截断而不消费管道，子进程写满 pipe 缓冲后会永远阻塞，
 * 表现为「命令明明很轻，却一直超时」。
 */
object AutomationExecutor {

    private const val TAG = "ShizakoAutomation"

    const val PRIVILEGE_SHIZUKU = "shizuku"
    const val PRIVILEGE_ROOT = "root"
    const val PRIVILEGE_NONE = "none"

    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        /** 错误码（[AutomationContract]），成功为 null */
        val error: String?,
        val durationMs: Long,
        val privilege: String,
        /** 给日志/note 的一句话说明（不包含命令原文） */
        val note: String? = null,
    )

    /** Shizuku 服务在跑吗（binder 活着就算）。 */
    fun hasShizuku(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /**
     * 有没有 root。**会阻塞**（libsu 首次调用要拉起 `su` 等用户/管理器授权），
     * 只能在后台线程调用。
     */
    fun hasRoot(): Boolean = try {
        Shell.getShell().isRoot
    } catch (t: Throwable) {
        Log.w(TAG, "root probe failed: ${t.javaClass.simpleName}")
        false
    }

    /**
     * 按规格的优先级挑身份：Shizuku 优先，其次 root。
     * 返回 [PRIVILEGE_NONE] 表示两条通道都不可用。
     */
    fun currentPrivilege(): String = when {
        hasShizuku() -> PRIVILEGE_SHIZUKU
        hasRoot() -> PRIVILEGE_ROOT
        else -> PRIVILEGE_NONE
    }

    /**
     * 执行一条已经通过白名单/元字符校验的命令。
     *
     * @param timeoutMs 已在 [AutomationContract.clampTimeout] 里夹取过
     */
    fun execute(command: String, timeoutMs: Int): Outcome {
        val started = System.currentTimeMillis()
        if (hasShizuku()) {
            return runShizuku(command, timeoutMs, started)
        }
        if (hasRoot()) {
            return runRoot(command, timeoutMs, started)
        }
        return Outcome(
            exitCode = -1,
            stdout = "",
            stderr = "",
            truncated = false,
            error = AutomationContract.ERR_NO_PRIVILEGE,
            durationMs = System.currentTimeMillis() - started,
            privilege = PRIVILEGE_NONE,
            note = "Shizuku 服务未运行，且本机没有可用的 root",
        )
    }

    // ══════════════ 通道 1：Shizuku ══════════════

    private fun runShizuku(command: String, timeoutMs: Int, started: Long): Outcome {
        val remote = try {
            val binder = Shizuku.getBinder()
                ?: return noService(started, PRIVILEGE_SHIZUKU)
            IShizukuService.Stub.asInterface(binder)
                .newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "newProcess failed", t)
            // 规格 §五：服务在半路断开一律算 NO_PRIVILEGE（不是笼统的 EXEC_FAILED）
            return Outcome(
                -1, "", "", false, AutomationContract.ERR_NO_PRIVILEGE,
                System.currentTimeMillis() - started, PRIVILEGE_SHIZUKU,
                note = "无法在服务端创建进程（服务可能刚断开）",
            )
        } ?: return noService(started, PRIVILEGE_SHIZUKU)

        return runRemote(remote, timeoutMs, started, PRIVILEGE_SHIZUKU)
    }

    private fun runRemote(
        remote: IRemoteProcess,
        timeoutMs: Int,
        started: Long,
        privilege: String,
    ): Outcome {
        // 管道读必须是线程：pipe 缓冲写满后子进程会阻塞在 write 上，
        // 单线程「先 waitFor 再读输出」会死锁（大输出的命令必现）。
        val (outThread, out) = startReader("automation-out") {
            remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it.dup()) }
        }
        val (errThread, err) = startReader("automation-err") {
            remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it.dup()) }
        }

        var timedOut = false
        var exit = -1
        try {
            // AIDL 只有秒级精度：向上取整，避免 500ms 被截成 0
            val seconds = ((timeoutMs + 999) / 1000).coerceAtLeast(1).toLong()
            val finished = try {
                remote.waitForTimeout(seconds, TimeUnit.SECONDS.name)
            } catch (t: Throwable) {
                // 老服务端/实现差异：退化成阻塞等待
                runCatching { remote.waitFor() }
                true
            }
            if (finished) {
                exit = runCatching { remote.exitValue() }.getOrDefault(-1)
            } else {
                timedOut = true
                runCatching { remote.destroy() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wait failed", t)
            runCatching { remote.destroy() }
            return Outcome(
                -1, out.text(), err.text(), out.truncated || err.truncated,
                AutomationContract.ERR_NO_PRIVILEGE,
                System.currentTimeMillis() - started, privilege,
                note = "等待进程结束时服务断开",
            )
        } finally {
            // 进程已结束/已销毁，读线程会在 EOF 退出；给 2s 收尾，别把结果卡在这里
            outThread.join(2000)
            errThread.join(2000)
        }

        return Outcome(
            exitCode = exit,
            stdout = out.text(),
            stderr = err.text(),
            truncated = out.truncated || err.truncated,
            error = if (timedOut) AutomationContract.ERR_TIMEOUT else null,
            durationMs = System.currentTimeMillis() - started,
            privilege = privilege,
            note = if (timedOut) "已超过 ${timeoutMs / 1000}s，进程被终止" else null,
        )
    }

    private fun noService(started: Long, privilege: String): Outcome = Outcome(
        -1, "", "", false, AutomationContract.ERR_NO_PRIVILEGE,
        System.currentTimeMillis() - started, privilege,
    )

    // ══════════════ 通道 2：root（su -c） ══════════════

    private fun runRoot(command: String, timeoutMs: Int, started: Long): Outcome {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (t: Throwable) {
            Log.w(TAG, "su spawn failed: ${t.javaClass.simpleName}")
            return Outcome(
                -1, "", "", false, AutomationContract.ERR_NO_PRIVILEGE,
                System.currentTimeMillis() - started, PRIVILEGE_ROOT,
                note = "无法启动 su",
            )
        }

        val out = CappedReader()
        val err = CappedReader()
        val outThread = Thread { out.drain(process.inputStream) }
            .apply { isDaemon = true; name = "automation-root-out"; start() }
        val errThread = Thread { err.drain(process.errorStream) }
            .apply { isDaemon = true; name = "automation-root-err"; start() }

        var timedOut = false
        var exit = -1
        try {
            if (process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                exit = process.exitValue()
            } else {
                timedOut = true
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "root wait failed", t)
            runCatching { process.destroyForcibly() }
            return Outcome(
                -1, out.text(), err.text(), out.truncated || err.truncated,
                AutomationContract.ERR_EXEC_FAILED,
                System.currentTimeMillis() - started, PRIVILEGE_ROOT,
            )
        } finally {
            outThread.join(2000)
            errThread.join(2000)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }

        return Outcome(
            exitCode = exit,
            stdout = out.text(),
            stderr = err.text(),
            truncated = out.truncated || err.truncated,
            error = if (timedOut) AutomationContract.ERR_TIMEOUT else null,
            durationMs = System.currentTimeMillis() - started,
            privilege = PRIVILEGE_ROOT,
            note = if (timedOut) "已超过 ${timeoutMs / 1000}s，进程被终止" else null,
        )
    }

    // ══════════════ 流读取 ══════════════

    private fun startReader(
        name: String,
        open: () -> InputStream?,
    ): Pair<Thread, CappedReader> {
        val reader = CappedReader()
        val thread = Thread {
            val stream = try {
                open()
            } catch (t: Throwable) {
                null
            }
            reader.drain(stream)
        }
        thread.isDaemon = true
        thread.name = name
        thread.start()
        return Pair(thread, reader)
    }

    /**
     * 带上限的读取器：累计到 [AutomationContract.MAX_STREAM_BYTES] 后只标记截断、
     * 继续把管道排空（否则子进程会阻塞）。
     */
    private class CappedReader {
        private val buffer = ByteArrayOutputStream()
        @Volatile
        var truncated: Boolean = false
            private set

        fun drain(stream: InputStream?) {
            stream ?: return
            try {
                stream.use { input ->
                    val chunk = ByteArray(16 * 1024)
                    val cap = AutomationContract.MAX_STREAM_BYTES
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        val room = cap - buffer.size()
                        if (room > 0) {
                            buffer.write(chunk, 0, minOf(room, n))
                        }
                        if (n > room) {
                            truncated = true
                        }
                    }
                }
            } catch (t: Throwable) {
                // 进程被 destroy 时流会抛 IOException，属正常收尾
            }
        }

        fun text(): String = try {
            AutomationPolicy.truncateUtf8(buffer.toByteArray(), AutomationContract.MAX_STREAM_BYTES)
        } catch (t: Throwable) {
            ""
        }
    }
}
