package moe.shizuku.manager.installer

import android.util.Log
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.utils.ShizukuState
import java.util.UUID

/**
 * 用 **PackageInstaller 会话**安装（规格 F8）。
 *
 * 为什么是 `pm install-create / install-write / install-commit` 而不是在进程里 new 一个
 * `PackageInstaller.Session`：创建会话需要 `INSTALL_PACKAGES`（signature|privileged）权限，
 * 本应用是普通应用拿不到；`pm` 对 shell 身份才有权限，而我们的特权通道就是 Shizuku 的
 * `newProcess`。`pm` 这条链内部走的就是 `PackageInstallerService` 的同名会话 API
 * （`-S` 对应 `SessionParams.setSize()`、commit 对应 `Session.commit()`），所以
 * 「split 走 Session」这件事成立，只是会话由 shell 身份代建。
 *
 * 文件怎么送到 shell 那边：App 私有缓存目录 shell 读不到（0700），`/sdcard` 在 Android 11+
 * 也进不去，所以用 [ActivationRunner.streamToServer] 从标准输入写进
 * `/data/local/tmp/shizako-inst-<uuid>/`，装完（含失败/取消）一律删掉。
 *
 * 取消的边界：会话**提交前**可以撤销（`pm install-abandon`），已经发给系统的
 * `install-commit` 无法撤回 —— 这时会被记成「已完成」，UI 上不做假动作。
 */
object SessionInstaller {

    private const val TAG = "ShizakoInstall"
    private const val REMOTE_BASE = "/data/local/tmp"
    private const val MIN_TIMEOUT = 60
    private const val MAX_TIMEOUT = 900
    private const val BYTES_PER_SECOND = 2L * 1024 * 1024

    /** 取消令牌：只在阶段之间检查（push 完一个 split、create 后、commit 前）。 */
    class CancelToken {
        @Volatile
        var cancelled: Boolean = false
    }

    data class Outcome(
        val success: Boolean,
        val error: InstallError?,
        val message: String?,
    )

    /**
     * @param onStage 进度回调：(已写入的 split 数, 总数)。主线程之外的线程调用。
     */
    fun install(
        prepared: PreparedPackage,
        cancel: CancelToken?,
        onStage: ((Int, Int) -> Unit)? = null,
    ): Outcome {
        if (!ShizukuState.safePing()) {
            return Outcome(false, InstallError.NO_SERVICE, "Shizuku service is not running")
        }
        val sessionDir = "$REMOTE_BASE/shizako-inst-${UUID.randomUUID().toString().take(8)}"
        val totalBytes = prepared.splits.sumOf { it.file.length() }
        var sessionId: Long? = null
        try {
            val mkdir = ActivationRunner.run("mkdir -p '$sessionDir'", timeoutSeconds = 20)
            if (!mkdir.success) {
                return Outcome(false, InstallError.IO, mkdir.output.ifBlank { "mkdir failed" })
            }

            // 1) 把每一份 split 推进 /data/local/tmp（stdin 流式，不经 /sdcard）
            prepared.splits.forEachIndexed { index, split ->
                if (cancel?.cancelled == true) return Outcome(false, InstallError.CANCELLED, "cancelled")
                val push = ActivationRunner.streamToServer(
                    "cat > '$sessionDir/${split.sessionName}' && chmod 644 '$sessionDir/${split.sessionName}'",
                    split.file.inputStream(),
                    timeoutSeconds = timeoutFor(split.file.length()),
                )
                if (!push.success) {
                    return Outcome(false, InstallError.SESSION_WRITE_FAILED, push.message())
                }
                onStage?.invoke(index + 1, prepared.splits.size)
            }

            // 2) 建会话：-r 覆盖安装、-t 允许 testOnly、-S 设置总大小（spec 的 setSize）
            val create = ActivationRunner.run(
                "pm install-create -r -t -S $totalBytes",
                timeoutSeconds = 120,
            )
            val parsed = parseSessionId(create.output)
            if (!create.success || parsed == null) {
                return Outcome(
                    false,
                    InstallError.SESSION_CREATE_FAILED,
                    create.output.ifBlank { "install-create failed" },
                )
            }
            sessionId = parsed

            // 3) 逐个写入会话
            prepared.splits.forEach { split ->
                if (cancel?.cancelled == true) {
                    abandon(sessionId)
                    return Outcome(false, InstallError.CANCELLED, "cancelled")
                }
                val write = ActivationRunner.run(
                    "pm install-write -S ${split.file.length()} $parsed '${split.sessionName}' " +
                        "'$sessionDir/${split.sessionName}'",
                    timeoutSeconds = timeoutFor(split.file.length()),
                )
                if (!write.success) {
                    abandon(sessionId)
                    return Outcome(false, InstallError.SESSION_WRITE_FAILED, write.output.ifBlank { "install-write failed" })
                }
            }

            if (cancel?.cancelled == true) {
                abandon(sessionId)
                return Outcome(false, InstallError.CANCELLED, "cancelled")
            }

            // 4) 提交：这一步开始系统在真正装，取消不再生效
            val commit = ActivationRunner.run("pm install-commit $parsed", timeoutSeconds = MAX_TIMEOUT)
            if (!commit.success || !looksSuccessful(commit.output)) {
                return Outcome(
                    false,
                    errorOf(commit.output),
                    commit.output.ifBlank { "install-commit exit ${commit.exitCode}" },
                )
            }
            sessionId = null // 已提交，finally 不需要 abandon
            return Outcome(true, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "session install failed", t)
            sessionId?.let { abandon(it) }
            return Outcome(false, InstallError.UNKNOWN, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { ActivationRunner.run("rm -rf '$sessionDir'", timeoutSeconds = 30) }
        }
    }

    /**
     * OBB 扩展文件：复制到 `/sdcard/Android/obb/<包名>/`。
     * 返回 null 表示成功或没有 OBB；返回字符串是失败原因（安装本身**不因此失败**，规格 §五）。
     */
    fun installObbs(prepared: PreparedPackage, packageName: String): String? {
        if (prepared.obbs.isEmpty()) return null
        if (!ShizukuState.safePing()) return "service not running"
        val target = "/sdcard/Android/obb/$packageName"
        val mkdir = ActivationRunner.run("mkdir -p '$target'", timeoutSeconds = 20)
        if (!mkdir.success) return mkdir.message()
        prepared.obbs.forEach { obb ->
            // 不 chmod：/sdcard 是 FUSE，chmod 会被内核/FUSE 拒（实测
            // `chmod ... to 0644: Operation not permitted`），而 OBB 目录的属主/权限
            // 由 FUSE 自己按包名托管 —— 应用读自己 obb 目录本来也不需要这个权限位。
            val push = ActivationRunner.streamToServer(
                "cat > '$target/${obb.fileName}'",
                obb.file.inputStream(),
                timeoutSeconds = timeoutFor(obb.file.length()),
            )
            if (!push.success) return "${obb.fileName}: ${push.message()}"
        }
        return null
    }

    // ---------------- 解析 ----------------

    private val SESSION_PATTERN = Regex("install session \\[(\\d+)]")

    fun parseSessionId(output: String): Long? =
        SESSION_PATTERN.find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /** `pm install-commit` 成功时输出 `Success`；部分版本会带 `Success: streamed ...`。 */
    private fun looksSuccessful(output: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false
        return (text.startsWith("Success") || text.contains("Success")) && !text.contains("Failure")
    }

    /**
     * 把 `Failure [INSTALL_FAILED_xxx: msg]` 归类。空间不足单独拎出来，因为它是
     * 用户唯一能自己解决的一类（规格 §五要求原因写「空间不足」并继续队列）。
     */
    fun errorOf(output: String): InstallError {
        val upper = output.uppercase()
        return when {
            upper.contains("INSUFFICIENT_STORAGE") -> InstallError.NO_SPACE
            upper.contains("SIGNATURE") || upper.contains("CERTIFICATE") -> InstallError.INSTALLER_REJECTED
            upper.contains("PARSE_FAILED") || upper.contains("INVALID_APK") -> InstallError.INSTALLER_REJECTED
            upper.contains("INCOMPATIBLE") || upper.contains("NO_MATCHING_ABIS") -> InstallError.INSTALLER_REJECTED
            upper.contains("FAILURE") -> InstallError.INSTALLER_REJECTED
            else -> InstallError.UNKNOWN
        }
    }

    private fun abandon(sessionId: Long?) {
        if (sessionId == null) return
        runCatching { ActivationRunner.run("pm install-abandon $sessionId", timeoutSeconds = 60) }
    }

    private fun timeoutFor(bytes: Long): Int =
        ((bytes / BYTES_PER_SECOND) + MIN_TIMEOUT).coerceIn(MIN_TIMEOUT.toLong(), MAX_TIMEOUT.toLong()).toInt()

    private fun ActivationRunner.Result.message(): String = when {
        error != null -> error?.message ?: "error"
        timedOut -> "timeout"
        output.isNotBlank() -> output
        else -> "exit $exitCode"
    }
}
