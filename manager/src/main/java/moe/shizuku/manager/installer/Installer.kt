package moe.shizuku.manager.installer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import moe.shizuku.manager.R
import moe.shizuku.manager.batch.ApiAuditLog
import moe.shizuku.manager.utils.ShizukuState
import java.util.UUID

/**
 * 单次安装的执行入口：**准备 → 提权会话安装 → OBB → 历史 → 审计**（规格 F6/F7/F8）。
 *
 * 所有进入「真正安装」的路径（外部 Intent 中转、设置页批量队列、系统安装器兜底之前）
 * 都走这里，于是「每次安装都写历史 + 写审计日志」只有一处实现，不会漏。
 *
 * 不做信任判定 —— 那是 [InstallPolicy] 的事，判定结果由调用方以 [InstallRequest.source]
 * 传进来（这样历史里的「来源」是判定当时的结论，而不是事后重算）。
 */
object Installer {

    private const val TAG = "ShizakoInstall"

    data class Outcome(
        val success: Boolean,
        val status: TaskStatus,
        val error: InstallError?,
        /** 已本地化的失败原因/备注（直接进历史与 UI）。 */
        val reason: String?,
        val packageName: String?,
        val versionName: String?,
        val format: PackageFormat,
        val durationMs: Long,
        val obbNote: String? = null,
    )

    fun install(
        context: Context,
        request: InstallRequest,
        cancel: SessionInstaller.CancelToken? = null,
        onStage: ((Int, Int) -> Unit)? = null,
    ): Outcome {
        val startedAt = SystemClock.elapsedRealtime()
        var prepared: PreparedPackage? = null
        var outcome: Outcome
        try {
            outcome = when {
                !ShizukuState.safePing() ->
                    failure(InstallError.NO_SERVICE, null, request.format)

                cancel?.cancelled == true ->
                    failure(InstallError.CANCELLED, null, request.format, TaskStatus.CANCELLED)

                else -> {
                    prepared = PackageArchive.prepare(context, Uri.parse(request.fileUri), request.fileName)
                    if (cancel?.cancelled == true) {
                        failure(InstallError.CANCELLED, null, prepared.format, TaskStatus.CANCELLED)
                    } else {
                        val session = SessionInstaller.install(prepared, cancel, onStage)
                        if (session.success) {
                            val obbNote = if (prepared.obbs.isEmpty()) {
                                null
                            } else {
                                SessionInstaller.installObbs(prepared, prepared.packageName ?: "")
                                    ?.also { Log.w(TAG, "obb install failed: $it") }
                            }
                            Outcome(
                                success = true,
                                status = TaskStatus.SUCCESS,
                                error = null,
                                reason = obbNote?.let { context.getString(R.string.installer_obb_not_installed, it) },
                                packageName = prepared.packageName,
                                versionName = prepared.versionName,
                                format = prepared.format,
                                durationMs = 0L,
                                obbNote = obbNote,
                            )
                        } else {
                            val status = if (session.error == InstallError.CANCELLED) TaskStatus.CANCELLED else TaskStatus.FAILED
                            failure(
                                session.error ?: InstallError.UNKNOWN,
                                session.message,
                                prepared.format,
                                status,
                            ).copy(packageName = prepared.packageName, versionName = prepared.versionName)
                        }
                    }
                }
            }
        } catch (f: InstallFailure) {
            outcome = failure(f.error, f.detail, prepared?.format ?: request.format)
                .copy(packageName = prepared?.packageName, versionName = prepared?.versionName)
        } catch (t: Throwable) {
            Log.w(TAG, "install failed", t)
            outcome = failure(InstallError.UNKNOWN, t.message ?: t.javaClass.simpleName, prepared?.format ?: request.format)
                .copy(packageName = prepared?.packageName, versionName = prepared?.versionName)
        } finally {
            // 解压目录限私有缓存、装完即删（规格安全要求），失败/取消也一样删
            prepared?.cleanup()
        }

        val duration = SystemClock.elapsedRealtime() - startedAt
        val reason = if (outcome.success) {
            outcome.reason
        } else {
            reasonText(context, outcome.error ?: InstallError.UNKNOWN, outcome.reason)
        }
        val result = outcome.copy(durationMs = duration, reason = reason)
        recordHistory(context, request, result)
        recordAudit(context, request, result)
        return result
    }

    // ---------------- 历史 / 审计 ----------------

    private fun recordHistory(context: Context, request: InstallRequest, outcome: Outcome) {
        runCatching {
            InstallHistoryStore.append(
                context,
                InstallHistoryEntry(
                    id = UUID.randomUUID().toString(),
                    fileName = request.fileName,
                    packageName = outcome.packageName,
                    versionName = outcome.versionName,
                    format = outcome.format,
                    source = request.source,
                    sourcePackage = request.sourcePackage,
                    result = outcome.status,
                    reason = outcome.reason,
                    durationMs = outcome.durationMs,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "history write failed", it) }
    }

    /** 每次安装（含失败）写一条 API 审计日志：`src=install` + 来源/结果/耗时。 */
    private fun recordAudit(context: Context, request: InstallRequest, outcome: Outcome) {
        val detail = buildString {
            append("src=install")
            append(";fmt=").append(outcome.format)
            append(";file=").append(sanitize(request.fileName))
            append(";pkg=").append(outcome.packageName ?: "-")
            append(";ver=").append(outcome.versionName ?: "-")
            append(";source=").append(request.source.name)
            append(";from=").append(sanitize(request.sourcePackage ?: "-"))
            append(";result=").append(outcome.status.name)
            append(";dur=").append(outcome.durationMs)
            if (!outcome.success) append(";err=").append(outcome.error ?: InstallError.UNKNOWN)
        }
        // ApiAuditLog.record 自己投递到单线程 executor，不会阻塞安装结果返回；
        // 服务没在跑时它会把行攒进待补写队列（≤200），不会丢。
        runCatching { ApiAuditLog.record(context, "install", outcome.success, detail) }
            .onFailure { Log.w(TAG, "audit write failed", it) }
    }

    // ---------------- 工具 ----------------

    fun reasonText(context: Context, error: InstallError, detail: String?): String {
        val res = when (error) {
            InstallError.NO_SERVICE -> R.string.installer_err_no_service
            InstallError.NO_SPACE -> R.string.installer_err_no_space
            InstallError.UNKNOWN_ARCHIVE -> R.string.installer_err_unknown_archive
            InstallError.MISSING_BASE -> R.string.installer_err_missing_base
            InstallError.SIGNATURE_MISMATCH -> R.string.installer_err_signature_mismatch
            InstallError.IO -> R.string.installer_err_io
            InstallError.SESSION_CREATE_FAILED -> R.string.installer_err_session_create
            InstallError.SESSION_WRITE_FAILED -> R.string.installer_err_session_write
            InstallError.SESSION_COMMIT_FAILED -> R.string.installer_err_session_commit
            InstallError.INSTALLER_REJECTED -> R.string.installer_err_rejected
            InstallError.CANCELLED -> R.string.installer_err_cancelled
            InstallError.UNKNOWN -> R.string.installer_err_unknown
        }
        val base = context.getString(res)
        val trimmed = detail?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
        return if (trimmed == null) base else "$base（$trimmed）"
    }

    private fun failure(
        error: InstallError,
        detail: String?,
        format: PackageFormat,
        status: TaskStatus = if (error == InstallError.CANCELLED) TaskStatus.CANCELLED else TaskStatus.FAILED,
    ): Outcome = Outcome(
        success = false,
        status = status,
        error = error,
        reason = detail,
        packageName = null,
        versionName = null,
        format = format,
        durationMs = 0L,
    )

    /** 审计日志用分号分隔，文件名里的分号/换行会破坏解析，统一替换掉。 */
    private fun sanitize(text: String): String =
        text.replace(';', '_').replace('\n', ' ').replace('\r', ' ').take(120)
}
