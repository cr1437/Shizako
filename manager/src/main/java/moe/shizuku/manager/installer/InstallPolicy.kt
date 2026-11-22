package moe.shizuku.manager.installer

/**
 * 静默安装的信任判定（规格 F2）。**纯逻辑、无 Android 依赖**，方便单测穷举。
 *
 * 判定顺序就是规格里的三条：
 * 1. 调用方是本应用 UI 手动触发 → 允许（视为用户显式操作）；
 * 2. 外部应用 → UID + 签名双匹配信任列表 → 静默；匹配不上 → 弹确认框；
 * 3. 无法获取调用方 → 拒绝。
 *
 * 与规格的一处**收紧**：信任列表条目的包名/UID 对上了、但当前签名与记录不一致时，
 * 直接拒绝（不弹确认框）。规格的验收清单写的是「UID 匹配但签名不符 → 拒绝」，
 * 这是 UID 复用（应用卸载后系统把 UID 分给别的应用）与「换签重装」两种攻击的落点，
 * 给用户一个「允许本次」的按钮等于把这道闸重新打开。
 */

/** 调用方身份的种类。 */
enum class CallerKind {
    /** 本应用自己（设置页批量安装、更新安装中转）。 */
    SELF,

    /** system_server（Resolver「用其他应用打开」）。 */
    SYSTEM,

    /** 普通第三方应用。 */
    APP,

    /** 拿不到调用方（`getLaunchedFromUid()` 返回 -1 等）。 */
    UNKNOWN,
}

/** 判定时需要的调用方信息；[signatureHash] 是**当前安装**的签名摘要（不是历史记录里的）。 */
data class CallerInfo(
    val uid: Int,
    val packageName: String?,
    val signatureHash: String?,
    val kind: CallerKind,
)

/** 判定结果的稳定代码：写日志、单测断言都用它，展示文案由 UI 层查资源。 */
object TrustCodes {
    const val SELF = "self"
    const val SYSTEM = "system"
    const val TRUSTED = "trusted"
    const val UNTRUSTED = "untrusted"
    const val NO_CALLER = "no_caller"
    const val PKG_MISMATCH = "pkg_mismatch"
    const val UID_MISMATCH = "uid_mismatch"
    const val SIG_UNAVAILABLE = "sig_unavailable"
    const val SIG_MISMATCH = "sig_mismatch"
}

/**
 * 一次判定：[allow] 直接放行，[needsConfirm] 弹确认框，两者都为 false 表示拒绝。
 * 拒绝时 [code] 是 [TrustCodes] 之一，用来区分「拿不到调用方 / 包名不符 / 签名不符」。
 */
data class TrustDecision(
    val allow: Boolean,
    val needsConfirm: Boolean,
    val code: String,
    val detail: String? = null,
) {
    val denied: Boolean
        get() = !allow && !needsConfirm

    companion object {
        fun allow(code: String) = TrustDecision(allow = true, needsConfirm = false, code = code)
        fun ask(code: String) = TrustDecision(allow = false, needsConfirm = true, code = code)
        fun deny(code: String, detail: String? = null) =
            TrustDecision(allow = false, needsConfirm = false, code = code, detail = detail)
    }
}

object InstallPolicy {

    /**
     * @param caller 调用方（拿不到传 null）
     * @param trusted 信任列表里按「包名 + uid」命中的条目（没有传 null）
     * @param currentSignature 调用方包**当前**的签名摘要（包被换签/卸载时为 null）
     */
    fun decide(
        caller: CallerInfo?,
        trusted: TrustedInstaller?,
        currentSignature: String?,
    ): TrustDecision {
        if (caller == null || caller.kind == CallerKind.UNKNOWN) {
            return TrustDecision.deny(TrustCodes.NO_CALLER)
        }
        if (caller.kind == CallerKind.SELF) {
            return TrustDecision.allow(TrustCodes.SELF)
        }
        if (caller.kind == CallerKind.SYSTEM) {
            return TrustDecision.allow(TrustCodes.SYSTEM)
        }

        // 以下是普通第三方应用。
        if (trusted == null) {
            return TrustDecision.ask(TrustCodes.UNTRUSTED)
        }
        // 包名必须与记录一致：信任列表按包名 + uid 存，只有 uid 对上说明可能是 UID 复用。
        if (caller.packageName == null || caller.packageName != trusted.packageName) {
            return TrustDecision.deny(
                TrustCodes.PKG_MISMATCH,
                "${caller.packageName ?: "null"} != ${trusted.packageName}",
            )
        }
        if (caller.uid != trusted.uid) {
            return TrustDecision.deny(TrustCodes.UID_MISMATCH, "${caller.uid} != ${trusted.uid}")
        }
        // 签名：当前签名必须与记录一致；读不到当前签名同样按失败处理（fail closed）。
        if (currentSignature == null) {
            return TrustDecision.deny(TrustCodes.SIG_UNAVAILABLE)
        }
        if (!currentSignature.equals(trusted.signatureHash, ignoreCase = true)) {
            return TrustDecision.deny(
                TrustCodes.SIG_MISMATCH,
                short(currentSignature) + " != " + short(trusted.signatureHash),
            )
        }
        return TrustDecision.allow(TrustCodes.TRUSTED)
    }

    /** 记录里的判定来源；放行时决定历史里的 `source`。 */
    fun sourceOf(decision: TrustDecision): InstallSource = when (decision.code) {
        TrustCodes.SELF -> InstallSource.UI
        TrustCodes.SYSTEM -> InstallSource.SYSTEM
        TrustCodes.TRUSTED -> InstallSource.TRUSTED_APP
        else -> InstallSource.USER_APPROVED
    }

    private fun short(hash: String): String =
        if (hash.length <= 16) hash else hash.substring(0, 16) + "…"
}
