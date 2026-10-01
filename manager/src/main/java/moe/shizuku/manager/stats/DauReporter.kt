package moe.shizuku.manager.stats

import android.content.Context
import android.os.Build
import android.util.Log
import moe.shizuku.manager.ShizukuSettings
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 匿名日活上报（DAU）。
 *
 * 目的：知道「每天有多少台设备在用」，除此之外什么都不收集。
 *
 * 隐私设计（这也是对外声明里要写的部分）：
 * - 上报的标识是 `sha256(安装随机ID + ":" + 当天UTC日期)`；
 *   服务端因此**只能在同一天内去重**（这正是 DAU 的定义），**无法跨天把同一个人关联起来**；
 * - 内容只有：日期、上面那个哈希、版本名、系统 API 级别、CPU 架构；
 * - **没有**账号、设备号、IMEI、Android ID、位置、已安装应用列表 —— 一个都没有；
 * - 未接受向导里的说明（[consent]）或用户在设置里关掉开关时，**完全不上报**；
 * - [ENDPOINT] 还是占位值时也完全不上报（所以没部署接收端时这个功能等于不存在）。
 *
 * 接收端见 `tools/dau-worker/`（Cloudflare Worker + 归档到 GitHub 仓库）。
 */
object DauReporter {

    private const val TAG = "DauReporter"

    /**
     * 接收端地址。部署好 `tools/dau-worker/` 之后把这里改成你的 Worker 域名。
     * 保持占位值 = 不上报。
     */
    private const val ENDPOINT = "https://shizako-dau.shizakoapi.workers.dev/ping"

    private const val KEY_INSTALL_ID = "dau_install_id"
    private const val KEY_LAST_DAY = "dau_last_day"
    private const val KEY_CONSENT = "dau_consent"
    private const val KEY_ENABLED = "dau_enabled"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    private val executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "dau-report").apply { isDaemon = true } } }

    /** 向导里用户看过说明并同意（向导免责声明页调用）。 */
    fun setConsent(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONSENT, true).apply()
    }

    fun hasConsent(context: Context): Boolean = prefs(context).getBoolean(KEY_CONSENT, false)

    /** 设置里的开关。默认开（前提是已同意）。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 尝试上报一次。所有前置条件不满足就直接返回，调用方不需要关心结果。
     * 建议在「用户真正打开应用」的时机调用（MainActivity.onResume），这样统计到的才是活跃用户。
     */
    fun maybeReport(context: Context) {
        if (ENDPOINT.contains("YOUR-SUBDOMAIN")) return          // 没配置接收端 → 不上报
        val appContext = context.applicationContext
        if (!hasConsent(appContext) || !isEnabled(appContext)) return

        val day = todayUtc()
        val p = prefs(appContext)
        if (p.getString(KEY_LAST_DAY, null) == day) return        // 今天已经报过

        // 先落标记再发：宁可少报一次，也不要因为失败而在同一天里反复重试
        p.edit().putString(KEY_LAST_DAY, day).apply()

        val installId = p.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_INSTALL_ID, it).apply()
        }
        val hash = sha256("$installId:$day")
        val body = buildString {
            append("{\"d\":\"").append(day).append("\",")
            append("\"h\":\"").append(hash).append("\",")
            append("\"v\":\"").append(escape(BuildConfigVersion.NAME)).append("\",")
            append("\"os\":").append(Build.VERSION.SDK_INT).append(',')
            append("\"a\":\"").append(escape(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")).append("\"}")
        }

        executor.execute { post(appContext, body) }
    }

    private fun post(context: Context, body: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "Shizako-DAU/1.0")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (BuildConfigDebug) Log.d(TAG, "dau ping -> HTTP $code")
            // 读掉响应，便于连接复用/正常关闭
            runCatching { conn.inputStream?.close() }
        } catch (e: Throwable) {
            // 上报失败绝不影响任何功能，也不打扰用户
            if (BuildConfigDebug) Log.d(TAG, "dau ping failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun prefs(context: Context) = ShizukuSettings.getPreferences()

    private fun todayUtc(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun escape(s: String): String = s.replace("\\", "").replace("\"", "")

    // 让本文件不直接依赖 BuildConfig（便于测试/复用），也避免 DEBUG 判断散落各处
    private object BuildConfigVersion {
        val NAME: String = moe.shizuku.manager.BuildConfig.VERSION_NAME ?: ""
    }

    private val BuildConfigDebug: Boolean = moe.shizuku.manager.BuildConfig.DEBUG
}
