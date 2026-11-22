package moe.shizuku.manager.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * `com.churan.shizako.action.EXEC_SHELL` 的接收器（规格 F2）。
 *
 * 三重校验，缺一不可：
 * 1. **显式组件**：`intent.component` 必须指向本类。隐式广播一律拒绝（只记日志）——
 *    Manifest 里保留 `<intent-filter>` 的唯一目的是让隐式调用也能被「看见并留痕」，
 *    而不是被系统静默丢弃。
 * 2. **令牌**：`token` extra 与磁盘上的密文解密后定时比较；命中才拿到绑定的包名+签名。
 * 3. **签名**：
 *    - 系统给出了发送方（**API 34+** 才有 `getSentFromUid()/getSentFromPackage()`）→
 *      要求 sender == 令牌绑定的包，并且绑定包当前的签名仍与令牌记录一致；
 *    - 系统给不出（API ≤33）→ 退化为「静态校验」：只验证绑定包当前的签名没变。
 *
 * 关于第 3 点的平台事实（这一点规格里写得比 Android 实际能力乐观）：
 * `BroadcastReceiver` **没有** `getCallingPackage()`（那是 Activity/Service 的 API，
 * 广播投递不经过 binder 调用，所以也拿不到 `Binder.getCallingUid()`）。API 34 之前
 * 系统根本不把「谁发的广播」暴露给接收器，所以「签名不匹配」在旧系统上只能通过
 * 「绑定包被换签重装」来触发。无论哪种模式，**结果广播永远只发给令牌绑定的包**，
 * 因此即使令牌泄漏，攻击者也拿不到命令输出。
 */
class ExecShellReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ShizakoAutomation"

        /**
         * 降级路径（后台不让起前台服务）里命令的最长执行时间。
         * 后台广播 + goAsync 的硬上限是 60s，留 10s 给收尾。
         */
        private const val FALLBACK_TIMEOUT_MS = 50_000
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutomationContract.ACTION_EXEC_SHELL) return
        val app = context.applicationContext

        val requestId = intent.getStringExtra(AutomationContract.EXTRA_REQUEST_ID)
        val command = intent.getStringExtra(AutomationContract.EXTRA_COMMAND)
        val tokenPlain = intent.getStringExtra(AutomationContract.EXTRA_TOKEN)
        val timeoutRaw = intent.getIntExtra(AutomationContract.EXTRA_TIMEOUT_MS, 0)

        val (callerPackage, callerUid) = resolveCaller(app)
        val verifyMode = if (callerPackage != null) {
            HistoryEntry.VERIFY_CALLER
        } else {
            HistoryEntry.VERIFY_STATIC
        }

        // ① 显式组件：连回给谁都不确定，只留日志
        if (!isExplicit(intent)) {
            AutomationRunner.reject(
                context = app,
                requestId = requestId.orEmpty(),
                callerPackage = callerPackage,
                callerUid = callerUid,
                tokenLabel = "",
                command = command,
                error = AutomationContract.REASON_NOT_EXPLICIT,
                detail = "必须用 Intent.setComponent() 显式指定接收器",
                verifyMode = verifyMode,
                notifyPackage = null,
            )
            return
        }

        // ② 必填项：规格明确 BAD_REQUEST 不回传
        val parsed = parseRequest(requestId, command, tokenPlain, timeoutRaw)
        if (parsed.error != null || parsed.request == null) {
            AutomationRunner.reject(
                context = app,
                requestId = requestId.orEmpty(),
                callerPackage = callerPackage,
                callerUid = callerUid,
                tokenLabel = "",
                command = command,
                error = parsed.error ?: AutomationContract.ERR_BAD_REQUEST,
                detail = "缺少 request_id / command / token，或 request_id 不合法",
                verifyMode = verifyMode,
                notifyPackage = null,
            )
            return
        }

        // ③ 令牌
        val token = parsed.token?.let { AutomationStore.findTokenByPlaintext(app, it) }
        if (token == null) {
            AutomationRunner.reject(
                context = app,
                requestId = parsed.request.requestId,
                callerPackage = callerPackage,
                callerUid = callerUid,
                tokenLabel = "",
                command = parsed.request.command,
                error = AutomationContract.ERR_DENIED_TOKEN,
                detail = "令牌不存在 / 已撤销 / 已轮换",
                verifyMode = verifyMode,
                notifyPackage = callerPackage,
            )
            return
        }

        // ④ 签名
        val signatureProblem = verifySignature(app, token, callerPackage)
        if (signatureProblem != null) {
            AutomationRunner.reject(
                context = app,
                requestId = parsed.request.requestId,
                callerPackage = callerPackage,
                callerUid = callerUid,
                tokenLabel = token.label,
                command = parsed.request.command,
                error = AutomationContract.ERR_DENIED_SIGNATURE,
                detail = signatureProblem,
                verifyMode = verifyMode,
                notifyPackage = callerPackage,
            )
            return
        }

        // ⑤ 白名单 + 元字符
        val decision = AutomationPolicy.evaluate(
            command = parsed.request.command,
            enabledPrefixes = AutomationStore.enabledPrefixes(app),
            allowedMeta = AutomationStore.allowedMetachars(app),
        )
        if (!decision.allowed) {
            AutomationRunner.reject(
                context = app,
                requestId = parsed.request.requestId,
                callerPackage = callerPackage,
                callerUid = callerUid,
                tokenLabel = token.label,
                command = parsed.request.command,
                error = AutomationContract.ERR_DENIED_WHITELIST,
                detail = decision.reason,
                verifyMode = verifyMode,
                notifyPackage = callerPackage ?: token.packageName,
            )
            return
        }

        Log.d(
            TAG,
            "exec_shell accepted rid=${parsed.request.requestId} pkg=${callerPackage ?: token.packageName} " +
                "verify=$verifyMode prefix=${decision.matchedPrefix}",
        )

        // ⑥ 执行：优先前台服务（支持 5–300s），被平台拦下就降级
        val started = AutomationExecService.start(
            context = app,
            token = token,
            request = parsed.request,
            callerPackage = callerPackage,
            callerUid = callerUid,
            verifyMode = verifyMode,
        )
        if (!started) {
            dispatchFallback(app, token, parsed.request, callerPackage, callerUid, verifyMode)
        }
    }

    /**
     * 降级路径：`goAsync()` + 独立线程。
     *
     * 后台广播的 goAsync 上限约 60s，所以把超时压到 [FALLBACK_TIMEOUT_MS]；
     * 命令本身没变，只是「最长等待时间」变短了 —— 超时仍会如实回 TIMEOUT。
     */
    private fun dispatchFallback(
        context: Context,
        token: TokenRecord,
        request: AutomationRequest,
        callerPackage: String?,
        callerUid: Int,
        verifyMode: String,
    ) {
        val pending = goAsync() ?: return
        val clamped = request.copy(
            timeoutMs = minOf(request.timeoutMs.coerceAtLeast(1), FALLBACK_TIMEOUT_MS),
        )
        Thread({
            try {
                AutomationRunner.executeAuthorized(
                    context = context,
                    token = token,
                    request = clamped,
                    callerPackage = callerPackage,
                    callerUid = callerUid,
                    verifyMode = verifyMode,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "fallback execute failed", t)
            } finally {
                try {
                    pending.finish()
                } catch (t: Throwable) {
                    // 已经被 finish 过就忽略
                }
            }
        }, "shizako-automation-fallback").apply { isDaemon = true }.start()
    }

    /** 要求调用方用 `Intent.setComponent()` 显式指定本接收器。 */
    private fun isExplicit(intent: Intent): Boolean {
        val name = intent.component?.className ?: return false
        return name == AutomationContract.RECEIVER_CLASS_NAME || name == javaClass.name
    }

    /**
     * 取广播发送方。**只有 API 34+ 有这套 API**，取出后还要回到包名。
     *
     * @return (包名, uid)；取不到时包名为 null、uid 为 -1
     */
    private fun resolveCaller(context: Context): Pair<String?, Int> {
        if (Build.VERSION.SDK_INT < 34) return Pair(null, -1)
        return try {
            val uid = getSentFromUid()
            val pkg = getSentFromPackage()
                ?: if (uid > 0) AutomationCrypto.packagesForUid(context, uid).firstOrNull() else null
            Pair(pkg, uid)
        } catch (t: Throwable) {
            Log.w(TAG, "resolveCaller failed: ${t.javaClass.simpleName}")
            Pair(null, -1)
        }
    }

    /**
     * 签名校验。
     *
     * @return null = 通过；否则是写进日志的原因
     */
    private fun verifySignature(
        context: Context,
        token: TokenRecord,
        callerPackage: String?,
    ): String? {
        // 绑定包还在、签名没变 —— 这条在任何 Android 版本上都能查，是「静态校验」的底
        val current = AutomationCrypto.signatureHash(context, token.packageName)
        if (current == null) {
            return "令牌绑定的应用 ${token.packageName} 已卸载"
        }
        if (current != token.signatureHash) {
            return "应用 ${token.packageName} 的签名与令牌记录不符（被换签重装？）"
        }
        // 系统能指出发送方时再比一层：发送方必须就是绑定包
        if (callerPackage != null && callerPackage != token.packageName) {
            return "发送方 $callerPackage 与令牌绑定的 ${token.packageName} 不符"
        }
        return null
    }
}
