package moe.shizuku.manager.diag

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 诊断日志录制（**只用于 `-PlogBuild` 的排查包，不随正式版发布**）。
 *
 * 为什么要它：反馈「配对连不上」时，最常见的情况是用户拿不到 logcat ——
 * 要么没电脑，要么 `adb logcat -s <tag>` 过滤错了什么也看不到。
 * 这里把日志直接录进 App：录完在首页导出成文件，或者一键复制，贴到 Discussion 里就行。
 *
 * 两个日志来源：
 * 1. **Shizuku shell（root / adb）**：`logcat` 以 shell 身份跑，能拿到**全系统**日志，
 *    包括 adbd、system_server 那些关键行（排查配对时就靠它们）。
 * 2. **自身进程**：Shizuku 没起来时退回 `ProcessBuilder("logcat")`。
 *    Android 4.1 起应用只能读到**自己 uid** 的日志，但 App 自己的诊断行都在里面，仍然有用。
 *
 * 容量是有上限的：内存里只留最近 [MAX_MEMORY_LINES] 行，磁盘文件超过 [MAX_FILE_BYTES]
 * 就砍掉前一半。录日志这件事本身不能把设备撑爆。
 */
object DiagLog {

    private const val TAG = "DiagLog"

    /** 内存里保留的行数（导出时以此为主，够贴一次反馈）。 */
    private const val MAX_MEMORY_LINES = 8000

    /** 落盘文件上限：超过就砍掉前一半，保留最近的。 */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private const val FILE_NAME = "diag-log.txt"
    private const val DIR_NAME = "diag"

    /** 是否正在录制。Compose 可观察，首页卡片据此显示状态。 */
    var recording by mutableStateOf(false)
        private set

    /** 已录到的行数（用于卡片上显示「已录 N 行」）。 */
    var lineCount by mutableIntStateOf(0)
        private set

    /** 录制来源，显示在卡片上：`shizuku`（全系统）/ `app`（仅本应用）。 */
    var source by mutableStateOf("starting")
        private set

    private val memory = ArrayDeque<String>()
    private val lock = Any()

    private var thread: Thread? = null
    private var process: Process? = null
    private var remote: moe.shizuku.server.IRemoteProcess? = null
    private var stream: java.io.InputStream? = null

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun fileOf(context: Context): File = File(dir(context), FILE_NAME)

    /**
     * 开始录制。重复调用安全（已经在录就直接返回）。
     *
     * @param systemWide 为 true 时优先走 Shizuku shell 拿全系统日志；
     *                   拿不到会自动退回只录本进程。
     */
    fun start(context: Context, systemWide: Boolean = true) {
        synchronized(lock) {
            if (recording) return
            recording = true
            val appContext = context.applicationContext
            val worker = Thread({ captureLoop(appContext, systemWide) }, "shizako-diag-log")
            worker.isDaemon = true
            thread = worker
            worker.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            recording = false
            runCatching { process?.destroy() }
            runCatching { remote?.destroy() }
            runCatching { stream?.close() }
            process = null
            remote = null
            stream = null
            thread = null
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            memory.clear()
            lineCount = 0
            runCatching { fileOf(context).delete() }
        }
    }

    /** 当前缓冲区内容（最近的日志），用于复制 / 导出。 */
    fun snapshot(): String = synchronized(lock) { memory.joinToString("\n") }

    /**
     * 把当前日志写成一个可分享的文件（放在 FileProvider 覆盖的目录里）。
     *
     * @return 写好的文件；失败返回 null。
     */
    fun exportTo(context: Context): File? = runCatching {
        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Download")
            .apply { if (!exists()) mkdirs() }
        val name = "shizako-diag-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".txt"
        val file = File(outDir, name)
        val header = buildString {
            appendLine("# Shizako diagnostics")
            appendLine("# app: " + context.packageName)
            appendLine("# recorded: " + lineCount + " lines, source=" + source)
            appendLine("# device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " / Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")")
            appendLine()
        }
        file.writeText(header + snapshot())
        file
    }.onFailure { Log.w(TAG, "export failed", it) }.getOrNull()

    // ---------------- 内部：抓取循环 ----------------

    private fun captureLoop(context: Context, systemWide: Boolean) {
        // 落盘文件在这里绑定一次，pump() 只管写
        currentFile = fileOf(context)
        try {
            if (systemWide && startShizukuSource()) {
                source = "shizuku (system-wide)"
            } else {
                startAppSource()
                source = "app (this process only)"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "capture failed", e)
            source = "failed: ${e.javaClass.simpleName}"
        } finally {
            recording = false
        }
    }

    /** 走 Shizuku shell 起 `logcat`：能拿到全系统日志。返回 false 表示没法用。 */
    private fun startShizukuSource(): Boolean {
        val binder = if (Shizuku.pingBinder()) runCatching { Shizuku.getBinder() }.getOrNull() else null
        if (binder == null) return false
        return runCatching {
            val service = IShizukuService.Stub.asInterface(binder)
            val proc = service.newProcess(
                arrayOf("logcat", "-v", "threadtime", "-T", "1"),
                null,
                null,
            )
            remote = proc
            stream = ParcelFileDescriptor.AutoCloseInputStream(proc.getInputStream())
            pump(stream!!)
            true
        }.onFailure { Log.w(TAG, "shizuku logcat unavailable", it) }.getOrDefault(false)
    }

    /** 退回普通模式下读自己进程的日志。 */
    private fun startAppSource() {
        val proc = ProcessBuilder("logcat", "-v", "threadtime", "-T", "1")
            .redirectErrorStream(true)
            .start()
        process = proc
        stream = proc.inputStream
        pump(proc.inputStream)
    }

    /** 把流按行读进内存与文件，直到流结束或停止录制。 */
    private fun pump(input: java.io.InputStream) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val file = currentFile
            while (recording) {
                val line = reader.readLine() ?: break
                synchronized(lock) {
                    memory.addLast(line)
                    while (memory.size > MAX_MEMORY_LINES) memory.removeFirst()
                    lineCount++
                    if (file != null) appendToFile(file, line)
                }
            }
        }
    }

    /**
     * 文件句柄：放在这里而不是每次 append 都 open/close。
     * 由 [captureLoop] 所在线程独占写，外部只读。
     */
    private var currentFile: File? = null

    private fun appendToFile(file: File, line: String) {
        runCatching {
            if (currentFile !== file) currentFile = file
            file.appendText(line + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                // 砍掉前一半，保留最近的日志
                val text = file.readText()
                file.writeText(text.substring(text.length / 2))
            }
        }
    }
}
