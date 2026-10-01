package moe.shizuku.manager.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.fdroid.FdroidBuild
import moe.shizuku.manager.utils.NetworkUtils
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UpdateChecker {

    private const val GITHUB_API = "https://api.github.com/repos/cr1437/Shizako/releases/latest"

    /**
     * 更新检查的入口，**按顺序尝试**，第一个拿到合法 JSON 的胜出。
     *
     * 为什么必须有备选：`api.github.com` 在国内网络下经常被 **DNS 层面直接拦掉**。
     * 实测（vivo / Android 16 / 移动网络）：`ping api.github.com` → unknown host，
     * 而 `github.com`、`objects.githubusercontent.com` 都正常 —— 于是更新检查永远失败，
     * 用户永远收不到更新提示（而且失败是静默的，看起来就像「这功能没做」）。
     * 后面几个反代域名在同样的网络下实测可解析，作为兜底。
     */
    private val API_ENDPOINTS = listOfNotNull(
        GITHUB_API,
        // 反代只在 GitHub 版启用：F-Droid 版只走官方 api.github.com
        // （第三方反代属 NonFreeNet 边缘情形，见 fdroid/FdroidBuild.kt）
        "https://gh-proxy.com/$GITHUB_API".takeIf { FdroidBuild.allowUpdateMirrors },
        "https://ghfast.top/$GITHUB_API".takeIf { FdroidBuild.allowUpdateMirrors },
        "https://ghproxy.net/$GITHUB_API".takeIf { FdroidBuild.allowUpdateMirrors },
    )

    /** 上次成功的入口下标：下次先试它，省掉一次注定失败的等待。 */
    private const val KEY_WORKING_ENDPOINT = "update_endpoint_index"

    /** 探测入口时的超时：比下载短，避免 4 个入口串起来等太久。 */
    private const val PROBE_CONNECT_TIMEOUT_MS = 8_000
    private const val PROBE_READ_TIMEOUT_MS = 10_000
    private const val CHANNEL_ID = "shizako_update"
    private const val DOWNLOAD_CHANNEL_ID = "shizako_download"
    private const val NOTIFY_ID = 1001
    private const val DOWNLOAD_NOTIFY_ID = 1003

    private const val TAG = "UpdateChecker"

    private const val MAX_RETRIES = 3
    private const val RETRY_BASE_DELAY_MS = 1_000L

    /**
     * Minimum interval between two notification refreshes (ms).
     * Keeps system load low while still feeling live to the user.
     */
    private const val NOTIFY_THROTTLE_MS = 400L

    /**
     * 「打开 App」自动检查的最小间隔：**60 秒**。
     *
     * 需求是「每次打开都能知道有没有新版本」，所以这里只留一个极短的防抖 ——
     * 防止转屏 / 几秒内来回切前后台连打两次 GitHub。原来是 1 小时，
     * 等于一天最多自动查几次，用户开着 App 也看不到更新。
     */
    private const val APP_OPEN_CHECK_INTERVAL_MS = 60_000L

    /** 前台自动检查是否正在进行：避免并发重复请求。 */
    @Volatile
    private var foregroundCheckInFlight = false

    /** 最近一次成功检查的时间戳（节流用）。 */
    private const val KEY_LAST_AUTO_CHECK_MS = "update_last_check_ms"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    /**
     * Callbacks for UI consumers (e.g. [DownloadProgressDialog]).
     * All methods are invoked on the main thread.
     */
    interface DownloadListener {
        /** [speedBps] is bytes per second, 0 when unknown. */
        fun onProgress(downloaded: Long, total: Long, speedBps: Long) {}
        fun onRetry(attempt: Int, max: Int) {}
        fun onComplete(apkFile: File) {}
        fun onFailed(reason: String) {}
        fun onCancelled() {}
    }

    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val apkUrl: String,
        val apkDirectUrl: String,
        val apkSize: Long
    )

    // ── HTTP helpers ──────────────────────────────────────────────────

    private fun userAgent(): String = "Shizako-Updater/1.0"

    private fun openConnection(
        url: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 15_000,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent())
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        return conn
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * 检查更新。
     *
     * **没网就直接返回**，不卡超时、不报错：
     * - [silent] = true（后台自动检查）：连提示都不弹，等下次定时再试；
     * - [silent] = false（用户手动点「检查更新」）：弹一句"没有网络连接"，说清楚原因。
     */
    fun checkForUpdate(
        context: Context,
        silent: Boolean = false,
        onResult: (ReleaseInfo?) -> Unit,
    ) {
        // F-Droid 版关闭一切更新检查（F-Droid 用自己的密钥签名，自更新在 F-Droid 用户设备上
        // 必然安装失败，且绕过 F-Droid 分发）—— 见 fdroid/FdroidBuild.kt
        if (!FdroidBuild.allowSelfUpdate) {
            onResult(null)
            return
        }

        if (!NetworkUtils.isOnline(context)) {
            if (!silent) {
                Toast.makeText(context, R.string.update_no_network, Toast.LENGTH_SHORT).show()
            }
            onResult(null)
            return
        }
        scope.launch {
            try {
                val info = fetchLatestRelease()
                if (info != null) {
                    // 记录一次成功检查（App 前台节流的依据）
                    ShizukuSettings.getPreferences().edit()
                        .putLong(KEY_LAST_AUTO_CHECK_MS, System.currentTimeMillis()).apply()
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    val current = pi.versionName ?: ""
                    // 只有**确实更新**才算更新：不能只看字符串不相等，
                    // 否则线上 release 比本机旧时会诱导用户降级（详见 isNewerVersion 注释）
                    val hasUpdate = isNewerVersion(info.tagName, current)
                    if (!hasUpdate && info.tagName != current) {
                        Log.i(TAG, "远端 ${info.tagName} 不比本机 $current 新，不提示更新")
                    }
                    withContext(Dispatchers.Main) { onResult(if (hasUpdate) info else null) }
                } else {
                    withContext(Dispatchers.Main) { onResult(null) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!silent) {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.update_check_failed,
                                e.message ?: "network error",
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    onResult(null)
                }
            }
        }
    }

    fun checkAndNotify(context: Context) {
        // 后台自动检查：没网/失败都静默，别在通知栏和屏幕上留噪音
        checkForUpdate(context, silent = true) { info ->
            if (info != null) showUpdateNotification(context, info)
        }
    }

    /**
     * 「打开 App」时的节流自动检查：距上次成功检查超过 [APP_OPEN_CHECK_INTERVAL_MS] 才发请求。
     * 让用户一打开应用就能知道有没有新版本，而不是干等后台闹钟。
     */
    /**
     * 「打开 App」时的自动检查：**每次打开都查**（只留 [APP_OPEN_CHECK_INTERVAL_MS] 的极短防抖）。
     *
     * @param onUpdateFound 发现新版本时回调（主线程）。调用方通常用它就地弹「发现新版本」对话框 ——
     *   只丢通知的话，用户明明开着 App 却看不到能直接下载的入口。
     */
    fun checkOnAppForeground(context: Context, onUpdateFound: ((ReleaseInfo) -> Unit)? = null) {
        if (!ShizukuSettings.isAutoUpdateEnabled()) return
        val last = ShizukuSettings.getPreferences().getLong(KEY_LAST_AUTO_CHECK_MS, 0L)
        if (System.currentTimeMillis() - last < APP_OPEN_CHECK_INTERVAL_MS) return
        if (foregroundCheckInFlight) return
        foregroundCheckInFlight = true
        checkForUpdate(context, silent = true) { info ->
            foregroundCheckInFlight = false
            if (info != null) {
                // 通知照旧留着：用户可能已经把 App 切走了
                showUpdateNotification(context, info)
                onUpdateFound?.invoke(info)
            }
        }
    }

    fun downloadAndInstall(context: Context, info: ReleaseInfo) {
        downloadAndInstall(context, info, null)
    }

    fun downloadAndInstall(context: Context, info: ReleaseInfo, listener: DownloadListener?) {
        // F-Droid 版没有任何「下载并安装 Shizako」的路径：签名不同，装上去也是失败，
        // 还会绕过 F-Droid 的分发。这里挡在最终入口上，UI 之外也堵死（见 fdroid/FdroidBuild.kt）
        if (!FdroidBuild.allowSelfUpdate) return
        if (downloadJob?.isActive == true) return

        // 没网就别开下载：直接告诉用户，别让他对着 0% 的进度条等超时
        if (!NetworkUtils.isOnline(context)) {
            Toast.makeText(context, R.string.update_no_network, Toast.LENGTH_SHORT).show()
            listener?.onFailed(context.getString(R.string.update_no_network))
            return
        }

        ensureChannels(context)

        downloadJob = scope.launch {
            // Indeterminate "connecting" notification first
            withContext(Dispatchers.Main) {
                showProgressNotification(context, downloaded = -1, total = info.apkSize, extra = null)
            }
            try {
                val apkFile = downloadWithRetry(context, info, MAX_RETRIES, listener)
                withContext(Dispatchers.Main) {
                    showCompleteNotification(context, apkFile)
                    listener?.onComplete(apkFile)
                }
            } catch (e: CancellationException) {
                cleanPartialFile(context, "shizako-${info.tagName}.apk")
                cancelDownloadNotification(context)
                // withContext would rethrow immediately on a cancelled job,
                // so dispatch the callback through a fresh child of the scope
                scope.launch(Dispatchers.Main) { listener?.onCancelled() }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showFailedNotification(context, e.message ?: "unknown error")
                    listener?.onFailed(e.message ?: "unknown error")
                }
            }
        }
    }

    /** Cancels an in-flight download, if any. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * 从任意 URL 下载 APK（一键注入页/内网推送场景）：复用更新器的
     * 下载、进度回调、完成通知与自动弹安装流程，UI 与「更新应用」同款。
     */
    fun downloadFromUrl(
        context: Context,
        url: String,
        fileName: String,
        listener: DownloadListener? = null
    ) {
        // 一键注入路径在 F-Droid 版不可用（会下载并安装第三方 APK）
        if (!FdroidBuild.allowOneClickInject) return
        if (downloadJob?.isActive == true) return

        // 离线：直接失败，不要开一个永远 0% 的下载
        if (!NetworkUtils.isOnline(context)) {
            listener?.onFailed(context.getString(R.string.update_no_network))
            return
        }

        ensureChannels(context)

        downloadJob = scope.launch {
            withContext(Dispatchers.Main) {
                showProgressNotification(context, downloaded = -1, total = 0, extra = null)
                listener?.onRetry(0, 0)
            }
            try {
                val apkFile = downloadDirect(context, url, fileName, listener)
                withContext(Dispatchers.Main) {
                    showCompleteNotification(context, apkFile)
                    listener?.onComplete(apkFile)
                }
            } catch (e: CancellationException) {
                cleanPartialFile(context, fileName)
                cancelDownloadNotification(context)
                scope.launch(Dispatchers.Main) { listener?.onCancelled() }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showFailedNotification(context, e.message ?: "unknown error")
                    listener?.onFailed(e.message ?: "unknown error")
                }
            }
        }
    }

    private suspend fun downloadDirect(
        context: Context,
        url: String,
        fileName: String,
        listener: DownloadListener? = null
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
            val apkFile = File(dir, fileName)
            if (apkFile.exists()) apkFile.delete()

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("User-Agent", userAgent())
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }

                val total = conn.contentLengthLong
                val input = conn.inputStream
                val output = FileOutputStream(apkFile)

                try {
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastNotify = 0L
                    var lastNotifyBytes = 0L

                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        downloaded += n

                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= NOTIFY_THROTTLE_MS) {
                            val elapsed = (now - lastNotify).coerceAtLeast(1)
                            val speedBps = if (lastNotify > 0) {
                                (downloaded - lastNotifyBytes) * 1000 / elapsed
                            } else 0L
                            lastNotify = now
                            lastNotifyBytes = downloaded
                            postProgress(context, downloaded, if (total > 0) total else downloaded)
                            val dl = downloaded; val tt = if (total > 0) total else downloaded; val sp = speedBps
                            withContext(Dispatchers.Main) { listener?.onProgress(dl, tt, sp) }
                        }
                    }
                    output.flush()
                    if (apkFile.length() == 0L) throw IOException("downloaded file is empty")
                    postProgress(context, apkFile.length(), if (total > 0) total else apkFile.length())
                    val dl = apkFile.length(); val tt = if (total > 0) total else apkFile.length()
                    withContext(Dispatchers.Main) { listener?.onProgress(dl, tt, 0) }
                } finally {
                    try { output.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                }
            } finally {
                conn.disconnect()
            }
            apkFile
        }

    private fun cleanPartialFile(context: Context, fileName: String) {
        try {
            val f = File(File(context.cacheDir, "updates"), fileName)
            if (f.exists()) f.delete()
        } catch (_: Exception) {
        }
    }

    private fun cancelDownloadNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIFY_ID)
        } catch (_: Exception) {
        }
    }

    // ── Release fetching ──────────────────────────────────────────────

    /**
     * 远端 tag 是否**比本机版本更新**。
     *
     * 以前这里写的是 `tagName != versionName` —— 只判「不相等」，于是：
     * 1. **装了比线上更新的版本时会被诱导降级**：实测线上最新 tag 是 `zako3.02`，
     *    而开发机上是 `zako3.12`，字符串不相等 → 弹「发现新版本 zako3.02」，
     *    用户一点就下回来一个旧 10 个版本的包；
     * 2. tag 带前缀（`v`）或资产名与 tag 不同名时（线上资产叫
     *    `shizako-vzako3.02-release.apk`）判定也会乱。
     *
     * 现在按**数字段逐段比较**：`zako3.12` → [3,12]、`vzako3.02` → [3,2]，只有确实更大才算更新。
     * 数字段相同时再看后缀：带后缀的视为更新（`zako3.01-hp` > `zako3.01`），
     * 否则不算（避免同一版本反复提示）。
     */
    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val remote = versionSegments(remoteTag)
        val local = versionSegments(localVersion)
        // 任一侧解析不出数字就**不乱提示**（宁可不提示，也不能提示错的方向）
        if (remote.isEmpty() || local.isEmpty()) return false

        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }

        val remoteSuffix = versionSuffix(remoteTag)
        val localSuffix = versionSuffix(localVersion)
        return remoteSuffix.isNotEmpty() && remoteSuffix != localSuffix
    }

    /** 取出字符串里所有数字段：`shizako-vzako3.02-release` → [3, 2] */
    private fun versionSegments(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

    /** 取 `-` 之后的后缀（小写）：`zako3.01-hp` → `hp`，`zako3.12` → `` */
    private fun versionSuffix(version: String): String =
        version.substringAfter('-', "").trim().lowercase(Locale.ROOT)

    /**
     * 取最新 release：按 [API_ENDPOINTS] 顺序尝试，先试上次成功的那个。
     *
     * 全部失败才返回 null（调用方按「没检查成功」处理：不写时间戳，下次打开再试）。
     */
    private fun fetchLatestRelease(): ReleaseInfo? {
        val prefs = ShizukuSettings.getPreferences()
        val preferred = prefs.getInt(KEY_WORKING_ENDPOINT, 0)
            .coerceIn(0, API_ENDPOINTS.lastIndex)
        val order = listOf(preferred) + API_ENDPOINTS.indices.filter { it != preferred }

        for (index in order) {
            val info = tryFetchRelease(API_ENDPOINTS[index])
            if (info != null) {
                if (index != preferred) {
                    // 记下来：下次先走这条，少等一次注定失败的探测
                    prefs.edit().putInt(KEY_WORKING_ENDPOINT, index).apply()
                }
                return info
            }
        }
        return null
    }

    /** 单个入口的尝试：任何异常都只记日志并返回 null，由上层换下一个入口。 */
    private fun tryFetchRelease(url: String): ReleaseInfo? {
        val conn = openConnection(url, PROBE_CONNECT_TIMEOUT_MS, PROBE_READ_TIMEOUT_MS).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }

        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "update endpoint ${hostOf(url)} -> HTTP $code")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())

            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            var apkDirectUrl = ""
            var apkSize = 0L

            // Prefer asset whose name contains "release", fall back to any .apk
            for (pass in 0..1) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    val match = if (pass == 0) name.endsWith(".apk") && name.contains("release")
                                else name.endsWith(".apk")
                    if (match) {
                        apkUrl = asset.getString("browser_download_url")
                        apkDirectUrl = asset.getString("url")
                        apkSize = asset.getLong("size")
                        break
                    }
                }
                if (apkUrl.isNotEmpty()) break
            }

            if (apkUrl.isEmpty()) return null

            return ReleaseInfo(
                tagName = json.getString("tag_name"),
                name = json.optString("name", json.getString("tag_name")),
                body = json.optString("body", ""),
                apkUrl = apkUrl,
                apkDirectUrl = apkDirectUrl,
                apkSize = apkSize
            )
        } catch (e: Exception) {
            // 最常见的就是 UnknownHostException（域名被 DNS 拦掉）：继续试下一个入口
            Log.w(TAG, "update endpoint ${hostOf(url)} failed: ${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    // ── Download with retry ───────────────────────────────────────────

    private suspend fun downloadWithRetry(
        context: Context,
        info: ReleaseInfo,
        maxRetries: Int,
        listener: DownloadListener?
    ): File {
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                // Direct API URL first — avoids the browser redirect chain
                return downloadApk(context, info.apkDirectUrl, info, listener)
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxRetries) {
                    postRetry(context, attempt, maxRetries)
                    withContext(Dispatchers.Main) { listener?.onRetry(attempt, maxRetries) }
                    delay(RETRY_BASE_DELAY_MS * attempt)
                }
            }
        }

        // Last resort: the browser_download_url
        try {
            return downloadApk(context, info.apkUrl, info, listener)
        } catch (e: IOException) {
            if (lastError == null) lastError = e
        }

        throw lastError ?: IOException("download failed after $maxRetries retries")
    }

    private fun postProgress(context: Context, downloaded: Long, total: Long) {
        scope.launch(Dispatchers.Main) {
            showProgressNotification(context, downloaded, total, null)
        }
    }

    private fun postRetry(context: Context, attempt: Int, max: Int) {
        scope.launch(Dispatchers.Main) {
            showProgressNotification(
                context, downloaded = -1, total = 0,
                extra = context.getString(R.string.update_retrying, attempt, max)
            )
        }
    }

    // ── Core download ─────────────────────────────────────────────────

    private suspend fun downloadApk(
        context: Context,
        urlString: String,
        info: ReleaseInfo,
        listener: DownloadListener?
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        val apkFile = File(dir, "shizako-${info.tagName}.apk")
        if (apkFile.exists()) apkFile.delete()

        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", userAgent())
            if (urlString.contains("api.github.com")) {
                conn.setRequestProperty("Accept", "application/octet-stream")
            }
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(120)
                } catch (_: Exception) { null }
                throw IOException(if (errBody != null) "HTTP $code: $errBody" else "HTTP $code")
            }

            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.apkSize
            val input = conn.inputStream
            val output = FileOutputStream(apkFile)

            try {
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                var lastNotify = 0L
                var lastNotifyBytes = 0L

                while (true) {
                    // Throws CancellationException promptly when the job is cancelled
                    ensureActive()

                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    downloaded += n

                    val now = System.currentTimeMillis()
                    if (now - lastNotify >= NOTIFY_THROTTLE_MS) {
                        val elapsed = (now - lastNotify).coerceAtLeast(1)
                        val speedBps = if (lastNotify > 0) {
                            (downloaded - lastNotifyBytes) * 1000 / elapsed
                        } else 0L

                        lastNotify = now
                        lastNotifyBytes = downloaded

                        postProgress(context, downloaded, total)
                        val dl = downloaded; val tt = total; val sp = speedBps
                        withContext(Dispatchers.Main) { listener?.onProgress(dl, tt, sp) }
                    }
                }
                output.flush()

                if (apkFile.length() == 0L) throw IOException("downloaded file is empty")
                postProgress(context, apkFile.length(), if (total > 0) total else apkFile.length())
                val dl = apkFile.length(); val tt = if (total > 0) total else apkFile.length()
                withContext(Dispatchers.Main) { listener?.onProgress(dl, tt, 0) }
            } finally {
                try { output.close() } catch (_: Exception) {}
                try { input.close() } catch (_: Exception) {}
            }
        } finally {
            conn.disconnect()
        }

        apkFile
    }

    // ── Installation ──────────────────────────────────────────────────

    private fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update_file_provider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun installNow(context: Context, apkFile: File) {
        try {
            context.startActivity(buildInstallIntent(context, apkFile))
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.update_install_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 拉起系统安装器（FileProvider URI，和「更新 Shizako」走同一条路）。
     *
     * 给「推荐应用」下载页复用：那边用 [downloadFromUrl] 下完 APK 后调这里安装，
     * 观感和更新自己完全一致。
     */
    fun installApk(context: Context, apkFile: File) {
        installNow(context, apkFile)
    }

    // ── Notifications (official setProgress pattern) ──────────────────
    // Pattern per https://developer.android.com/develop/ui/views/notifications/build-notification#progressbar

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
            nm.createNotificationChannel(
                NotificationChannel(DOWNLOAD_CHANNEL_ID, context.getString(R.string.update_download_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun canNotify(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    /**
     * @param downloaded -1 = indeterminate (connecting / retrying)
     */
    private fun showProgressNotification(context: Context, downloaded: Long, total: Long, extra: String?) {
        if (!canNotify(context)) return

        val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.update_downloading))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when {
            extra != null -> {
                // Retrying — indeterminate
                builder.setContentText(extra)
                    .setProgress(0, 0, true)
            }
            downloaded < 0 || total <= 0 -> {
                // Connecting — indeterminate
                builder.setContentText(context.getString(R.string.update_connecting))
                    .setProgress(0, 0, true)
            }
            else -> {
                val pct = (downloaded * 100 / total).toInt()
                builder.setContentText(
                    context.getString(
                        R.string.update_downloading_detail,
                        formatSize(downloaded), formatSize(total), pct
                    )
                )
                    .setProgress(100, pct, false)
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(DOWNLOAD_NOTIFY_ID, builder.build())
        } catch (_: SecurityException) {}
    }

    private fun showCompleteNotification(context: Context, apkFile: File) {
        val pi = PendingIntent.getActivity(
            context, DOWNLOAD_NOTIFY_ID,
            buildInstallIntent(context, apkFile),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_download_complete))
            .setContentText(context.getString(R.string.update_tap_to_install, formatSize(apkFile.length())))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setProgress(0, 0, false)   // clears the bar per official docs
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            NotificationManagerCompat.from(context).notify(DOWNLOAD_NOTIFY_ID, builder.build())
        } catch (_: SecurityException) {}

        // Also fire the installer directly so the user gets it right away
        installNow(context, apkFile)
    }

    private fun showFailedNotification(context: Context, reason: String) {
        val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.update_download_failed_title))
            .setContentText(reason)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            NotificationManagerCompat.from(context).notify(DOWNLOAD_NOTIFY_ID, builder.build())
        } catch (_: SecurityException) {}

        Toast.makeText(context, context.getString(R.string.update_download_failed, reason), Toast.LENGTH_LONG).show()
    }

    private fun showUpdateNotification(context: Context, info: ReleaseInfo) {
        ensureChannels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra("check_update", true)

        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = context.getString(R.string.update_available_text, info.tagName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (_: SecurityException) {}
    }

    // ── Utils ─────────────────────────────────────────────────────────

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}