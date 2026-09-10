package moe.shizuku.manager.activation

import android.os.ParcelFileDescriptor
import android.text.TextUtils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
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
     * Executes `command` with `sh -c` on the server (shell uid for adb start,
     * root uid for root start) and collects stdout + stderr.
     */
    fun run(command: String, timeoutSeconds: Int = 90): Result {
        val service = service() ?: return Result(-1, "", false, IllegalStateException("service not running"))

        return try {
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = StringBuilder()

            val stdoutThread = readStreamAsync(process.outputStream, output)
            val stderrThread = readStreamAsync(process.errorStream, output)

            var timedOut = false
            var exit = -1
            val exited = try {
                process.waitForTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS.name)
            } catch (e: Exception) {
                try {
                    process.waitFor()
                    true
                } catch (e2: Exception) {
                    false
                }
            }
            if (exited) {
                exit = try {
                    process.exitValue()
                } catch (e: Exception) {
                    -1
                }
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

    private fun readStreamAsync(fd: ParcelFileDescriptor?, output: StringBuilder): Thread {
        val thread = Thread {
            fd ?: return@Thread
            try {
                BufferedReader(InputStreamReader(FileInputStream(fd.fileDescriptor))).use { reader ->
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
                try {
                    fd.close()
                } catch (e: Throwable) {
                }
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