package moe.shizuku.manager.automation

/**
 * 自动化功能的纯数据模型（不引用 android.\*，可在 JVM 单测里直接用）。
 *
 * 这里有一条刻意的约束：**明文令牌不进任何 data class**。
 * data class 的 `toString()` 会进日志、进崩溃栈、进 IDE 调试器 —— 只要有一次
 * `Log.d(TAG, "$token")` 就会把凭据写进 logcat。所以：
 * - 落盘/传递用 [TokenRecord]（只带密文）；
 * - 展示用 [TokenView]（只带掩码）；
 * - 明文只在「创建 / 轮换」的那一刻以局部变量的形式存在，交给调用方弹一次窗。
 */

/** 一个令牌的持久化形态。`secretCipher` 是 `iv:密文`，明文在 [AutomationCrypto] 里才有。 */
data class TokenRecord(
    val id: String,
    val label: String,
    val packageName: String,
    val signatureHash: String,
    val secretCipher: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val usedCount: Int,
)

/** 设置页列表用的展示形态（不含任何可复原令牌的信息）。 */
data class TokenView(
    val id: String,
    val label: String,
    val packageName: String,
    /** 签名摘要前 8 位，够用户核对，不足以推导 */
    val signatureHashShort: String,
    /** 前 4 后 4 的掩码 */
    val maskedToken: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val usedCount: Int,
    /** 解不开（Keystore 密钥被清 / 从别处还原了数据）→ 只能撤销 */
    val broken: Boolean,
)

/** 创建/轮换令牌的结果。 */
sealed class TokenSecretResult {
    data class Success(val record: TokenRecord, val plaintext: String) : TokenSecretResult()
    /** reason: `limit` / `no_signature` / `crypto` / `not_found` / `duplicate` */
    data class Failure(val reason: String) : TokenSecretResult()
}

/** 执行历史的状态分类（设置页筛选按这四个值）。 */
object HistoryStatus {
    const val OK = "ok"
    const val DENIED = "denied"
    const val ERROR = "error"
    const val TIMEOUT = "timeout"

    fun of(error: String?, exitCode: Int): String = when {
        error == AutomationContract.ERR_TIMEOUT -> TIMEOUT
        error != null -> if (error.startsWith("DENIED") ||
            error == AutomationContract.ERR_BAD_REQUEST ||
            error == AutomationContract.REASON_NOT_EXPLICIT
        ) {
            DENIED
        } else {
            ERROR
        }
        exitCode == 0 -> OK
        else -> ERROR
    }
}

/** 一条执行历史（规格 F5 / F6「执行历史」）。 */
data class HistoryEntry(
    val requestId: String,
    val time: Long,
    val callerPackage: String,
    val callerUid: Int,
    val tokenLabel: String,
    /** 已脱敏、已截断的命令摘要 */
    val commandSummary: String,
    val exitCode: Int,
    val durationMs: Long,
    /** [HistoryStatus] 之一 */
    val status: String,
    /** 错误码或拒绝原因；成功时为空 */
    val reason: String?,
    val truncated: Boolean,
    /**
     * 调用方身份的校验方式：
     * - [VERIFY_CALLER]：系统给出了广播发送方（API 34+），做了真实调用方比对；
     * - [VERIFY_STATIC]：系统给不出发送方，只能校验「绑定包当前签名是否仍与令牌一致」。
     */
    val verifyMode: String = VERIFY_STATIC,
) {
    companion object {
        const val VERIFY_CALLER = "caller"
        const val VERIFY_STATIC = "static"
    }
}

/**
 * 历史筛选（规格 F5「可按调用方、结果状态、时间筛选」）。
 *
 * 说明：既有「API 调用日志」页（`LogsFragment`）其实**没有**筛选能力 —— 它只是
 * `tail -n 200` 的行列表。所以这里按规格自己实现一份筛选，历史数据也另存一份
 * （服务没跑的时候 `tail` 是读不到日志文件的，历史页不能因此空白）。
 *
 * @param caller 包名精确匹配；null/空 = 不限
 * @param status [HistoryStatus] 之一；null/空 = 不限
 * @param since 起始时间戳（毫秒）；null/<=0 = 不限
 */
fun filterHistory(
    entries: List<HistoryEntry>,
    caller: String? = null,
    status: String? = null,
    since: Long? = null,
): List<HistoryEntry> {
    return entries.filter { e ->
        (caller.isNullOrBlank() || e.callerPackage == caller) &&
            (status.isNullOrBlank() || e.status == status) &&
            (since == null || since <= 0L || e.time >= since)
    }
}

/** 历史里出现过的调用方包名（去重、排序），给筛选下拉用。 */
fun historyCallers(entries: List<HistoryEntry>): List<String> =
    entries.map { it.callerPackage }.filter { it.isNotBlank() }.distinct().sorted()
