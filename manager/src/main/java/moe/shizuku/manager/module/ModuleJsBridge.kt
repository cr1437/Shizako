package moe.shizuku.manager.module

import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * 模块本地 WebUI 的 shell 桥（照搬 Shevery 的 `ModuleJsBridge`，
 * 策略门换成 Shizako 自己的 [ModuleSettings]）。
 *
 * 页面里拿到的是 Activity 注入的 `window.Shizuku`（同一份对象的原生侧，
 * 接口名叫 `ShizukuNative`）：
 * ```
 * Shizuku.exec("id")                -> { ok, code, exitCode, stdout, stderr, timedOut }
 * Shizuku.exec("ls", {cwd:"bin"})   -> 同上（可带 cwd / env / stdin / timeoutSeconds）
 * Shizuku.download(url, "file.zip") -> { ok, bytes, path }
 * Shizuku.moduleId / mode / trusted -> 只读属性
 * ```
 *
 * **安全设计（为什么这么写）**：
 * - 命令一律走 Shizuku 的 `IShizukuService.newProcess`，因此权限跟着 Shizuku 的身份
 *   （ADB 启动 = shell，root 启动 = root），桥自己不额外提权；
 * - 只有 `module.enabled` 且 [ModuleSettings.canUseShellBridge] 为真才注入（策略在 Activity 里判），
 *   这里再判一次是纵深防御：万一以后有人绕过 Activity 直接 new 这个桥，也不会漏出去；
 * - 每次调用都校验"主文档还停在模块自己的 WebUI 上"（虚拟域名），
 *   防止页面被导航到外站后仍然拿得到桥；
 * - [ModuleSettings.needsCommandReview] 为真时必须先弹确认框（ReCommand），
 *   拒绝就直接返回错误，命令**不执行**；
 * - 硬限额（和 [AdbModuleManager] 保持一致）：每条流只留最后 64KB、默认/最长 120s、
 *   超时退出码 124 —— 这些**不受** Full Trust 影响。
 *
 * 已知限制：`addJavascriptInterface` 会把对象暴露给 WebView 里**所有** frame，
 * 所以当模块 WebUI 里嵌了外站 iframe 时，那个 iframe 也能调到这里的方法。
 * 缓解手段是上面的"主文档域名校验 + ReCommand 确认框"（确认框会把命令原文给用户看），
 * 上游 Shevery 也是同样的取舍。
 */
class ModuleJsBridge(
    private val module: AdbModule,
    /**
     * "调用方还站在模块自己的 WebUI 上吗"。
     * 由 Activity 在 UI 线程维护当前主文档 URL 后回调，避免跨线程读 `WebView.url`（会抛异常）。
     */
    private val originAllowed: () -> Boolean,
    /** ReCommand 确认框；为 null 表示没人能确认 —— 那就按"拒绝"处理，不执行命令 */
    private val commandReviewer: CommandReviewer? = null,
) {

    /** 命令确认回调：必须阻塞到用户选完再返回 */
    fun interface CommandReviewer {
        fun confirm(command: String): Boolean
    }

    // ---------------- 对页面暴露的 JS 接口 ----------------

    /** 模块信息 + 当前策略下的各项权限，页面可以用它决定显示什么按钮 */
    @JavascriptInterface
    fun getModuleInfo(): String {
        if (!originAllowed()) return "{}"
        return JSONObject().apply {
            put("ok", true)
            put("id", module.id)
            put("name", module.name)
            put("version", module.version ?: "")
            put("versionCode", module.versionCode ?: 0)
            put("author", module.author ?: "")
            put("enabled", module.enabled)
            put("moduleDir", module.directory.absolutePath)
            put("accessMode", ModuleSettings.getAccessMode().value)
            put("trusted", ModuleSettings.isModuleTrusted(module.id))
            put("background", ModuleSettings.canRunBackground(module))
            put(
                "permissions",
                JSONObject().apply {
                    put("action", ModuleSettings.canRunAction(module))
                    put("service", ModuleSettings.canRunService(module))
                    put("webBridge", ModuleSettings.canUseShellBridge(module))
                    put("webInternet", ModuleSettings.canWebViewInternet(module))
                    put("webDownload", ModuleSettings.canWebUiDownload(module))
                    put("declaresShellBridge", module.declaresShellBridge)
                    put("commandReview", ModuleSettings.needsCommandReview(module))
                },
            )
        }.toString()
    }

    /**
     * 执行一条 shell 命令。
     * @return JSON 字符串（跨 JS 边界只能传字符串），结构见 [shellResult]/[shellError]。
     *         同时给了 `code` 和 `exitCode` 两个同名字段，兼容两种写法。
     */
    @JavascriptInterface
    fun exec(command: String): String {
        val denied = bridgeDeniedReason()
        if (denied != null) return shellError(denied)
        return execInternal(command, ExecOptions())
    }

    /** 带选项的 exec（cwd / env / stdin / timeoutSeconds），选项解析失败就当参数非法拒绝 */
    @JavascriptInterface
    fun execWithOptions(command: String, optionsJson: String): String {
        val denied = bridgeDeniedReason()
        if (denied != null) return shellError(denied)
        return try {
            execInternal(command, parseExecOptions(optionsJson))
        } catch (e: Exception) {
            shellError(e.message ?: "Invalid exec options.")
        }
    }

    /**
     * 从网上下载一个文件到 WebUI 根目录里的相对路径。
     * 只有 [ModuleSettings.canWebUiDownload] 放行时才可能成功；只走 HTTPS（模块文件多半是要被执行的脚本，
     * 明文 HTTP 会被中间人改包）。
     */
    @JavascriptInterface
    fun download(url: String, relativeWebPath: String): String {
        val denied = bridgeDeniedReason()
        if (denied != null) return downloadResult(url, relativeWebPath, null, denied)
        val result = try {
            require(module.webRoot?.isDirectory == true) { "Module has no WebUI root." }
            require(ModuleSettings.canWebUiDownload(module)) {
                "Permission denied: WebUI download is blocked by the module access policy."
            }
            val outFile = resolveWebFile(relativeWebPath)
            downloadHttpsToFile(url, outFile)
        } catch (e: Exception) {
            return downloadResult(url, relativeWebPath, null, e.message ?: "Download failed.")
        }
        return downloadResult(url, relativeWebPath, result, null)
    }

    // ---------------- 内部实现 ----------------

    /** 桥的准入检查：任何一条不满足都直接拒绝 */
    private fun bridgeDeniedReason(): String? {
        if (!originAllowed()) return "Permission denied: origin mismatch."
        if (!module.enabled) return "Module is disabled."
        if (!ModuleSettings.canUseShellBridge(module)) {
            return "Permission denied: module.prop must declare usesShellBridge=true and the access policy must allow the WebUI bridge."
        }
        return null
    }

    private fun execInternal(command: String, options: ExecOptions): String {
        if (command.isBlank()) return shellError("Command is blank.")

        // ReCommand：把命令原文交给用户看，没批准就不执行
        if (ModuleSettings.needsCommandReview(module)) {
            val approved = commandReviewer?.confirm(command) ?: false
            if (!approved) return shellError("Command rejected by ReCommand.")
        }

        val binder = Shizuku.getBinder() ?: return shellError("Shizuku service is not running.")

        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val env = buildEnvironment(options.extraEnv)
            val cwd = resolveModuleDirectory(options.cwd).absolutePath
            val remote = service.newProcess(arrayOf("sh", "-c", command), env, cwd)

            val stdoutPfd = remote.getInputStream()
            val stderrPfd = remote.getErrorStream()
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread {
                try {
                    stdout = readStreamTail(stdoutPfd)
                } catch (_: Exception) {
                }
            }
            val stderrThread = Thread {
                try {
                    stderr = readStreamTail(stderrPfd)
                } catch (_: Exception) {
                }
            }
            stdoutThread.start()
            stderrThread.start()

            // 把 stdin 写完就关掉，否则脚本里 `read` 之类的会一直等
            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).use { output ->
                if (options.stdin.isNotEmpty()) {
                    output.write(options.stdin.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }

            val finished = remote.waitForTimeout(options.timeoutSeconds, TimeUnit.SECONDS.name)
            val exitCode = if (finished) {
                remote.exitValue()
            } else {
                // 超时：杀掉远端进程，退出码用 timeout(1) 的约定值 124
                remote.destroy()
                try { stdoutPfd.close() } catch (_: Exception) {}
                try { stderrPfd.close() } catch (_: Exception) {}
                EXIT_TIMEOUT
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)

            shellResult(exitCode, stdout, stderr, !finished)
        } catch (e: Exception) {
            shellError(e.message ?: "Unknown error")
        }
    }

    /**
     * 命令环境变量：和 Shevery 文档一致（`$MODDIR` 指向模块自己的目录，
     * 模块作者不该写死 Magisk/KSU 的路径）。
     */
    private fun buildEnvironment(extraEnv: Map<String, String>): Array<String> {
        val binDir = AdbModuleManager.ensureSuShim(moe.shizuku.manager.application)
        val env = linkedMapOf(
            "MODDIR" to module.directory.absolutePath,
            "MODPATH" to module.directory.absolutePath,
            "ASH_STANDALONE" to "1",
            "SHIZUKU_MODULE_ID" to module.id,
            "SHIZUKU_MODULE_MODE" to ModuleSettings.getAccessMode().value,
            "SHIZUKU_MODULE_TRUSTED" to if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0",
            "SHIZUKU_MODULE_BACKGROUND" to if (ModuleSettings.canRunBackground(module)) "1" else "0",
            "ARCH" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"),
            "PATH" to "${binDir.absolutePath}:/product/bin:/apex/com.android.runtime/bin:" +
                "/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:" +
                "/vendor/bin:/vendor/xbin:/sbin:/data/adb/apatch:/data/adb/ksu/bin",
        )
        extraEnv.forEach { (key, value) -> env[key] = value }
        return env.map { (key, value) -> "$key=$value" }.toTypedArray()
    }

    private fun parseExecOptions(raw: String): ExecOptions {
        if (raw.isBlank() || raw == "undefined" || raw == "null") return ExecOptions()

        val json = JSONObject(raw)
        val timeout = json.optLong("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        val stdin = json.optString("stdin", "")
        require(stdin.length <= MAX_STDIN_CHARS) { "stdin is too large." }

        val cwd = if (json.has("cwd")) json.optString("cwd", "") else ""
        val extraEnv = linkedMapOf<String, String>()
        json.optJSONObject("env")?.let { envJson ->
            val keys = envJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(ENV_KEY_REGEX.matches(key)) { "Invalid env key: $key" }
                require(extraEnv.size < MAX_EXTRA_ENV_COUNT) { "Too many environment variables." }
                val value = envJson.optString(key, "")
                require(value.length <= MAX_ENV_VALUE_CHARS) { "Environment value is too large: $key" }
                extraEnv[key] = value
            }
        }
        return ExecOptions(timeout, stdin, cwd, extraEnv)
    }

    /**
     * 只保留最后 [MAX_OUTPUT_CHARS] 个字符：边读边丢头部，
     * 既不会因为长输出把内存吃掉，也不会卡住管道的另一端。
     */
    private fun readStreamTail(fd: ParcelFileDescriptor): String {
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            val tail = StringBuilder()
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                tail.append(buffer, 0, read)
                if (tail.length > MAX_OUTPUT_CHARS * 2) {
                    tail.delete(0, tail.length - MAX_OUTPUT_CHARS)
                }
            }
            if (tail.length > MAX_OUTPUT_CHARS) tail.substring(tail.length - MAX_OUTPUT_CHARS) else tail.toString()
        }
    }

    /** cwd 只允许指向模块目录内部 */
    private fun resolveModuleDirectory(relativePath: String): File {
        if (relativePath.isBlank() || relativePath == ".") return module.directory
        val clean = cleanRelativePath(relativePath)
        val directory = File(module.directory, clean)
        ensureInside(module.directory, directory)
        require(directory.isDirectory) { "cwd is not a directory: $relativePath" }
        return directory
    }

    /** 下载目标只允许落在 WebUI 根目录内部，且默认不许覆盖入口文件 */
    private fun resolveWebFile(relativeWebPath: String): File {
        val root = module.webRoot ?: error("Module has no WebUI root.")
        val clean = cleanRelativePath(relativeWebPath)
        if (!ModuleSettings.isModuleTrusted(module.id)) {
            require(!clean.equals("index.html", ignoreCase = true) && !clean.endsWith("/index.html", ignoreCase = true)) {
                "download() cannot overwrite WebUI entry files."
            }
        }
        val file = File(root, clean)
        ensureInside(root, file)
        require(!file.isDirectory) { "Destination is a directory." }
        return file
    }

    private fun cleanRelativePath(path: String): String {
        val clean = path.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Path is blank." }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe path: $path" }
        require(!clean.contains('\u0000')) { "Unsafe path: $path" }
        return clean
    }

    private fun ensureInside(root: File, file: File) {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) { "Path escapes the module directory." }
    }

    private fun shellResult(exitCode: Int, stdout: String, stderr: String, timedOut: Boolean): String =
        JSONObject().apply {
            put("ok", !timedOut && exitCode == 0)
            put("code", exitCode)
            put("exitCode", exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
            put("timedOut", timedOut)
        }.toString()

    private fun shellError(message: String): String = shellResult(EXIT_ERROR, "", message, false)

    private fun downloadResult(url: String, path: String, bytes: Long?, error: String?): String =
        JSONObject().apply {
            put("ok", error == null)
            put("url", url)
            put("path", path)
            if (error == null) put("bytes", bytes ?: 0L) else put("error", error)
        }.toString()

    private data class ExecOptions(
        val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        val stdin: String = "",
        val cwd: String = "",
        val extraEnv: Map<String, String> = emptyMap(),
    )

    companion object {
        /** 桥自己报错时的退出码（区别于 shell 的退出码） */
        const val EXIT_ERROR = -1

        /** 超时退出码，沿用 timeout(1) 的约定（和 AdbModuleManager 一致） */
        const val EXIT_TIMEOUT = 124

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val MIN_TIMEOUT_SECONDS = 1L

        /** 和 [AdbModuleManager] 的脚本超时保持一致：120s */
        private const val MAX_TIMEOUT_SECONDS = 120L

        /** 每条流最多回给页面 64KB（和 AdbModuleManager 一致） */
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private const val MAX_STDIN_CHARS = 64 * 1024
        private const val MAX_EXTRA_ENV_COUNT = 32
        private const val MAX_ENV_VALUE_CHARS = 4096

        /** 下载上限 20MB：WebUI 下载不该变成搬运大文件的通道 */
        const val MAX_DOWNLOAD_BYTES = 20L * 1024L * 1024L
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5

        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )

        /**
         * 只允许 HTTPS 的下载实现（手动跟重定向，限制跳转次数与体积）。
         * Activity 的 DownloadListener 也复用它，避免两处各写一份。
         */
        fun downloadHttpsToFile(rawUrl: String, outFile: File): Long {
            var current = parseHttpsUrl(rawUrl)
            var redirects = 0

            while (true) {
                val connection = (current.openConnection() as HttpsURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = NETWORK_TIMEOUT_MS
                    readTimeout = NETWORK_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Shizako-Module-WebUI/1.0")
                }
                try {
                    val code = connection.responseCode
                    if (code in REDIRECT_CODES) {
                        redirects++
                        require(redirects <= MAX_REDIRECTS) { "Too many redirects." }
                        val location = connection.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirect without a Location header.")
                        current = parseHttpsUrl(URL(current, location).toString())
                        continue
                    }
                    require(code in 200..299) { "HTTP $code while downloading $current" }

                    val parent = outFile.parentFile ?: error("Destination has no parent directory.")
                    parent.mkdirs()
                    val tmp = File.createTempFile("download-", ".tmp", parent)
                    try {
                        var total = 0L
                        BufferedInputStream(connection.inputStream).use { input ->
                            tmp.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    total += read.toLong()
                                    require(total <= MAX_DOWNLOAD_BYTES) { "Downloaded file is too large." }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        if (outFile.exists()) outFile.delete()
                        check(tmp.renameTo(outFile)) { "Unable to save the downloaded file." }
                        return total
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }

        private fun parseHttpsUrl(raw: String): URL {
            val url = URL(raw.trim())
            require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS downloads are allowed." }
            require(!url.host.isNullOrBlank()) { "URL host is blank." }
            return url
        }
    }
}
