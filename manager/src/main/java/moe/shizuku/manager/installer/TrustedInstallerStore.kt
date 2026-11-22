package moe.shizuku.manager.installer

import android.content.Context
import android.content.SharedPreferences
import moe.shizuku.manager.utils.AppSignatures
import org.json.JSONArray

/**
 * 「信任的安装来源」的持久化（规格 F1）。
 *
 * 存 CE（`MODE_PRIVATE`）而不是 DE：这里的签名摘要与备注属于凭据性数据，
 * 没必要在锁屏前（direct boot）就可读；安装动作本身也要等用户在 UI 里操作，
 * 不存在「开机未解锁就得静默装包」的合法场景。
 *
 * 记录以**包名**为主键：一个包名只会有一个 uid（同一用户下），写入时按包名替换；
 * 判定时要求 uid + 签名同时匹配（见 [InstallPolicy.decide]）—— uid 是「应用被
 * 卸载后复用」的风险点，签名是「换签重装」的风险点，两者都得对。
 */
object TrustedInstallerStore {

    private const val PREFS_NAME = "trusted_installers"
    private const val KEY_ENTRIES = "entries"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cached = p
            return p
        }
    }

    @Synchronized
    fun all(context: Context): List<TrustedInstaller> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<TrustedInstaller>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            runCatching { TrustedInstaller.fromJson(obj) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    fun count(context: Context): Int = all(context).size

    /** 精确查找（判定用）：包名 + uid 都命中才算。 */
    fun find(context: Context, packageName: String?, uid: Int): TrustedInstaller? =
        all(context).firstOrNull { it.packageName == packageName && it.uid == uid }

    /**
     * 按包名查找（**只用于诊断**）：包名命中但 uid 不符时，
     * 判定要报「UID 变了」而不是「不在列表」，否则用户只会看到一句含糊的「未受信任」。
     */
    fun findByPackage(context: Context, packageName: String?): TrustedInstaller? {
        if (packageName.isNullOrBlank()) return null
        return all(context).firstOrNull { it.packageName == packageName }
    }

    /** 用当前安装信息构造一条记录；读不到签名/包名返回 null（不允许把不确定的写进信任列表）。 */
    fun fromCurrentPackage(
        context: Context,
        packageName: String,
        note: String?,
        now: Long = System.currentTimeMillis(),
    ): TrustedInstaller? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull() ?: return null
        val uid = info.applicationInfo?.uid ?: return null
        val sig = AppSignatures.packageHash(context, packageName) ?: return null
        return TrustedInstaller(
            packageName = packageName,
            uid = uid,
            signatureHash = sig,
            note = note,
            addedAt = now,
            lastUsedAt = null,
        )
    }

    /** 写入（按包名覆盖）。[entry] 的签名必须非空，调用方要先从 [fromCurrentPackage] 拿到。 */
    @Synchronized
    fun put(context: Context, entry: TrustedInstaller) {
        val list = all(context).filterNot { it.packageName == entry.packageName } + entry
        write(context, list)
    }

    @Synchronized
    fun remove(context: Context, packageName: String) {
        write(context, all(context).filterNot { it.packageName == packageName })
    }

    /** 更新「最近使用」时间（受信应用每次放行都会刷新，方便排查哪个来源还在用）。 */
    @Synchronized
    fun touch(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        val list = all(context)
        val index = list.indexOfFirst { it.packageName == packageName }
        if (index < 0) return
        val updated = ArrayList(list)
        updated[index] = updated[index].copy(lastUsedAt = now)
        write(context, updated)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun write(context: Context, list: List<TrustedInstaller>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
