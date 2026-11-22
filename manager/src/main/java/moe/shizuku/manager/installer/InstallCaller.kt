package moe.shizuku.manager.installer

import android.app.Activity
import android.content.Context
import android.os.Process
import moe.shizuku.manager.utils.AppSignatures

/**
 * 从「谁启动了我们」里读出调用方身份（规格 F2 的数据来源）。
 *
 * 公开 API 只有 [Activity.getLaunchedFromUid] / [Activity.getLaunchedFromPackage]（API 17+）：
 * 这是 Activity 启动链上 system_server 填进去的**真实**启动方，`getCallingPackage()`
 * 在这里不可靠（`startActivity` 不带 result 时它是 null）。
 *
 * 三种特殊身份的处理：
 * - `uid == 本应用` → [CallerKind.SELF]（设置页批量安装、我们自己的中转页）；
 * - `uid == 1000`（或包名 `android`）→ [CallerKind.SYSTEM]：`ACTION_VIEW` 的
 *   「用其他应用打开」是 Resolver（system_server）起我们的，用户已经在系统 UI 里
 *   选过本应用，等价于显式操作；
 * - `uid <= 0`（拿不到）→ 返回 null，判定层直接拒绝。
 */
object InstallCaller {

    fun fromActivity(activity: Activity): CallerInfo? {
        val uid = activity.launchedFromUid
        if (uid <= 0) return null
        val context = activity.applicationContext
        val launchedPackage = activity.launchedFromPackage?.takeIf { it.isNotBlank() }
        return of(context, uid, launchedPackage)
    }

    /** 本应用自己（UI 手动触发）。 */
    fun self(context: Context): CallerInfo = CallerInfo(
        uid = Process.myUid(),
        packageName = context.packageName,
        signatureHash = null,
        kind = CallerKind.SELF,
    )

    fun of(context: Context, uid: Int, packageName: String?): CallerInfo {
        val resolved = packageName ?: packageForUid(context, uid)
        val kind = when {
            uid == Process.myUid() -> CallerKind.SELF
            uid == Process.SYSTEM_UID || resolved == "android" -> CallerKind.SYSTEM
            else -> CallerKind.APP
        }
        // 只有第三方应用需要签名（SELF/SYSTEM 走的是「用户显式操作」这一档）
        val signature = if (kind == CallerKind.APP && resolved != null) {
            AppSignatures.packageHash(context, resolved)
        } else {
            null
        }
        return CallerInfo(uid = uid, packageName = resolved, signatureHash = signature, kind = kind)
    }

    /**
     * `launchedFromPackage` 为空时按 uid 反查：**只有一个包**才采用。
     * 共享 uid（`sharedUserId`）下多个包共用一个 uid，随便挑一个会把「A 应用请求」
     * 记成「B 应用请求」，所以拿不准就保持 null（判定层会走「未受信任 → 询问」）。
     */
    private fun packageForUid(context: Context, uid: Int): String? = try {
        context.packageManager.getPackagesForUid(uid)?.singleOrNull()
    } catch (t: Throwable) {
        null
    }

    /** 展示用：应用名（拿不到就退回包名）。 */
    fun labelOf(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "?"
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (t: Throwable) {
            packageName
        }
    }

    /** 签名摘要的短展示（弹窗里给用户看个可辨的前缀即可）。 */
    fun shortSignature(hash: String?): String = when {
        hash.isNullOrBlank() -> "—"
        hash.length <= 16 -> hash
        else -> hash.substring(0, 16) + "…"
    }
}
