package moe.shizuku.manager.shell

import android.content.Context
import android.util.Base64
import android.util.Log
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.activation.ActivationRunner
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.io.File

/**
 * rish 一键部署。
 *
 * 背景（rish 脚本自身的要求 + 跨用户读取）：
 * - `rish` 是 shell 脚本，必须和 `rish_shizuku.dex` 放在**同一个目录**（脚本按 `dirname $0` 找 dex）；
 * - Android 14+ 上 `app_process` 拒绝加载**可写** dex，所以 dex 不能带写位。注意官方脚本里写的
 *   `chmod 400` 只在「文件属主 = 运行用户」时成立：放到 `/data/local/tmp` 后跑 rish 的是
 *   **终端应用**（uid 10371 之类），它既不是属主、也读不到 400 的文件 —— 加载 dex 直接
 *   `SIGABRT / ClassNotFoundException: rikka.shizuku.shell.ShizukuShellLoader`。
 *   所以这里统一用 **444**（全用户可读、任何人不可写）：既满足「不可写」，又让任何终端应用读得到。
 * - 脚本里的 `RISH_APPLICATION_ID` 是终端应用的包名（默认占位符 `PKG`），部署时按检测到的终端替换。
 *
 * 两条部署路径：
 * 1. **服务是 root**：源文件落在 App 私有 `cacheDir`（root 读得到），让服务自己 `cp`；
 *    装了 Termux 还能顺手写进 `$PREFIX/bin` 并 `chown`。
 * 2. **服务是 adb/shell（uid 2000）**：`/sdcard`（含 `Android/data/<pkg>`）在 Android 11+ 被 FUSE +
 *    SELinux 挡住，shell 连 App 自己的外部目录都读不到 —— 旧实现就是在这里报
 *    `cp: .../Android/data/<pkg>/files/rish/rish: Permission denied`。
 *    改成**把内容从 App 的标准输入喂给服务进程**，落在 `/data/local/tmp` 下当前身份写得进去的目录，
 *    全程不依赖任何文件系统读权限。
 */
object RishInstaller {

    private const val TAG = "RishInstaller"
    private const val SH_NAME = "rish"
    private const val DEX_NAME = "rish_shizuku.dex"

    /** 目前只针对 Termux 做「进 PATH」的部署，其他终端走 /data/local/tmp 路径提示。 */
    const val TERMUX_PACKAGE = "com.termux"

    private const val TERMUX_BIN = "/data/data/$TERMUX_PACKAGE/files/usr/bin"
    private const val FALLBACK_DIR = "/data/local/tmp/rish"

    /** 部署串行化锁（见 [deploy]）。 */
    private val DEPLOY_LOCK = Any()

    /** 服务身份，用于给用户解释「为什么只能放 /data/local/tmp」。 */
    enum class Runtime { ROOT, SHELL }

    sealed class Result {
        /** @param targetDir 部署目录 @param inTermuxPath 是否已进 Termux 的 PATH */
        data class Success(
            val targetDir: String,
            val inTermuxPath: Boolean,
            val runtime: Runtime,
            val output: String,
        ) : Result()

        data class Failure(val message: String) : Result()
    }

    /** 服务是否在运行（不在运行就没法部署，需要先激活）。 */
    fun isServiceRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 服务身份：true = root（uid 0），false = adb/shell。 */
    fun isRoot(): Boolean = isServiceRunning() && Shizuku.getUid() == 0

    /** 装了 Termux 吗（走服务侧 pm list，绕开包可见性限制）。 */
    fun hasTermux(): Boolean = ActivationRunner.isPackageInstalled(TERMUX_PACKAGE)

    /**
     * 给用户复制的一行调用命令。
     *
     * rish 会把 `RISH_APPLICATION_ID` 报给服务端做「哪个 App 在请求」的校验，所以必须是
     * **用户真正打开的那个终端**：装了 Termux 就用 Termux，否则留个占位符让用户自己换。
     */
    fun commandLine(targetDir: String, inTermuxPath: Boolean): String {
        val terminal = if (hasTermux()) TERMUX_PACKAGE else "<你的终端包名>"
        val path = if (inTermuxPath) SH_NAME else "$targetDir/$SH_NAME"
        return "RISH_APPLICATION_ID=$terminal sh $path"
    }

    /**
     * 执行部署。**必须在后台线程调用**（内部会同步等 shell 命令）。
     *
     * 用锁串行化：部署分两步（先脚本、再 dex），两次同时跑会互相把对方写了一半的文件覆盖，
     * 报出来的就是 `Permission denied` 这种看不懂的错。
     */
    fun deploy(context: Context, installIntoTermux: Boolean): Result = synchronized(DEPLOY_LOCK) {
        if (!isServiceRunning()) {
            return@synchronized Result.Failure("service-not-running")
        }

        val script = readAssetText(context, SH_NAME) ?: return@synchronized Result.Failure("asset $SH_NAME missing")
        if (script.isBlank()) {
            return@synchronized Result.Failure("asset $SH_NAME is empty")
        }

        if (isRoot()) {
            deployAsRoot(context, script, installIntoTermux)
        } else {
            // shell 身份只能落 /data/local/tmp，但 RISH_APPLICATION_ID 仍要指向终端应用，
            // 否则用户在 Termux 里执行脚本会因为包名还是占位符 `PKG` 而失败
            deployAsShell(context, script, if (hasTermux()) TERMUX_PACKAGE else null)
        }
    }

    /**
     * 自检输出（只在显式带 `zako_deploy_selftest` 启动时调用）。
     * vivo 之类 ROM 会把应用日志从 logcat 里过滤掉，所以结果同时写到
     * `Android/data/<pkg>/files/shizako-deploy.log`（adb shell 读得到）。
     */
    internal fun dumpSelfTest(context: Context, result: Result) {
        val text = "uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)} " +
            "root=${isRoot()} termux=${hasTermux()}\n" +
            "result=${result::class.java.simpleName} ${(result as? Result.Success)?.output ?: (result as? Result.Failure)?.message}"
        Log.i(TAG, "selftest: $text")
        val out = File(context.getExternalFilesDir(null) ?: context.cacheDir, "shizako-deploy.log")
        runCatching { out.writeText(text + "\n") }
        runCatching { File("/data/local/tmp/shizako-deploy.log").writeText(text + "\n") }
    }

    /**
     * 自检（DEBUG）：用**管理器自己的身份**跑一次部署好的 rish，
     * 验证「dex 能不能被非属主的 uid 加载」这一环 —— 这正是之前只有一句 `Aborted`
     * （ClassNotFoundException: rikka.shizuku.shell.ShizukuShellLoader）的地方。
     * 结果同样写到 `Android/data/<pkg>/files/shizako-run.log`。
     */
    internal fun selfRunTest(context: Context, targetDir: String) {
        val command = "RISH_APPLICATION_ID=${BuildConfig.APPLICATION_ID} sh $targetDir/$SH_NAME -c id"
        val result = ActivationRunner.run(command, timeoutSeconds = 25)
        val text = buildString {
            appendLine("cmd=$command")
            appendLine("exit=${result.exitCode} timeout=${result.timedOut} error=${result.error}")
            appendLine("output=${result.output}")
        }
        Log.i(TAG, "selfrun:\n$text")
        val out = File(context.getExternalFilesDir(null) ?: context.cacheDir, "shizako-run.log")
        runCatching { out.writeText(text) }
    }

    /** root：App 私有 cacheDir 是 root 读得到的，直接 cp，dex 不用走 I/O 桥。 */
    private fun deployAsRoot(context: Context, script: String, installIntoTermux: Boolean): Result {
        val patched = if (installIntoTermux) patchApplicationId(script, TERMUX_PACKAGE) else script
        val srcDir = File(context.cacheDir, "rish").apply { mkdirs() }
        try {
            val sh = File(srcDir, SH_NAME)
            sh.writeText(patched)
            val dex = File(srcDir, DEX_NAME)
            context.assets.open(DEX_NAME).use { input ->
                dex.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "stage assets", e)
            return Result.Failure(e.message ?: e.javaClass.simpleName)
        }

        val target = if (installIntoTermux) TERMUX_BIN else FALLBACK_DIR
        val command = buildString {
            appendLine("SRC='${srcDir.absolutePath}'")
            appendLine("DST='$target'")
            appendLine("mkdir -p \"\$DST\" || exit 1")
            // 覆盖前先删：旧的 dex 是 444/400，属主自己也没写权限，cp 会 EACCES
            appendLine("rm -f \"\$DST/$SH_NAME\" \"\$DST/$DEX_NAME\" || exit 1")
            appendLine("cp \"\$SRC/$SH_NAME\" \"\$SRC/$DEX_NAME\" \"\$DST/\" || exit 1")
            if (installIntoTermux) {
                // Termux 的 bin 目录属主是 Termux 自己，root 复制过去要 chown，否则 Termux 读不了
                appendLine("OWNER=\$(stat -c '%u:%g' '/data/data/$TERMUX_PACKAGE' 2>/dev/null)")
                appendLine("if [ -n \"\$OWNER\" ]; then chown \"\$OWNER\" \"\$DST/$SH_NAME\" \"\$DST/$DEX_NAME\" || exit 1; fi")
            }
            // 755 脚本 + 444 dex：dex 不可写（Android 14+ 要求）且全用户可读（终端应用要读）
            appendLine("chmod 755 \"\$DST/$SH_NAME\" && chmod 444 \"\$DST/$DEX_NAME\" || exit 1")
            appendLine("test -x \"\$DST/$SH_NAME\" || exit 1")
            appendLine("echo DEPLOY_OK")
            appendLine("ls -l \"\$DST/$SH_NAME\" \"\$DST/$DEX_NAME\"")
        }

        val result = ActivationRunner.run(command, timeoutSeconds = 60)
        return finish(result, target, installIntoTermux, Runtime.ROOT)
    }

    /**
     * shell(adb)：内容走标准输入。
     * 第一条命令只吃脚本（base64 文本），第二条只吃 dex（原始字节，`cat` 读到 EOF 即结束），
     * 两条管道互不干扰，失败也不会把 6MB 卡在管道里。
     */
    private fun deployAsShell(context: Context, script: String, terminalPackage: String?): Result {
        val encoded = Base64.encodeToString(
            patchApplicationId(script, terminalPackage).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val staged = File(context.cacheDir, DEX_NAME)
        try {
            context.assets.open(DEX_NAME).use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "stage dex", e)
            return Result.Failure(e.message ?: e.javaClass.simpleName)
        }

        val head = buildString {
            // 选目录：优先 /data/local/tmp/rish；它可能是之前用 root 身份建的
            // （755、root 属主）而当前身份根本写不进去 —— 那就换一个自己建的目录。
            // 只挑「能真正写进去」的目录，否则用户会看到一串 Permission denied。
            appendLine("for d in /data/local/tmp/rish /data/local/tmp/shizako-rish /data/local/tmp; do")
            appendLine("  mkdir -p \"\$d\" 2>/dev/null")
            appendLine("  if [ -d \"\$d\" ] && touch \"\$d/.shizako_probe\" 2>/dev/null; then")
            appendLine("    DST=\"\$d\"; rm -f \"\$d/.shizako_probe\" 2>/dev/null; break")
            appendLine("  fi")
            appendLine("done")
            appendLine("[ -n \"\$DST\" ] || exit 1")
            // 重新部署时脚本可能还是只读/被占用，先删再建
            appendLine("rm -f \"\$DST/$SH_NAME\" 2>/dev/null")
            appendLine("touch \"\$DST/$SH_NAME\" || exit 1")
            appendLine("base64 -d > \"\$DST/$SH_NAME\" <<'SHIZAKO_SH_EOF'")
            appendLine(encoded)
            appendLine("SHIZAKO_SH_EOF")
            appendLine("chmod 755 \"\$DST/$SH_NAME\" || exit 1")
            // 目录路径回传给 App：真正落在哪里，界面就显示哪里
            appendLine("echo DEPLOY_DST=\$DST")
        }
        val scriptResult = ActivationRunner.streamToServer(
            command = head,
            stdin = ByteArrayInputStream(ByteArray(0)),
            timeoutSeconds = 30,
        )
        if (scriptResult.error != null || scriptResult.timedOut || scriptResult.exitCode != 0) {
            Log.w(
                TAG,
                "write script failed: exit=${scriptResult.exitCode} timeout=${scriptResult.timedOut} " +
                    "error=${scriptResult.error} output=${scriptResult.output}",
                scriptResult.error,
            )
            return Result.Failure(scriptResult.output.ifBlank { "exit=${scriptResult.exitCode}" })
        }
        val target = Regex("DEPLOY_DST=(\\S+)").find(scriptResult.output)?.groupValues?.get(1)
            ?: FALLBACK_DIR

        val dexBytes = try {
            staged.inputStream()
        } catch (e: Throwable) {
            return Result.Failure(e.message ?: e.javaClass.simpleName)
        }
        val tail = buildString {
            appendLine("DST='$target'")
            // dex 上次部署后是 444（不可写），**属主自己也没写权限**：
            // 直接 `cat >` 会被 EACCES 挡住，必须先删掉再新建。脚本在上面已经重新写好，别动它。
            appendLine("rm -f \"\$DST/$DEX_NAME\" || exit 1")
            appendLine("cat > \"\$DST/$DEX_NAME\" || exit 1")
            // 444 = 全用户可读 + 谁都不可写：终端应用（不同 uid）读得到，Android 14+ 也认。
            // 千万不要用官方脚本里的 400 —— 属主是部署身份（shell），终端应用根本读不到，
            // 结果就是 app_process 起不来、只有一句 "Aborted"。
            appendLine("chmod 444 \"\$DST/$DEX_NAME\" || exit 1")
            appendLine("test -x \"\$DST/$SH_NAME\" || exit 1")
            appendLine("test -s \"\$DST/$DEX_NAME\" || exit 1")
            appendLine("echo DEPLOY_OK")
            appendLine("ls -l \"\$DST/$SH_NAME\" \"\$DST/$DEX_NAME\"")
        }
        val dexResult = ActivationRunner.streamToServer(
            command = tail,
            stdin = dexBytes,
            timeoutSeconds = 180,
        )
        runCatching { dexBytes.close() }
        runCatching { staged.delete() }

        return finish(dexResult, target, installIntoTermux = false, runtime = Runtime.SHELL)
    }

    private fun finish(
        result: ActivationRunner.Result,
        target: String,
        installIntoTermux: Boolean,
        runtime: Runtime,
    ): Result {
        if (result.error != null || result.timedOut || result.exitCode != 0 ||
            !result.output.contains("DEPLOY_OK")
        ) {
            val message = result.error?.message
                ?: if (result.timedOut) "timeout" else result.output.ifBlank { "exit=${result.exitCode}" }
            Log.w(TAG, "deploy failed ($runtime): $message")
            return Result.Failure(message)
        }
        Log.i(TAG, "deploy ok ($runtime) -> $target")
        return Result.Success(target, installIntoTermux, runtime, result.output)
    }

    /** 把脚本里的 RISH_APPLICATION_ID 占位符替换成终端包名（null = 保持占位符）。 */
    private fun patchApplicationId(script: String, packageName: String?): String =
        if (packageName == null) script else script.replace("\"PKG\"", "\"$packageName\"")

    private fun readAssetText(context: Context, name: String): String? = try {
        context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Throwable) {
        Log.e(TAG, "read asset $name", e)
        null
    }
}
