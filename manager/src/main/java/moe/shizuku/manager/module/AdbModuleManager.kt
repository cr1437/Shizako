package moe.shizuku.manager.module

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * ADB Modules：真正的模块运行器（照搬 Shevery 的设计与限额）。
 *
 * - 安装：把 ZIP 解到 App 私有目录 `files/adb_modules/<id>`，解析 `module.prop`；
 * - 跑脚本：通过 Shizuku 的 `IShizukuService.newProcess` 以 shell/root 身份执行，
 *   带上 `MODDIR` / `SHIZUKU_MODULE_*` 等环境变量（脚本里别写死 Magisk 路径）；
 * - 启用状态：`disable` 标记文件（和 Magisk 同一约定）；
 * - 日志：`logs/action-last.log` / `logs/service-last.log`；
 * - 硬安全限制：路径穿越拒绝、条目数 ≤2048、解压总量 ≤200MB、
 *   脚本 ≤256KB、超时 120s（超时退出码 124）、每条流只留最后 64KB。
 */
object AdbModuleManager {

    private const val MODULES_DIR = "adb_modules"
    private const val DISABLE_FILE = "disable"
    private const val MAX_ENTRY_COUNT = 2048
    private const val MAX_EXTRACTED_BYTES = 200L * 1024L * 1024L
    private const val MAX_SCRIPT_SECONDS = 120L
    private const val MAX_OUTPUT_CHARS = 64 * 1024
    private const val MAX_SCRIPT_BYTES = 256 * 1024

    private val idRegex = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")
    private val installMutexes = ConcurrentHashMap<String, Mutex>()

    /** 一次 Shizuku binder 会话里，enabled 的 service.sh 只自动跑一次 */
    @Volatile
    private var servicesStartedForBinder = false

    /** 运行中的实时输出（Action 的流式执行） */
    private val _liveOutput = MutableStateFlow<LiveModuleOutput?>(null)
    val liveOutput: StateFlow<LiveModuleOutput?> = _liveOutput.asStateFlow()

    data class LiveModuleOutput(
        val moduleId: String,
        val moduleName: String,
        val text: String,
        val running: Boolean,
    )

    fun modulesRoot(context: Context): File =
        File(context.filesDir, MODULES_DIR).apply { mkdirs() }

    /** 清掉上次安装中断留下的 staging 目录 */
    fun cleanupStagingDirs(context: Context) {
        modulesRoot(context).listFiles { file ->
            file.isDirectory && file.name.startsWith(".") && file.name.endsWith(".installing")
        }?.forEach { it.deleteRecursively() }
    }

    // ---------------- 列表 / 安装 / 删除 ----------------

    suspend fun listModules(context: Context): List<AdbModule> = withContext(Dispatchers.IO) {
        modulesRoot(context)
            .listFiles { file -> file.isDirectory }
            ?.mapNotNull(::readModule)
            ?.sortedWith(compareBy<AdbModule> { !it.enabled }.thenBy { it.name.lowercase(Locale.ROOT) })
            .orEmpty()
    }

    /**
     * 从用户选的 ZIP 安装模块。整个过程在 IO 线程，
     * 先解到 `.&lt;id&gt;.installing` 再整体改名，避免半成品被当成已安装模块。
     */
    suspend fun install(context: Context, uri: Uri): AdbModule = withContext(Dispatchers.IO) {
        cleanupStagingDirs(context)
        val temp = File.createTempFile("module-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "打不开这个 ZIP。" }
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(temp).use { zip ->
                val propEntry = zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.trim('/') == "module.prop" }
                    ?: error("这个 ZIP 里没有 module.prop。")
                val props = zip.getInputStream(propEntry).use {
                    parseModuleProp(it.bufferedReader().readText())
                }
                val id = props["id"]?.trim().orEmpty()
                require(idRegex.matches(id)) { "模块 id 不合法：$id" }

                installMutexes.computeIfAbsent(id) { Mutex() }.withLock {
                    val target = File(modulesRoot(context), id)
                    val staging = File(modulesRoot(context), ".$id.installing")
                    staging.deleteRecursively()
                    staging.mkdirs()

                    var entryCount = 0
                    var extractedBytes = 0L
                    zip.entries().asSequence().forEach { entry ->
                        entryCount++
                        require(entryCount <= MAX_ENTRY_COUNT) { "ZIP 里文件太多（上限 $MAX_ENTRY_COUNT）。" }
                        val cleanName = cleanZipName(entry.name)
                        val outFile = File(staging, cleanName)
                        ensureInside(staging, outFile)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedInputStream(zip.getInputStream(entry)).use { input ->
                                outFile.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        extractedBytes += read.toLong()
                                        require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                            "ZIP 解压后太大（上限 200MB）。"
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }

                    markScriptsExecutable(staging, props["action"])
                    target.deleteRecursively()
                    check(staging.renameTo(target)) { "无法把模块移动到存储目录。" }
                    readModule(target) ?: error("装好了但读不出模块信息。")
                }
            }
        } finally {
            temp.delete()
        }
    }

    suspend fun setEnabled(module: AdbModule, enabled: Boolean) = withContext(Dispatchers.IO) {
        val marker = module.directory.resolve(DISABLE_FILE)
        if (enabled) marker.delete() else marker.writeText("disabled\n")
    }

    suspend fun delete(module: AdbModule) = withContext(Dispatchers.IO) {
        module.directory.deleteRecursively()
    }

    // ---------------- 执行 ----------------

    suspend fun runAction(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunAction(module)) { "策略不允许执行动作脚本。" }
        val script = module.actionScript?.takeIf { it.isFile }
            ?: error("这个模块没有动作脚本。")
        runModuleScript(module, script, module.lastActionLog, streaming = false)
    }

    suspend fun runActionStreaming(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunAction(module)) { "策略不允许执行动作脚本。" }
        val script = module.actionScript?.takeIf { it.isFile }
            ?: error("这个模块没有动作脚本。")
        runModuleScript(module, script, module.lastActionLog, streaming = true)
    }

    suspend fun runService(module: AdbModule): ModuleActionResult = withContext(Dispatchers.IO) {
        check(ModuleSettings.canRunService(module)) { "策略不允许跑 service.sh。" }
        check(ModuleSettings.canRunBackground(module)) { "后台动作没有开启。" }
        val script = module.serviceScript?.takeIf { it.isFile }
            ?: error("这个模块没有 service 脚本。")
        runModuleScript(module, script, module.lastServiceLog, streaming = false)
    }

    /**
     * 一次 binder 会话里自动跑一遍「启用 + 有 service.sh + 策略放行」的模块。
     * @return 每个模块的执行结果，供上层提示
     */
    suspend fun runEnabledServicesIfAllowed(context: Context): List<Pair<AdbModule, ModuleActionResult>> =
        withContext(Dispatchers.IO) {
            if (servicesStartedForBinder) return@withContext emptyList()
            if (!Shizuku.pingBinder()) return@withContext emptyList()

            servicesStartedForBinder = true
            listModules(context)
                .filter {
                    it.enabled && it.hasService &&
                        ModuleSettings.canRunService(it) && ModuleSettings.canRunBackground(it)
                }
                .map { module -> module to runService(module) }
        }

    fun resetServiceRunGuard() {
        servicesStartedForBinder = false
    }

    fun clearLiveOutput() {
        _liveOutput.value = null
    }

    private fun runModuleScript(
        module: AdbModule,
        script: File,
        logFile: File,
        streaming: Boolean,
    ): ModuleActionResult {
        check(module.enabled) { "模块已停用。" }
        module.logsDir.mkdirs()

        if (script.length() > MAX_SCRIPT_BYTES) {
            return ModuleActionResult(
                exitCode = -1,
                stdout = "",
                stderr = "脚本太大：${script.length()} 字节（上限 $MAX_SCRIPT_BYTES）",
            )
        }

        val scriptContent = try {
            script.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ModuleActionResult(-1, "", "读不出脚本：${e.message}")
        }

        val binder = Shizuku.getBinder() ?: error("Shizuku 服务没有运行。")
        val service = IShizukuService.Stub.asInterface(binder)
        val binDir = ensureSuShim(moe.shizuku.manager.application)

        if (streaming) {
            _liveOutput.value = LiveModuleOutput(
                moduleId = module.id,
                moduleName = module.name,
                text = "开始执行 ${script.name} …\n",
                running = true,
            )
        }

        val remote = service.newProcess(
            arrayOf("sh", "-c", scriptContent),
            moduleEnvironment(module, binDir),
            "/data/local/tmp",
        )

        ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close()
        val stdoutPfd = remote.getInputStream()
        val stderrPfd = remote.getErrorStream()

        val streamingBuffer = StringBuilder()
        var stdout = ""
        var stderr = ""

        val stdoutThread = Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd)
                    .bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(1024)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) break
                            synchronized(streamingBuffer) {
                                streamingBuffer.append(String(buffer, 0, read))
                                stdout = streamingBuffer.toString()
                            }
                            if (streaming) {
                                _liveOutput.value = LiveModuleOutput(
                                    moduleId = module.id,
                                    moduleName = module.name,
                                    text = synchronized(streamingBuffer) {
                                        streamingBuffer.toString().takeLast(MAX_OUTPUT_CHARS)
                                    },
                                    running = true,
                                )
                            }
                        }
                    }
            } catch (_: Exception) {
            }
        }
        val stderrThread = Thread {
            try {
                stderr = readStreamTail(ParcelFileDescriptor.AutoCloseInputStream(stderrPfd))
            } catch (_: Exception) {
            }
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = remote.waitForTimeout(MAX_SCRIPT_SECONDS, TimeUnit.SECONDS.name)
        val exitCode = if (finished) {
            remote.exitValue()
        } else {
            remote.destroy()
            try { stdoutPfd.close() } catch (_: Exception) {}
            try { stderrPfd.close() } catch (_: Exception) {}
            124 // 超时（和上游 / timeout(1) 一致）
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)

        val finalOut = if (streaming) synchronized(streamingBuffer) { streamingBuffer.toString() } else stdout
        if (streaming) {
            _liveOutput.value = LiveModuleOutput(
                moduleId = module.id,
                moduleName = module.name,
                text = finalOut.takeLast(MAX_OUTPUT_CHARS) + "\n\n--- 退出码：$exitCode ---",
                running = false,
            )
        }

        val result = ModuleActionResult(
            exitCode = exitCode,
            stdout = finalOut.takeLast(MAX_OUTPUT_CHARS),
            stderr = stderr.takeLast(MAX_OUTPUT_CHARS),
        )
        writeLastLog(logFile, module, script, result, finished)
        return result
    }

    /** 脚本环境变量：和 Shevery 文档一致（`$MODDIR` 是模块自己的目录） */
    private fun moduleEnvironment(module: AdbModule, binDir: File): Array<String> = arrayOf(
        "MODDIR=${module.directory.absolutePath}",
        "ASH_STANDALONE=1",
        "SHIZUKU_MODULE_ID=${module.id}",
        "SHIZUKU_MODULE_MODE=${ModuleSettings.getAccessMode().value}",
        "SHIZUKU_MODULE_TRUSTED=${if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"}",
        "SHIZUKU_MODULE_BACKGROUND=${if (ModuleSettings.canRunBackground(module)) "1" else "0"}",
        "MODPATH=${module.directory.absolutePath}",
        "ARCH=${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}",
        "PATH=${binDir.absolutePath}:/product/bin:/apex/com.android.runtime/bin:" +
            "/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:" +
            "/vendor/bin:/vendor/xbin:/sbin:/data/adb/apatch:/data/adb/kasu/bin",
    )

    /**
     * `files/bin/su` 垫片：模块脚本里经常直接调 `su`，
     * 在已经是 root 的情况下不该再套一层，因此这里判断 uid 后直接执行。
     */
    fun ensureSuShim(context: Context): File {
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val suFile = File(binDir, "su")
        val shim = """
            #!/system/bin/sh
            # Shizako 模块环境用的 su 垫片：已经是 root 就直接执行，否则转发给系统 su
            if [ "${'$'}(id -u)" -eq 0 ]; then
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        -c) shift; exec sh -c "${'$'}*" ;;
                        *) shift ;;
                    esac
                done
                exec sh
            else
                for p in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su /data/adb/ksu/bin/su /data/adb/apatch/su; do
                    if [ -x "${'$'}p" ]; then
                        exec "${'$'}p" "${'$'}@"
                    fi
                done
                echo "su 不可用：当前不是 root，也找不到系统 su" >&2
                exit 127
            fi
        """.trimIndent() + "\n"
        if (!suFile.isFile || suFile.readText() != shim) {
            suFile.writeText(shim)
        }
        suFile.setExecutable(true, false)
        suFile.setReadable(true, false)
        return binDir
    }

    // ---------------- module.prop / 目录解析 ----------------

    fun readModule(directory: File): AdbModule? {
        val propsFile = directory.resolve("module.prop")
        if (!propsFile.isFile) return null
        val props = try {
            parseModuleProp(propsFile.readText())
        } catch (e: Exception) {
            return null
        }
        val id = props["id"]?.takeIf { idRegex.matches(it) } ?: return null
        val name = props["name"]?.takeIf { it.isNotBlank() } ?: id
        return AdbModule(
            id = id,
            name = name,
            version = props["version"]?.takeIf { it.isNotBlank() },
            versionCode = props["versionCode"]?.toLongOrNull(),
            author = props["author"]?.takeIf { it.isNotBlank() },
            description = props["description"]?.takeIf { it.isNotBlank() },
            directory = directory,
            banner = findFirstExisting(
                directory,
                props["banner"],
                "banner.png",
                "banner.jpg",
                "banner.jpeg",
                "banner.webp",
            ),
            webRoot = findFirstExisting(directory, props["webui"], "webroot", "webui", "web")
                ?.takeIf { it.isDirectory },
            declaresShellBridge = props["usesShellBridge"]?.toBooleanStrictOrNull()
                ?: props["shellBridge"]?.toBooleanStrictOrNull()
                ?: false,
            actionScript = findFirstExisting(
                directory,
                props["action"],
                "action.sh",
                "run.sh",
                "main.sh",
                "exec.sh",
            ),
            serviceScript = findFirstExisting(directory, "service.sh", "late_start.sh"),
            logsDir = directory.resolve("logs"),
            sizeBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            enabled = !directory.resolve(DISABLE_FILE).exists(),
            url = props["url"]?.takeIf { it.isNotBlank() }
                ?: props["github"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseModuleProp(raw: String): Map<String, String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .associate {
            val index = it.indexOf('=')
            it.substring(0, index).trim() to it.substring(index + 1).trim()
        }

    private fun findFirstExisting(root: File, vararg names: String?): File? {
        for (name in names) {
            if (name.isNullOrBlank()) continue
            val file = File(root, name)
            if (file.exists()) return file
        }
        return null
    }

    private fun markScriptsExecutable(root: File, actionProp: String?) {
        val candidates = listOfNotNull(
            actionProp,
            "action.sh", "run.sh", "main.sh", "exec.sh", "service.sh", "late_start.sh",
        )
        for (name in candidates) {
            val file = File(root, name)
            if (file.isFile) file.setExecutable(true, false)
        }
    }

    private fun cleanZipName(name: String): String {
        val clean = name.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "ZIP 里有非法条目。" }
        require(!clean.startsWith("/") && !clean.contains("../") && clean != "..") {
            "ZIP 条目试图跳出目录：$name"
        }
        return clean
    }

    private fun ensureInside(root: File, child: File) {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        require(childPath.startsWith(rootPath)) { "不安全的模块路径：${child.name}" }
    }

    /** 只读完整个流，但只保留最后 [MAX_OUTPUT_CHARS] 个字符 */
    private fun readStreamTail(stream: java.io.InputStream): String {
        val builder = StringBuilder()
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                builder.append(String(buffer, 0, read))
                if (builder.length > MAX_OUTPUT_CHARS * 2) {
                    builder.delete(0, builder.length - MAX_OUTPUT_CHARS)
                }
            }
        }
        return builder.toString().takeLast(MAX_OUTPUT_CHARS)
    }

    private fun writeLastLog(
        logFile: File,
        module: AdbModule,
        script: File,
        result: ModuleActionResult,
        finished: Boolean,
    ) {
        try {
            logFile.parentFile?.mkdirs()
            logFile.writeText(
                buildString {
                    appendLine("module: ${module.id} (${module.name})")
                    appendLine("script: ${script.name}")
                    appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
                    appendLine("mode: ${ModuleSettings.getAccessMode().value}")
                    appendLine("trusted: ${ModuleSettings.isModuleTrusted(module.id)}")
                    appendLine("exit: ${result.exitCode}${if (finished) "" else " (超时)"}")
                    appendLine()
                    appendLine("--- stdout ---")
                    appendLine(result.stdout.trim())
                    if (result.stderr.isNotBlank()) {
                        appendLine()
                        appendLine("--- stderr ---")
                        appendLine(result.stderr.trim())
                    }
                },
            )
        } catch (_: Exception) {
        }
    }
}
