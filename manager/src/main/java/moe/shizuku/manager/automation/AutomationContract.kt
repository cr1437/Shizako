package moe.shizuku.manager.automation

/**
 * EXEC_SHELL 协议常量与数据结构。
 *
 * 这个文件**故意不引用任何 android.\*** —— 协议解析/校验是安全边界，必须能在
 * JVM 单元测试里直接跑（`manager/src/test` 的 `AutomationPolicyTest`），
 * 不需要模拟器就能验证「白名单 / 元字符 / 脱敏 / 令牌比对」这些硬规则。
 *
 * 协议见 `md/shizako项目架构详细.md` §19（EXEC_SHELL 特权命令执行广播）。
 */
object AutomationContract {

    /** 请求广播：调用方必须显式 setComponent 到 [RECEIVER_CLASS_NAME]。 */
    const val ACTION_EXEC_SHELL = "com.churan.shizako.action.EXEC_SHELL"

    /** 结果广播：只发给令牌绑定的包（`Intent.setPackage`），不回给「谁发的就回谁」。 */
    const val ACTION_EXEC_SHELL_RESULT = "com.churan.shizako.action.EXEC_SHELL_RESULT"

    /** 接收器全名（写进文档/示例；校验显式组件时也用它）。 */
    const val RECEIVER_CLASS_NAME = "moe.shizuku.manager.automation.ExecShellReceiver"

    // ---------------- 请求 extra（与规格逐字一致） ----------------
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_TIMEOUT_MS = "timeout_ms"

    // ---------------- 结果 extra（与规格逐字一致） ----------------
    const val EXTRA_EXIT_CODE = "exit_code"
    const val EXTRA_STDOUT = "stdout"
    const val EXTRA_STDERR = "stderr"
    const val EXTRA_TRUNCATED = "truncated"
    const val EXTRA_ERROR = "error"

    /**
     * 规格外的可选扩展：给调用方一句人能看懂的状态补充。
     * 只在「返回了结果」时出现，绝不带命令原文或令牌。
     */
    const val EXTRA_NOTE = "note"

    // ---------------- 错误码 ----------------
    /** 令牌缺失/错误/已被撤销/已被轮换 */
    const val ERR_DENIED_TOKEN = "DENIED_TOKEN"

    /** 调用方签名与令牌绑定不符（或绑定包被换签重装） */
    const val ERR_DENIED_SIGNATURE = "DENIED_SIGNATURE"

    /** 命令不在白名单 / 含未放行的 shell 元字符 */
    const val ERR_DENIED_WHITELIST = "DENIED_WHITELIST"

    /** Shizuku 服务与 Root 都不可用 */
    const val ERR_NO_PRIVILEGE = "NO_PRIVILEGE"

    /** 超时（进程已终止，附已捕获的部分输出） */
    const val ERR_TIMEOUT = "TIMEOUT"

    /** 缺 request_id / command / token，或参数本身不合法 */
    const val ERR_BAD_REQUEST = "BAD_REQUEST"

    /** 执行器内部异常（比如服务在半路断开） */
    const val ERR_EXEC_FAILED = "EXEC_FAILED"

    /**
     * 日志里「拒绝原因」用的内部标记（不是回传给调用方的错误码）。
     * 非显式组件调用连回传都不做，只在本地日志留痕。
     */
    const val REASON_NOT_EXPLICIT = "not_explicit_component"

    /** 每个令牌允许的并发执行数；超出的请求排队（规格 §五）。 */
    const val MAX_CONCURRENT_PER_TOKEN = 5

    /** 单次执行的 stdout / stderr 上限（规格 F4）。 */
    const val MAX_STREAM_BYTES = 64 * 1024

    /** 命令摘要写进日志的长度（规格 F5）。 */
    const val MAX_COMMAND_SUMMARY = 100

    /** 超时可调范围（规格 F4：5–300 秒）。 */
    const val MIN_TIMEOUT_MS = 5_000
    const val MAX_TIMEOUT_MS = 300_000
    const val DEFAULT_TIMEOUT_MS = 30_000

    /** 令牌数量上限（规格 F1）。 */
    const val MAX_TOKENS = 20

    /** 未识别的超时参数兜底到这个值（不直接拒绝，规格里 timeout_ms 是「可选覆盖」）。 */
    fun clampTimeout(raw: Int): Int = when {
        raw <= 0 -> DEFAULT_TIMEOUT_MS
        raw < MIN_TIMEOUT_MS -> MIN_TIMEOUT_MS
        raw > MAX_TIMEOUT_MS -> MAX_TIMEOUT_MS
        else -> raw
    }
}

/** 一次请求（已通过语法解析、尚未鉴权）。 */
data class AutomationRequest(
    val requestId: String,
    val command: String,
    val timeoutMs: Int,
)

/**
 * 请求解析结果。`error != null` 表示这条广播连「找令牌」都不用做。
 *
 * 注意：解析阶段**不读令牌**。令牌是鉴权用的，解析只判断 request_id/command 是否给了。
 */
data class ParsedRequest(
    val request: AutomationRequest?,
    val token: String?,
    val error: String?,
)

/**
 * 解析广播 extra。
 *
 * @param requestId 原始 request_id
 * @param command 原始 command
 * @param token 原始 token
 * @param timeoutMs 原始 timeout_ms（<=0 表示没给）
 */
fun parseRequest(
    requestId: String?,
    command: String?,
    token: String?,
    timeoutMs: Int,
): ParsedRequest {
    if (requestId.isNullOrBlank()) {
        return ParsedRequest(null, token, AutomationContract.ERR_BAD_REQUEST)
    }
    if (command.isNullOrBlank()) {
        return ParsedRequest(null, token, AutomationContract.ERR_BAD_REQUEST)
    }
    if (token.isNullOrBlank()) {
        return ParsedRequest(null, null, AutomationContract.ERR_BAD_REQUEST)
    }
    // requestId 会被原样回传并在日志里出现，限制长度/字符集防止日志注入（换行/逗号会串列）
    if (requestId.length > 128 || requestId.any { it == '\n' || it == '\r' || it == ',' || it == ';' }) {
        return ParsedRequest(null, token, AutomationContract.ERR_BAD_REQUEST)
    }
    return ParsedRequest(
        AutomationRequest(
            requestId = requestId,
            command = command,
            timeoutMs = AutomationContract.clampTimeout(timeoutMs),
        ),
        token,
        null,
    )
}
