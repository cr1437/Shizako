package moe.shizuku.manager.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.batch.ApiAuditLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 「鉴权通过之后」的统一执行/记账/回传流程。
 *
 * 接收器和前台服务都会走到这里（服务是常规路径，接收器里的线程是
 * 「后台不允许启动前台服务」时的降级路径），所以日志与结果回传只有一份实现。
 */
object AutomationRunner {

    private const val TAG = "ShizakoAutomation"

    /** 每个令牌一个信号量：并发上限 [AutomationContract.MAX_CONCURRENT_PER_TOKEN]，超出的排队。 */
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    private fun semaphore(tokenId: String): Semaphore =
        // 必须用 computeIfAbsent：Kotlin 的 getOrPut 是「先 get 再 put」，在
        // ConcurrentHashMap 上不是原子操作 —— 两条请求同时进来会各建一个 Semaphore(5)，
        // 上限就变成 10（实测 8 条并发全部同一时刻开跑，就是踩到这个）。
        semaphores.computeIfAbsent(tokenId) {
            Semaphore(AutomationContract.MAX_CONCURRENT_PER_TOKEN, true)
        }

    /**
     * 执行一条已授权命令（白名单已在调用方校验过），把结果记进历史 + API 日志，
     * 并把结果广播发给令牌绑定的包。
     *
     * @param callerPackage 能识别时的真实发送方；识别不到为 null
     * @param verifyMode [HistoryEntry.VERIFY_CALLER] / [HistoryEntry.VERIFY_STATIC]
     */
    fun executeAuthorized(
        context: Context,
        token: TokenRecord,
        request: AutomationRequest,
        callerPackage: String?,
        callerUid: Int,
        verifyMode: String,
    ): AutomationExecutor.Outcome {
        val started = System.currentTimeMillis()
        val gate = semaphore(token.id)
        // 排队上限就是这条请求自己的超时：等太久的话调用方早就不等了
        val acquired = try {
            gate.tryAcquire(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            val outcome = AutomationExecutor.Outcome(
                exitCode = -1,
                stdout = "",
                stderr = "",
                truncated = false,
                error = AutomationContract.ERR_TIMEOUT,
                durationMs = System.currentTimeMillis() - started,
                privilege = AutomationExecutor.currentPrivilege(),
                note = "同一令牌已有 ${AutomationContract.MAX_CONCURRENT_PER_TOKEN} 条命令在跑，排队超时",
            )
            record(context, token, request, callerPackage, callerUid, verifyMode, outcome)
            sendResult(context, token.packageName, request.requestId, outcome)
            return outcome
        }

        return try {
            val outcome = AutomationExecutor.execute(request.command, request.timeoutMs)
            // 只要过了鉴权+白名单就算「用过」这个令牌（被拒绝的请求不算）
            runCatching { AutomationStore.touchToken(context, token.id) }
            record(
                context, token, request, callerPackage, callerUid, verifyMode, outcome,
                totalMs = System.currentTimeMillis() - started,
            )
            sendResult(context, token.packageName, request.requestId, outcome)
            outcome
        } catch (t: Throwable) {
            Log.w(TAG, "execute failed", t)
            val outcome = AutomationExecutor.Outcome(
                -1, "", "", false, AutomationContract.ERR_EXEC_FAILED,
                System.currentTimeMillis() - started,
                AutomationExecutor.PRIVILEGE_NONE,
                note = "执行器异常：${t.javaClass.simpleName}",
            )
            record(
                context, token, request, callerPackage, callerUid, verifyMode, outcome,
                totalMs = System.currentTimeMillis() - started,
            )
            sendResult(context, token.packageName, request.requestId, outcome)
            outcome
        } finally {
            gate.release()
        }
    }

    /**
     * 在**执行之前**就被拒绝的请求：只回错误码，不回 stdout/stderr。
     *
     * @param notifyPackage 结果发到哪个包；null 表示连发送对象都确定不了（系统不提供发送方
     *        身份，且令牌没匹配上）→ 只记日志。规格要求「拒绝时不回传敏感信息」，
     *        这里进一步做到：**失败路径一条字节的 stdout 都不会出现**。
     */
    fun reject(
        context: Context,
        requestId: String,
        callerPackage: String?,
        callerUid: Int,
        tokenLabel: String,
        command: String?,
        error: String,
        detail: String?,
        verifyMode: String,
        notifyPackage: String?,
    ) {
        val entry = HistoryEntry(
            requestId = requestId,
            time = System.currentTimeMillis(),
            callerPackage = callerPackage ?: "",
            callerUid = callerUid,
            tokenLabel = tokenLabel,
            commandSummary = if (command.isNullOrBlank()) "" else AutomationPolicy.summarize(command),
            exitCode = -1,
            durationMs = 0L,
            status = HistoryStatus.of(error, -1),
            reason = detail?.let { "$error(${sanitizeDetail(it)})" } ?: error,
            truncated = false,
            verifyMode = verifyMode,
        )
        AutomationStore.appendHistory(context, entry)
        ApiAuditLog.record(
            context,
            func = FUNC_EXEC_SHELL,
            allowed = false,
            detail = apiDetail(entry, extraFields = noteField(detail)),
        )

        if (notifyPackage != null) {
            sendErrorCode(context, notifyPackage, requestId, error, detail)
        }
    }

    // ══════════════ 记账 ══════════════

    private const val FUNC_EXEC_SHELL = "automation.exec_shell"

    private fun record(
        context: Context,
        token: TokenRecord,
        request: AutomationRequest,
        callerPackage: String?,
        callerUid: Int,
        verifyMode: String,
        outcome: AutomationExecutor.Outcome,
        /** 总耗时（含排队）；&lt; 0 表示用执行器自己的耗时 */
        totalMs: Long = -1L,
    ) {
        val durationMs = if (totalMs >= 0) totalMs else outcome.durationMs
        val entry = HistoryEntry(
            requestId = request.requestId,
            time = System.currentTimeMillis(),
            callerPackage = callerPackage ?: token.packageName,
            callerUid = callerUid,
            tokenLabel = token.label,
            commandSummary = AutomationPolicy.summarize(request.command),
            exitCode = outcome.exitCode,
            durationMs = durationMs,
            status = HistoryStatus.of(outcome.error, outcome.exitCode),
            reason = outcome.error ?: outcome.note,
            truncated = outcome.truncated,
            verifyMode = verifyMode,
        )
        AutomationStore.appendHistory(context, entry)
        val extra = buildString {
            append("priv=").append(outcome.privilege)
            val n = noteField(outcome.note)
            if (n.isNotEmpty()) append(";").append(n)
        }
        ApiAuditLog.record(
            context,
            func = FUNC_EXEC_SHELL,
            allowed = outcome.error == null,
            detail = apiDetail(entry, extraFields = extra),
        )
    }

    /** `note=xxx` 字段（空则返回空串，由调用方决定要不要拼）。 */
    private fun noteField(note: String?): String =
        if (note.isNullOrBlank()) "" else "note=" + sanitizeDetail(note)

    /** 第 6 列明细：`k=v` 用分号分隔；值里不能再有逗号/换行（会串列）。 */
    private fun apiDetail(entry: HistoryEntry, extraFields: String): String = buildString {
        append("src=automation")
        append(";rid=").append(entry.requestId)
        append(";pkg=").append(entry.callerPackage.ifEmpty { "-" })
        append(";uid=").append(entry.callerUid)
        append(";token=").append(sanitizeDetail(entry.tokenLabel))
        append(";exit=").append(entry.exitCode)
        append(";dur=").append(entry.durationMs)
        append(";status=").append(entry.status)
        append(";verify=").append(entry.verifyMode)
        append(";reason=").append(sanitizeDetail(entry.reason ?: ""))
        if (extraFields.isNotEmpty()) append(";").append(extraFields)
        append(";trunc=").append(if (entry.truncated) 1 else 0)
        if (entry.commandSummary.isNotEmpty()) append(";cmd=").append(sanitizeDetail(entry.commandSummary))
    }

    /** 明细只能是一行一列：去掉换行，逗号换成全角（日志格式是逗号分列）。 */
    private fun sanitizeDetail(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace(',', '，').replace(';', '；')

    // ══════════════ 结果回传 ══════════════

    /** 结果广播**只发给令牌绑定的包**（`Intent.setPackage`），不会泄给别的应用。 */
    fun sendResult(
        context: Context,
        targetPackage: String,
        requestId: String,
        outcome: AutomationExecutor.Outcome,
    ) {
        val intent = Intent(AutomationContract.ACTION_EXEC_SHELL_RESULT).apply {
            setPackage(targetPackage)
            putExtra(AutomationContract.EXTRA_REQUEST_ID, requestId)
            putExtra(AutomationContract.EXTRA_EXIT_CODE, outcome.exitCode)
            putExtra(AutomationContract.EXTRA_STDOUT, outcome.stdout)
            putExtra(AutomationContract.EXTRA_STDERR, outcome.stderr)
            putExtra(AutomationContract.EXTRA_TRUNCATED, outcome.truncated)
            if (outcome.error != null) {
                putExtra(AutomationContract.EXTRA_ERROR, outcome.error)
            }
            if (!outcome.note.isNullOrBlank()) {
                putExtra(AutomationContract.EXTRA_NOTE, outcome.note)
            }
        }
        send(context, intent, targetPackage, requestId)
    }

    private fun sendErrorCode(
        context: Context,
        targetPackage: String,
        requestId: String,
        error: String,
        detail: String?,
    ) {
        val intent = Intent(AutomationContract.ACTION_EXEC_SHELL_RESULT).apply {
            setPackage(targetPackage)
            putExtra(AutomationContract.EXTRA_REQUEST_ID, requestId)
            putExtra(AutomationContract.EXTRA_EXIT_CODE, -1)
            putExtra(AutomationContract.EXTRA_STDOUT, "")
            putExtra(AutomationContract.EXTRA_STDERR, "")
            putExtra(AutomationContract.EXTRA_TRUNCATED, false)
            putExtra(AutomationContract.EXTRA_ERROR, error)
            if (!detail.isNullOrBlank()) {
                putExtra(AutomationContract.EXTRA_NOTE, detail)
            }
        }
        send(context, intent, targetPackage, requestId)
    }

    private fun send(context: Context, intent: Intent, targetPackage: String, requestId: String) {
        // FLAG_INCLUDE_STOPPED_PACKAGES：调用方刚装好/被 force-stop 时仍是「stopped」状态，
        // 默认投递会把结果直接丢掉（实测：新装的客户端收不到回执）。
        // 调用方既然主动发了请求，就说明它要这个结果 —— 和「静默推送」不是一回事。
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "send result failed rid=$requestId", t)
        }
    }
}
