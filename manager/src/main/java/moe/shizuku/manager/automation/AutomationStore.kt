package moe.shizuku.manager.automation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 自动化功能的持久化层：令牌 / 白名单 / 元字符放行 / 超时 / 执行历史。
 *
 * 为什么单独一个 prefs 文件而不是塞进 `settings.xml`：
 * - `settings.xml` 在**设备保护存储**（DE，开机解锁前就能读），而令牌属于凭据，
 *   放 CE 更合适（`shared_prefs/automation.xml`）；
 * - 备份/恢复（`SettingsBackupHelper`）只认 `settings.xml` 里的白名单 key，
 *   不会把令牌顺手导出到用户选的 SAF 文件里 —— 凭据不该跟着「设置备份」走。
 *
 * 并发：接收器 + 服务 + 设置页会同时读写，所有读改写都走 [lock]。
 * prefs 的读改写本身不是原子的，少了这层会丢令牌/丢历史。
 */
object AutomationStore {

    private const val TAG = "ShizakoAutomation"
    private const val PREFS_NAME = "automation"

    private const val KEY_TOKENS = "tokens"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_ALLOWED_META = "allowed_meta"
    private const val KEY_TIMEOUT_MS = "timeout_ms"
    private const val KEY_HISTORY = "history"

    private const val MAX_HISTORY = 200

    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun now(): Long = System.currentTimeMillis()

    // ══════════════ 令牌 ══════════════

    fun listTokens(context: Context): List<TokenView> = synchronized(lock) {
        readTokens(context).map { record ->
            val plain = AutomationCrypto.decrypt(record.secretCipher)
            TokenView(
                id = record.id,
                label = record.label,
                packageName = record.packageName,
                signatureHashShort = record.signatureHash.take(8),
                maskedToken = if (plain == null) "" else AutomationCrypto.mask(plain),
                createdAt = record.createdAt,
                lastUsedAt = record.lastUsedAt,
                usedCount = record.usedCount,
                broken = plain == null,
            )
        }
    }

    /**
     * 新建令牌。
     *
     * @param label 备注名（空 → 用包名）
     * @param packageName 绑定的调用方包名
     */
    fun createToken(context: Context, label: String, packageName: String): TokenSecretResult =
        synchronized(lock) {
            val pkg = packageName.trim()
            if (pkg.isEmpty()) return@synchronized TokenSecretResult.Failure("no_package")

            val tokens = readTokens(context)
            if (tokens.size >= AutomationContract.MAX_TOKENS) {
                return@synchronized TokenSecretResult.Failure("limit")
            }
            // 同一个包+同一个签名只留一个有效令牌：重复绑定会让「轮换」失去意义
            val signature = AutomationCrypto.signatureHash(context, pkg)
                ?: return@synchronized TokenSecretResult.Failure("no_signature")
            if (tokens.any { it.packageName == pkg && it.signatureHash == signature }) {
                return@synchronized TokenSecretResult.Failure("duplicate")
            }

            val plaintext = AutomationCrypto.newToken()
            val cipher = try {
                AutomationCrypto.encrypt(plaintext)
            } catch (t: Throwable) {
                Log.w(TAG, "token encrypt failed", t)
                return@synchronized TokenSecretResult.Failure("crypto")
            }

            val record = TokenRecord(
                id = UUID.randomUUID().toString(),
                label = label.trim().ifEmpty { pkg },
                packageName = pkg,
                signatureHash = signature,
                secretCipher = cipher,
                createdAt = now(),
                lastUsedAt = 0L,
                usedCount = 0,
            )
            writeTokens(context, tokens + record)
            TokenSecretResult.Success(record, plaintext)
        }

    /** 轮换：生成新值、旧值立即失效（磁盘上只保留新密文）。 */
    fun rotateToken(context: Context, id: String): TokenSecretResult = synchronized(lock) {
        val tokens = readTokens(context)
        val index = tokens.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized TokenSecretResult.Failure("not_found")

        val plaintext = AutomationCrypto.newToken()
        val cipher = try {
            AutomationCrypto.encrypt(plaintext)
        } catch (t: Throwable) {
            Log.w(TAG, "token encrypt failed", t)
            return@synchronized TokenSecretResult.Failure("crypto")
        }
        val updated = tokens[index].copy(secretCipher = cipher, lastUsedAt = 0L, usedCount = 0)
        writeTokens(context, tokens.toMutableList().also { it[index] = updated })
        TokenSecretResult.Success(updated, plaintext)
    }

    fun revokeToken(context: Context, id: String) = synchronized(lock) {
        val tokens = readTokens(context)
        writeTokens(context, tokens.filterNot { it.id == id })
    }

    /** 收到一次成功请求时记一次「最近使用」。 */
    fun touchToken(context: Context, id: String) = synchronized(lock) {
        val tokens = readTokens(context)
        val index = tokens.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val updated = tokens[index].copy(lastUsedAt = now(), usedCount = tokens[index].usedCount + 1)
        writeTokens(context, tokens.toMutableList().also { it[index] = updated })
    }

    fun findTokenById(context: Context, id: String): TokenRecord? = synchronized(lock) {
        readTokens(context).firstOrNull { it.id == id }
    }

    /**
     * 按明文令牌反查记录。
     *
     * 20 条上限，逐条解密 + 定时比较；够快，也不建任何索引（索引本身会泄漏
     * 「这条令牌存在」的信息）。每条都完整比较，不提前 return，避免时间侧信道。
     */
    fun findTokenByPlaintext(context: Context, plaintext: String): TokenRecord? = synchronized(lock) {
        var found: TokenRecord? = null
        for (record in readTokens(context)) {
            val plain = AutomationCrypto.decrypt(record.secretCipher) ?: continue
            val hit = AutomationCrypto.safeEquals(plain, plaintext)
            if (hit && found == null) found = record
        }
        found
    }

    private fun readTokens(context: Context): List<TokenRecord> {
        val raw = runCatching { prefs(context).getString(KEY_TOKENS, null) }.getOrNull()
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<TokenRecord>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val pkg = o.optString("pkg", "")
            val cipher = o.optString("secret", "")
            if (id.isEmpty() || pkg.isEmpty() || cipher.isEmpty()) continue
            out += TokenRecord(
                id = id,
                label = o.optString("label", pkg),
                packageName = pkg,
                signatureHash = o.optString("sig", ""),
                secretCipher = cipher,
                createdAt = o.optLong("created", 0L),
                lastUsedAt = o.optLong("used", 0L),
                usedCount = o.optInt("count", 0),
            )
        }
        return out
    }

    private fun writeTokens(context: Context, tokens: List<TokenRecord>) {
        val array = JSONArray()
        tokens.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("label", t.label)
                    put("pkg", t.packageName)
                    put("sig", t.signatureHash)
                    put("secret", t.secretCipher)
                    put("created", t.createdAt)
                    put("used", t.lastUsedAt)
                    put("count", t.usedCount)
                },
            )
        }
        prefs(context).edit().putString(KEY_TOKENS, array.toString()).apply()
    }

    // ══════════════ 命令白名单 ══════════════

    /**
     * 读白名单。第一次访问时把默认项写进磁盘（之后用户禁用的项不会被「读默认」覆盖）。
     *
     * 若将来版本新增默认项，这里会把「磁盘上完全没有的」默认项补进来 ——
     * 默认项不可删除、只能禁用，所以补进来不会违背用户意图。
     */
    fun whitelist(context: Context): List<WhitelistEntry> = synchronized(lock) {
        val stored = readWhitelist(context)
        val missing = AutomationPolicy.DEFAULT_PREFIXES
            .filter { def -> stored.none { it.prefix == def } }
            .map { WhitelistEntry(it, enabled = true, custom = false) }
        val merged = stored + missing
        if (missing.isNotEmpty()) writeWhitelist(context, merged)
        AutomationPolicy.sortForDisplay(merged)
    }

    /** 已启用的前缀（执行时用这个，不是全部）。 */
    fun enabledPrefixes(context: Context): List<String> =
        whitelist(context).filter { it.enabled }.map { it.prefix }

    fun addPrefix(context: Context, raw: String): Boolean = synchronized(lock) {
        val prefix = AutomationPolicy.normalizePrefix(raw)
        if (prefix.isEmpty()) return@synchronized false
        val stored = readWhitelist(context)
        if (stored.any { it.prefix == prefix }) return@synchronized false
        writeWhitelist(context, stored + WhitelistEntry(prefix, enabled = true, custom = true))
        true
    }

    fun removePrefix(context: Context, prefix: String) = synchronized(lock) {
        writeWhitelist(context, readWhitelist(context).filterNot { it.prefix == prefix && it.custom })
    }

    fun setPrefixEnabled(context: Context, prefix: String, enabled: Boolean) = synchronized(lock) {
        writeWhitelist(
            context,
            readWhitelist(context).map { if (it.prefix == prefix) it.copy(enabled = enabled) else it },
        )
    }

    /** 恢复默认：默认项全部启用，自定义项保留（删不删是用户的事）。 */
    fun restoreDefaultWhitelist(context: Context) = synchronized(lock) {
        val custom = readWhitelist(context).filter { it.custom }
        val defaults = AutomationPolicy.DEFAULT_PREFIXES.map {
            WhitelistEntry(it, enabled = true, custom = false)
        }
        writeWhitelist(context, defaults + custom)
    }

    private fun readWhitelist(context: Context): List<WhitelistEntry> {
        val raw = runCatching { prefs(context).getString(KEY_WHITELIST, null) }.getOrNull()
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<WhitelistEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val prefix = o.optString("prefix", "")
            if (prefix.isEmpty()) continue
            out += WhitelistEntry(
                prefix = prefix,
                enabled = o.optBoolean("enabled", true),
                custom = o.optBoolean("custom", false),
            )
        }
        return out
    }

    private fun writeWhitelist(context: Context, items: List<WhitelistEntry>) {
        val array = JSONArray()
        items.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("prefix", e.prefix)
                    put("enabled", e.enabled)
                    put("custom", e.custom)
                },
            )
        }
        prefs(context).edit().putString(KEY_WHITELIST, array.toString()).apply()
    }

    // ══════════════ 元字符放行 ══════════════

    /** 默认一个都不放行（规格 §四：元字符默认拒绝）。 */
    fun allowedMetachars(context: Context): Set<String> = synchronized(lock) {
        val raw = runCatching { prefs(context).getString(KEY_ALLOWED_META, null) }.getOrNull()
            ?: return@synchronized emptySet()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptySet()
        (0 until array.length()).mapNotNull { array.optString(it, null) }.toSet()
    }

    fun setMetaAllowed(context: Context, key: String, allowed: Boolean) = synchronized(lock) {
        val current = allowedMetachars(context).toMutableSet()
        if (allowed) current += key else current -= key
        val array = JSONArray()
        current.sorted().forEach { array.put(it) }
        prefs(context).edit().putString(KEY_ALLOWED_META, array.toString()).apply()
    }

    // ══════════════ 超时 ══════════════

    fun timeoutMs(context: Context): Int = synchronized(lock) {
        val raw = runCatching { prefs(context).getInt(KEY_TIMEOUT_MS, 0) }.getOrDefault(0)
        if (raw <= 0) AutomationContract.DEFAULT_TIMEOUT_MS else AutomationContract.clampTimeout(raw)
    }

    fun setTimeoutMs(context: Context, value: Int) = synchronized(lock) {
        prefs(context).edit()
            .putInt(KEY_TIMEOUT_MS, AutomationContract.clampTimeout(value))
            .apply()
    }

    // ══════════════ 执行历史 ══════════════

    /** 新的在前，最多 [MAX_HISTORY] 条。 */
    fun history(context: Context): List<HistoryEntry> = synchronized(lock) {
        val raw = runCatching { prefs(context).getString(KEY_HISTORY, null) }.getOrNull()
            ?: return@synchronized emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@synchronized emptyList()
        val out = ArrayList<HistoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out += HistoryEntry(
                requestId = o.optString("rid", ""),
                time = o.optLong("time", 0L),
                callerPackage = o.optString("pkg", ""),
                callerUid = o.optInt("uid", -1),
                tokenLabel = o.optString("token", ""),
                commandSummary = o.optString("cmd", ""),
                exitCode = o.optInt("exit", Int.MIN_VALUE),
                durationMs = o.optLong("dur", 0L),
                status = o.optString("status", HistoryStatus.ERROR),
                reason = o.optString("reason", "").ifEmpty { null },
                truncated = o.optBoolean("trunc", false),
                verifyMode = o.optString("verify", HistoryEntry.VERIFY_STATIC),
            )
        }
        out
    }

    fun appendHistory(context: Context, entry: HistoryEntry) = synchronized(lock) {
        val current = history(context)
        val next = (listOf(entry) + current).take(MAX_HISTORY)
        val array = JSONArray()
        next.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("rid", e.requestId)
                    put("time", e.time)
                    put("pkg", e.callerPackage)
                    put("uid", e.callerUid)
                    put("token", e.tokenLabel)
                    put("cmd", e.commandSummary)
                    put("exit", e.exitCode)
                    put("dur", e.durationMs)
                    put("status", e.status)
                    put("reason", e.reason ?: "")
                    put("trunc", e.truncated)
                    put("verify", e.verifyMode)
                },
            )
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory(context: Context) = synchronized(lock) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }
}
