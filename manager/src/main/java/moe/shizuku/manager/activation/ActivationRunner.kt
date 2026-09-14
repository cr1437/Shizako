package moe.shizuku.manager.activation

import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Runs activation commands on the device through the running Shizaku server.
 *
 * The manager app is exempted from the server's permission enforcement
 * (checkCallerManagerPermission in the server matches the manager uid), so no
 * user-facing authorization is needed for these calls.
 */
object ActivationRunner {

    class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val error: Throwable? = null
    ) {
        val success: Boolean
            get() = error == null && !timedOut && exitCode == 0
    }

    private fun service(): IShizukuService? {
        val binder = Shizuku.getBinder() ?: return null
        return IShizukuService.Stub.asInterface(binder)
    }

    /**
     * 服务身份进程的三根管道。
     *
     * 直接用 AIDL 的 [IRemoteProcess]：它给的 `ParcelFileDescriptor` 就是服务进程的
     * 管道末端，包一层 Java 流即可读写。**不要**去 cast `rikka.shizuku.ShizukuRemoteProcess`
     * —— AIDL 方法返回的是 binder 代理（`IRemoteProcess.Stub.Proxy`），不是那个包装类，
     * `as?` 会静默变成 null（踩过一次：报 `exit=-1`）。
     *
     * [stdin] 正是把 App 里的文件送进「服务身份才能写」的目录的唯一通路。
     */
    private class ProcessHandle(
        private val remote: IRemoteProcess,
        private val pid: Int,
    ) {
        fun openStdin(): OutputStream {
            val fd = remote.outputStream ?: error("process has no stdin pipe")
            return ParcelFileDescriptor.AutoCloseOutputStream(fd.dup())
        }

        fun openStdout(): InputStream {
            val fd = remote.inputStream ?: error("process has no stdout pipe")
            return ParcelFileDescriptor.AutoCloseInputStream(fd.dup())
        }

        fun openStderr(): InputStream {
            val fd = remote.errorStream ?: error("process has no stderr pipe")
            return ParcelFileDescriptor.AutoCloseInputStream(fd.dup())
        }

        fun waitForTimeout(seconds: Int): Boolean = try {
            remote.waitForTimeout(seconds.toLong(), TimeUnit.SECONDS.name)
        } catch (e: Exception) {
            remote.waitFor() >= 0
        }

        fun exitCode(): Int = try {
            remote.exitValue()
        } catch (e: Exception) {
            -1
        }

        fun destroy() {
            runCatching { remote.destroy() }
        }

        override fun toString(): String = "pid=$pid"
    }

    /** 起一个服务身份的 `sh -c` 进程（shell uid 或 root uid）。 */
    private fun startProcess(command: String): ProcessHandle? {
        val service = service() ?: return null
        val remote = try {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Throwable) {
            Log.w("ActivationRunner", "newProcess failed", e)
            return null
        } ?: return null
        return ProcessHandle(remote, 0)
    }

    /**
     * Executes `command` with `sh -c` on the server (shell uid for adb start,
     * root uid for root start) and collects stdout + stderr.
     */
    fun run(command: String, timeoutSeconds: Int = 90): Result {
        val process = startProcess(command)
            ?: return Result(-1, "", false, IllegalStateException("service not running"))

        return try {
            val output = StringBuilder()

            val stdoutThread = readStreamAsync(process.openStdout(), output)
            val stderrThread = readStreamAsync(process.openStderr(), output)

            var timedOut = false
            var exit = -1
            val exited = process.waitForTimeout(timeoutSeconds)
            if (exited) {
                exit = process.exitCode()
            } else {
                timedOut = true
                process.destroy()
            }

            stdoutThread.join(3000)
            stderrThread.join(3000)

            Result(exit, output.toString().trim(), timedOut)
        } catch (e: Throwable) {
            Result(-1, "", false, e)
        }
    }

    /**
     * 跑一条命令，同时把 [stdin] 的内容喂给它的标准输入，返回结果（stdout + stderr 合并）。
     *
     * 这是把 App 里的文件送进「服务身份才能写」的目录的唯一可靠方式：
     * App 自己（普通应用 uid）既进不去 `/data/local/tmp` 的私有目录，也读不了自己的
     * `cacheDir`（那是服务进程要读的），而 `/sdcard/Android/data/...` 在 Android 11+
     * 连 shell 身份都会被 FUSE/ SELinux 挡住 —— 只有 shell 服务进程自己的标准输入是通的。
     */
    fun streamToServer(
        command: String,
        stdin: InputStream,
        timeoutSeconds: Int = 120,
    ): Result {
        val process = startProcess(command)
            ?: return Result(-1, "", false, IllegalStateException("service not running"))

        return try {
            val output = StringBuilder()

            val stdoutThread = readStreamAsync(process.openStdout(), output)
            val stderrThread = readStreamAsync(process.openStderr(), output)

            // 写 stdin 必须和读 stdout 并发，管道满了会互相死锁
            var writeError: Throwable? = null
            val writer = Thread {
                try {
                    stdin.use { input ->
                        process.openStdin().use { out -> copy(input, out) }
                    }
                } catch (e: Throwable) {
                    writeError = e
                }
            }
            writer.isDaemon = true
            writer.start()

            var timedOut = false
            var exit = -1
            val exited = process.waitForTimeout(timeoutSeconds)
            if (exited) {
                exit = process.exitCode()
            } else {
                timedOut = true
                process.destroy()
            }

            writer.join(5000)
            stdoutThread.join(3000)
            stderrThread.join(3000)

            if (writeError != null && output.isEmpty()) {
                Result(exit, "", timedOut, writeError)
            } else {
                Result(exit, output.toString().trim(), timedOut)
            }
        } catch (e: Throwable) {
            Log.w("ActivationRunner", "streamToServer failed: $command", e)
            runCatching { stdin.close() }
            Result(-1, "", false, e)
        }
    }

    /** 8KB 缓冲的复制（不用 Kotlin 的 `copyTo` 以免它用默认 8KB 之外的行为差异）。 */
    private fun copy(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        out.flush()
    }

    private fun readStreamAsync(source: InputStream?, output: StringBuilder): Thread {
        val thread = Thread {
            source ?: return@Thread
            try {
                BufferedReader(InputStreamReader(source)).use { reader ->
                    val local = StringBuilder()
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        local.append(line).append('\n')
                    }
                    synchronized(output) {
                        output.append(local)
                    }
                }
            } catch (e: Throwable) {
                // stream closed with the process
            } finally {
                runCatching { source.close() }
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Checks whether [packageName] is installed by running `pm list packages`
     * through the Shizaku server. This bypasses Android 11+ package visibility
     * restrictions that may cause [android.content.pm.PackageManager.getPackageInfo]
     * to miss packages.
     */
    fun isPackageInstalled(packageName: String): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (TextUtils.isEmpty(packageName)) return false
        val result = run("pm list packages $packageName", timeoutSeconds = 10)
        if (result.error != null) return false
        return result.output.contains("package:$packageName")
    }

    /**
     * Whether [packageName] currently holds the device owner role, detected
     * from `dumpsys device_policy`. Searches the entire output (not line-by-line)
     * because the format varies across Android versions and vendor ROMs.
     */
    fun isDeviceOwner(packageName: String): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (TextUtils.isEmpty(packageName)) return false
        val result = run("dumpsys device_policy", timeoutSeconds = 20)
        if (result.error != null) return false
        // Search the entire output for the package name in context of device/profile owner
        val output = result.output
        // Common patterns across Android versions:
        //   "Device Owner: ...packageName..."
        //   "mDeviceOwner=ComponentInfo{packageName/...}"
        //   "admin=ComponentInfo{packageName/...}"
        return output.contains(packageName) && (
            output.contains("Device Owner", ignoreCase = true) ||
            output.contains("Profile Owner", ignoreCase = true) ||
            output.contains("mDeviceOwner", ignoreCase = true)
        )
    }
}