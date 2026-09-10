package moe.shizuku.manager.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import moe.shizuku.manager.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UpdateChecker {

    private const val GITHUB_API = "https://api.github.com/repos/xm1437/Shizako/releases/latest"
    private const val CHANNEL_ID = "shizako_update"
    private const val DOWNLOAD_CHANNEL_ID = "shizako_download"
    private const val NOTIFY_ID = 1001
    private const val DOWNLOAD_NOTIFY_ID = 1003

    private const val MAX_RETRIES = 3
    private const val RETRY_BASE_DELAY_MS = 1_000L

    /**
     * Minimum interval between two notification refreshes (ms).
     * Keeps system load low while still feeling live to the user.
     */
    private const val NOTIFY_THROTTLE_MS = 400L

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

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent())
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        return conn
    }

    // ── Public API ────────────────────────────────────────────────────

    fun checkForUpdate(context: Context, onResult: (ReleaseInfo?) -> Unit) {
        scope.launch {
            try {
                val info = fetchLatestRelease()
                if (info != null) {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    val current = pi.versionName ?: ""
                    val hasUpdate = info.tagName != current
                    withContext(Dispatchers.Main) { onResult(if (hasUpdate) info else null) }
                } else {
                    withContext(Dispatchers.Main) { onResult(null) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_check_failed, e.message ?: "network error"),
                        Toast.LENGTH_SHORT
                    ).show()
                    onResult(null)
                }
            }
        }
    }

    fun checkAndNotify(context: Context) {
        checkForUpdate(context) { info ->
            if (info != null) showUpdateNotification(context, info)
        }
    }

    fun downloadAndInstall(context: Context, info: ReleaseInfo) {
        downloadAndInstall(context, info, null)
    }

    fun downloadAndInstall(context: Context, info: ReleaseInfo, listener: DownloadListener?) {
        if (downloadJob?.isActive == true) return

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
        if (downloadJob?.isActive == true) return
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

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = openConnection(GITHUB_API).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }

        try {
            if (conn.responseCode != 200) return null
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
        } finally {
            conn.disconnect()
        }
    }

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