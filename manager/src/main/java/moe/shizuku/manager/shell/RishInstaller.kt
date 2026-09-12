package moe.shizuku.manager.shell

import android.content.Context
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.activation.ActivationRunner
import rikka.shizuku.Shizuku
import java.io.File

/**
 * rish 一键部署。
 *
 * 背景（rish 脚本自身的要求）：
 * - `rish` 是 shell 脚本，必须和 `rish_shizuku.dex` 放在**同一个目录**（脚本按 `dirname $0` 找 dex）；
 * - Android 14+ 上 `app_process` 拒绝加载可写 dex，所以 dex 必须 `chmod 400` —— 这也是为什么
 *   放在 /sdcard（FUSE，chmod 无效）里不行，必须落到能 chmod 的目录；
 * - 脚本里的 `RISH_APPLICATION_ID` 是终端应用的包名（默认占位符 `PKG`），部署时按检测到的终端替换。
 *
 * 部署策略（多路径回退，参考社区 rish_installer 的做法）：
 * 1. 终端装了 Termux 且服务是 **root**：直接写进 Termux 的 `$PREFIX/bin`
 *    （`/data/data/com.termux/files/usr/bin`），chown 给 Termux 的 uid —— 用户打开 Termux 敲 `rish` 即可；
 * 2. 其他情况（adb/shell 身份、或没装 Termux）：落到 `/data/local/tmp/rish`
 *    （root 与 shell 都能写、能 chmod、能执行），并给出在终端里可粘贴的完整路径。
 *
 * assets 先由 App 落到「服务身份读得到」的位置：root 读得到 App 私有 cacheDir，
 * shell(2000) 读不到，所以 shell 身份走 `Android/data/<pkg>/files`（shell 可读）。
 */
object RishInstaller {

    private const val TAG = "RishInstaller"
    private const val SH_NAME = "rish"
    private const val DEX_NAME = "rish_shizuku.dex"

    /** 目前只针对 Termux 做「进 PATH」的部署，其他终端走 /data/local/tmp 路径提示。 */
    const val TERMUX_PACKAGE = "com.termux"

    private const val TERMUX_BIN = "/data/data/$TERMUX_PACKAGE/files/usr/bin"
    private const val FALLBACK_DIR = "/data/local/tmp/rish"

    sealed class Result {
        /** @param targetDir 部署目录 @param hintKey 运行方式（Termux PATH / 完整路径） */
        data class Success(
            val targetDir: String,
            val inTermuxPath: Boolean,
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
     * 执行部署。**必须在后台线程调用**（内部会同步等 shell 命令）。
     */
    fun deploy(context: Context, installIntoTermux: Boolean): Result {
        if (!isServiceRunning()) {
            return Result.Failure("service-not-running")
        }
        val root = isRoot()

        // 1) assets → 服务身份读得到的地方
        val srcDir = if (root) {
            File(context.cacheDir, "rish")
        } else {
            // shell(2000) 读不到 App 私有 cacheDir，改用 Android/data/<pkg>/files（shell 可读）
            File(context.getExternalFilesDir(null) ?: context.cacheDir, "rish")
        }
        try {
            srcDir.mkdirs()
            extract(context, SH_NAME, File(srcDir, SH_NAME))
            extract(context, DEX_NAME, File(srcDir, DEX_NAME))
            patchApplicationId(File(srcDir, SH_NAME), if (installIntoTermux) TERMUX_PACKAGE else null)
        } catch (e: Throwable) {
            Log.e(TAG, "extract assets", e)
            return Result.Failure(e.message ?: e.javaClass.simpleName)
        }

        // 2) 组装部署脚本
        val target = if (installIntoTermux) TERMUX_BIN else FALLBACK_DIR
        val script = buildString {
            appendLine("set -e")
            appendLine("SRC='${srcDir.absolutePath}'")
            appendLine("DST='$target'")
            appendLine("mkdir -p \"\$DST\"")
            appendLine("cp -f \"\$SRC/$SH_NAME\" \"\$SRC/$DEX_NAME\" \"\$DST/\"")
            // rish 脚本要可执行；dex 在 Android 14+ 必须只读
            appendLine("chmod 755 \"\$DST/$SH_NAME\"")
            appendLine("chmod 400 \"\$DST/$DEX_NAME\"")
            if (installIntoTermux) {
                // Termux 的 bin 目录属主是 Termux 自己，root 复制过去要 chown，否则 Termux 读不了
                appendLine("OWNER=\$(stat -c '%u:%g' '/data/data/$TERMUX_PACKAGE' 2>/dev/null || true)")
                appendLine("if [ -n \"\$OWNER\" ]; then chown \"\$OWNER\" \"\$DST/$SH_NAME\" \"\$DST/$DEX_NAME\"; fi")
            }
            appendLine("test -x \"\$DST/$SH_NAME\" && echo DEPLOY_OK")
            appendLine("ls -l \"\$DST/$SH_NAME\" \"\$DST/$DEX_NAME\"")
        }

        val result = ActivationRunner.run(script, timeoutSeconds = 30)
        if (result.error != null || result.timedOut || result.exitCode != 0) {
            val message = result.error?.message
                ?: if (result.timedOut) "timeout" else result.output.ifBlank { "exit=${result.exitCode}" }
            Log.w(TAG, "deploy failed: $message")
            return Result.Failure(message)
        }

        return Result.Success(
            targetDir = target,
            inTermuxPath = installIntoTermux,
            output = result.output,
        )
    }

    private fun extract(context: Context, name: String, out: File) {
        context.assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 把脚本里的 RISH_APPLICATION_ID 占位符替换成终端包名（null = 保持占位符）。 */
    private fun patchApplicationId(script: File, packageName: String?) {
        if (packageName == null) return
        val text = script.readText()
        script.writeText(text.replace("\"PKG\"", "\"$packageName\""))
    }
}
