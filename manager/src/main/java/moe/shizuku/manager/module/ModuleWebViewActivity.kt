package moe.shizuku.manager.module

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 模块本地 WebUI 宿主（照搬 Shevery 的 `ModuleWebViewActivity`，UI 用 View 体系重写）。
 *
 * **为什么不直接用 `file://` 加载**：
 * `file://` 页面配合 `allowFileAccessFromFileURLs` 就能用 XHR 读盘上任意可读文件，
 * 光靠 `shouldInterceptRequest` 拦不住（file 方案的拦截行为在各版本上并不一致）。
 * 所以这里学 `WebViewAssetLoader` 的做法：页面跑在一个**不存在的虚拟域名**上
 * （[VIRTUAL_HOST]，`.invalid` 保证永远解析不到），所有资源由
 * [ModuleWebViewClient.shouldInterceptRequest] 从模块目录里读出来喂给 WebView：
 * - 路径先做规范化（去掉 `..` / 空段），再做 `canonicalPath` 前缀校验 ——
 *   既是软链接也跳不出 `webRoot`，**路径穿越一律拒绝**（返回 403）；
 * - WebView 自身的文件访问全部关掉（`allowFileAccess = false`），
 *   页面因此拿不到任何本机文件系统路径；
 * - 联网靠 `blockNetworkLoads` 开关：只有 [ModuleSettings.canWebViewInternet] 为真才放开
 *   （虚拟域名的请求走本地拦截，不需要联网）。
 *
 * 启动方式：`Intent` 里带 [EXTRA_MODULE_ID]，或者直接用 [newIntent]。
 */
class ModuleWebViewActivity : AppActivity() {

    private lateinit var module: AdbModule
    private lateinit var webRoot: File

    private var webView: WebView? = null
    private var internetAllowed = false

    /** 当前主文档 URL（只在 UI 线程写），JS 桥用它判断"还在模块自己的页面上吗" */
    @Volatile
    private var currentPageUrl: String? = null

    /** ReCommand 确认队列：脚本可能连着发好几条命令，一条一条弹 */
    private val pendingReviews = ArrayDeque<PendingReview>()
    private var reviewShowing = false

    private class PendingReview(val command: String) {
        private val latch = CountDownLatch(1)

        @Volatile
        var approved = false
            private set

        fun settle(value: Boolean) {
            approved = value
            latch.countDown()
        }

        /** 等用户在 UI 上选完；超时按拒绝处理，免得 JS 桥线程永远挂着 */
        fun await(seconds: Long): Boolean =
            try {
                latch.await(seconds, TimeUnit.SECONDS) && approved
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent?.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        // 模块 id 必须先在合法性上把关，否则 "../" 之类会让 files/adb_modules/<id> 指到别处
        val loaded = if (MODULE_ID_REGEX.matches(moduleId)) {
            AdbModuleManager.readModule(AdbModuleManager.modulesRoot(this).resolve(moduleId))
        } else {
            null
        }
        val root = loaded?.webRoot
        val index = root?.resolve("index.html")
        if (loaded == null || root == null || index?.isFile != true) {
            toast(getString(R.string.module_webui_unavailable))
            finish()
            return
        }
        // 打开 WebUI 本身也受策略门控制（安全模式下不放行）
        if (!ModuleSettings.canOpenWebUi(loaded)) {
            toast(getString(R.string.module_webui_blocked))
            finish()
            return
        }

        module = loaded
        webRoot = root
        internetAllowed = ModuleSettings.canWebViewInternet(loaded)

        val view = buildContentView()
        setContentView(view)

        // 模块作者调试自己的页面时会用到：debug 包允许 chrome://inspect 连上来
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    // ---------------- UI ----------------

    private fun buildContentView(): View {
        val barHeight = dp(56)
        val barPadding = dp(4)

        val title = TextView(this).apply {
            text = module.name
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = getString(R.string.module_webui_close)
            background = themedDrawable(android.R.attr.selectableItemBackgroundBorderless)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = barHeight
            setPadding(barPadding, 0, barPadding, 0)
            addView(title)
            addView(close)
        }

        val content = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        webView = content
        configureWebView(content)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
            addView(bar)
            addView(content)
        }

        // edge-to-edge（基类已关掉系统自动留白）：顶部让开状态栏，底部让开手势条
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bar.updatePadding(left = barPadding, right = barPadding, top = bars.top)
            root.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // 页面来自虚拟域名，压根不需要文件系统访问权
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            // 联网默认关：本地 WebUI 不需要外网，只有策略放行才打开
            blockNetworkLoads = !internetAllowed
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT

            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            saveFormData = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webViewClient = ModuleWebViewClient()

        // 桥只在策略放行时注入；没注入的页面里 window.Shizuku 就是 undefined
        if (module.enabled && ModuleSettings.canUseShellBridge(module)) {
            view.addJavascriptInterface(
                ModuleJsBridge(
                    module = module,
                    originAllowed = ::isOriginAllowed,
                    commandReviewer = ModuleJsBridge.CommandReviewer { command ->
                        requestCommandReview(command)
                    },
                ),
                JS_INTERFACE_NAME,
            )
        }

        if (ModuleSettings.canWebUiDownload(module)) {
            view.setDownloadListener { url, _, contentDisposition, _, _ ->
                handleWebDownload(url, contentDisposition)
            }
        }

        view.loadUrl("${VIRTUAL_ORIGIN}/index.html")
    }

    // ---------------- WebView 策略 ----------------

    private inner class ModuleWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            currentPageUrl = url
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            currentPageUrl = url
            // 页面加载完再把 window.Shizuku 这层壳注进去（桥对象本身是 addJavascriptInterface 加的）
            view?.evaluateJavascript(BRIDGE_SHIM, null)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            currentPageUrl = url
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase(Locale.ROOT)) {
                // 虚拟域名内部跳转 = WebUI 自己的页面，交给 shouldInterceptRequest 从模块目录取
                "https" -> if (isVirtualHost(uri)) false else handleExternal(uri)
                "http" -> handleExternal(uri)
                // 其他 scheme（intent: / tel: / mailto: …）一律不放行，别让页面把应用带出去
                else -> true
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            // 非虚拟域名：交回 WebView 走网络（开不开网由 blockNetworkLoads 决定）
            if (uri.scheme?.lowercase(Locale.ROOT) != "https" || !isVirtualHost(uri)) return null
            val file = resolveLocalFile(uri) ?: return emptyResponse(403, "Forbidden")
            return try {
                WebResourceResponse(
                    mimeTypeFor(file.name),
                    charsetFor(file.name),
                    200,
                    "OK",
                    mapOf("Cache-Control" to "no-store"), // 模块文件随时会被改，别缓存
                    FileInputStream(file),
                )
            } catch (e: Exception) {
                emptyResponse(404, "Not Found")
            }
        }

        /** http(s) 外链：策略放行才交给浏览器，否则直接挡掉 */
        private fun handleExternal(uri: Uri): Boolean {
            if (!internetAllowed) {
                toast(getString(R.string.module_webui_external_blocked))
                return true
            }
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                true
            }
        }
    }

    /**
     * 把虚拟域名的 URL 映射到模块 WebUI 目录里的文件。
     * 两层校验：先查路径段（`..` / 空段 / NUL），再用 canonicalPath 前缀比对（软链接也逃不掉）。
     */
    private fun resolveLocalFile(uri: Uri): File? {
        val rawPath = uri.encodedPath?.let { Uri.decode(it) } ?: return null
        val relative = rawPath.trim('/').ifBlank { "index.html" }
        val parts = relative.replace('\\', '/').split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) return null

        val target = File(webRoot, relative)
        return try {
            val rootPath = webRoot.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (!targetPath.startsWith(rootPath)) return null
            if (!target.isFile) return null
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun isVirtualHost(uri: Uri): Boolean =
        uri.host?.equals(VIRTUAL_HOST, ignoreCase = true) == true

    /** 桥的准入：当前主文档必须还在模块自己的 WebUI 上 */
    private fun isOriginAllowed(): Boolean {
        val uri = Uri.parse(currentPageUrl ?: return false)
        return uri.scheme?.lowercase(Locale.ROOT) == "https" && isVirtualHost(uri)
    }

    // ---------------- ReCommand 确认 ----------------

    /**
     * 从 JS 桥线程调用：把确认框丢到 UI 线程，然后**阻塞**等用户选完。
     * 桥线程本来就不是 UI 线程，阻塞它是安全的（页面那边是同步 API，语义上也该等结果）。
     */
    private fun requestCommandReview(command: String): Boolean {
        if (isFinishing || isDestroyed) return false
        val review = PendingReview(command)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                review.settle(false)
                return@runOnUiThread
            }
            pendingReviews.addLast(review)
            showNextReviewIfIdle()
        }
        return review.await(REVIEW_WAIT_SECONDS)
    }

    private fun showNextReviewIfIdle() {
        if (reviewShowing || isFinishing || isDestroyed) return
        val next = pendingReviews.pollFirst() ?: return
        reviewShowing = true

        val message = getString(R.string.module_webui_command_message, module.name) + "\n\n" + next.command
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.module_webui_command_title)
            .setMessage(message)
            .setPositiveButton(R.string.module_webui_allow) { _, _ -> next.settle(true) }
            .setNegativeButton(R.string.module_webui_deny) { _, _ -> next.settle(false) }
            .setOnCancelListener { next.settle(false) }
            .setOnDismissListener {
                next.settle(false)
                reviewShowing = false
                // 队列里可能还排着下一条
                webView?.post { showNextReviewIfIdle() }
            }
            .show()
    }

    // ---------------- 下载 ----------------

    /**
     * WebView 的下载回调。只有策略放行时才会挂上来，所以这里再确认一次只是兜底。
     * 落盘位置固定为模块目录下的 `downloads/`（虚拟域名的文件直接从 webRoot 里拷一份）。
     */
    private fun handleWebDownload(url: String, contentDisposition: String?) {
        if (!ModuleSettings.canWebUiDownload(module)) {
            toast(getString(R.string.module_webui_download_blocked))
            return
        }
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") {
            toast(getString(R.string.module_webui_download_blocked))
            return
        }

        val target = File(File(module.directory, DOWNLOAD_DIR).apply { mkdirs() }, guessFileName(uri, contentDisposition))
        toast(getString(R.string.module_webui_download_started))
        Thread {
            try {
                val written = if (isVirtualHost(uri)) {
                    val source = resolveLocalFile(uri) ?: error("Not found in the module WebUI.")
                    source.copyTo(target, overwrite = true)
                    target.length()
                } else {
                    ModuleJsBridge.downloadHttpsToFile(url, target)
                }
                runOnUiThread {
                    toast(getString(R.string.module_webui_download_saved, "${target.absolutePath} ($written B)"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(getString(R.string.module_webui_download_failed, e.message ?: "?"))
                }
            }
        }.start()
    }

    private fun guessFileName(uri: Uri, contentDisposition: String?): String {
        val fromHeader = contentDisposition
            ?.split(';')
            ?.firstOrNull { it.trim().startsWith("filename=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
        val raw = fromHeader?.takeIf { it.isNotBlank() }
            ?: Uri.decode(uri.lastPathSegment ?: "")
        val safe = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
        return safe.ifBlank { "download.bin" }
    }

    // ---------------- 收尾 ----------------

    override fun onDestroy() {
        // 页面还在等确认框的，一律按拒绝结掉，别让 JS 桥线程永远挂着
        while (true) {
            val pending = pendingReviews.pollFirst() ?: break
            pending.settle(false)
        }
        reviewShowing = false

        webView?.let { view ->
            try {
                view.stopLoading()
                view.loadUrl("about:blank")
                (view.parent as? ViewGroup)?.removeView(view)
                view.removeAllViews()
                view.destroy()
            } catch (_: Exception) {
            }
        }
        webView = null
        super.onDestroy()
    }

    // ---------------- 杂项 ----------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return android.graphics.Color.TRANSPARENT
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun themedDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true) || value.resourceId == 0) return null
        return ContextCompat.getDrawable(this, value.resourceId)
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun emptyResponse(statusCode: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            statusCode,
            reason,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)) as InputStream,
        )

    private fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs", "cjs" -> "application/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "txt", "log", "md", "sh", "prop", "cfg", "ini", "csv" -> "text/plain"
            "xml" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "bmp" -> "image/bmp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        }
    }

    /** 文本类给 UTF-8；二进制不给 charset（给了反而会让 Chrome 乱猜） */
    private fun charsetFor(name: String): String? = when (mimeTypeFor(name).substringBefore(';')) {
        "text/html", "text/css", "text/plain", "application/javascript", "application/json",
        "application/xml", "image/svg+xml",
        -> "utf-8"
        else -> null
    }

    companion object {
        /** 启动用的 Intent extra：模块 id（`files/adb_modules/<id>` 里那个目录名） */
        const val EXTRA_MODULE_ID = "module_id"

        /** 注入的原生桥对象名，页面侧由 [BRIDGE_SHIM] 包成 `window.Shizuku` */
        const val JS_INTERFACE_NAME = "ShizukuNative"

        /** 虚拟域名：`.invalid` 是 RFC 2606 保留后缀，永远解析不到，请求全走本地拦截 */
        const val VIRTUAL_HOST = "webui.shizako.invalid"

        private const val VIRTUAL_ORIGIN = "https://$VIRTUAL_HOST"

        private const val DOWNLOAD_DIR = "downloads"

        /** 等用户点确认框的时长（超过就按拒绝处理） */
        private const val REVIEW_WAIT_SECONDS = 300L

        private val MODULE_ID_REGEX = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")

        fun newIntent(context: Context, moduleId: String): Intent =
            Intent(context, ModuleWebViewActivity::class.java).putExtra(EXTRA_MODULE_ID, moduleId)

        /**
         * 页面侧的 `window.Shizuku` 壳：
         * - 只读属性（moduleId / mode / trusted / permissions …）直接从原生对象取一次快照；
         * - `exec` 返回 Promise，内部把跨边界的 JSON 字符串解析成对象；
         * - `execSync` 保留同步调用（返回解析后的对象），给不想用 Promise 的页面；
         * - 注入完派发 `shizuku-ready` 事件，页面可以据此初始化。
         */
        private val BRIDGE_SHIM = """
            (function () {
              var native = window.$JS_INTERFACE_NAME;
              if (!native) return;
              var info = {};
              try { info = JSON.parse(native.getModuleInfo() || '{}') || {}; } catch (e) { info = {}; }
              function toResult(raw) {
                try { return JSON.parse(raw); } catch (e) {
                  return { ok: false, code: -1, exitCode: -1, stdout: '', stderr: String(raw), timedOut: false };
                }
              }
              var api = {
                moduleId: info.id || '',
                name: info.name || '',
                version: info.version || '',
                mode: info.accessMode || 'safe',
                trusted: !!info.trusted,
                enabled: !!info.enabled,
                background: !!info.background,
                permissions: info.permissions || {},
                info: function () { return info; },
                execSync: function (command) { return toResult(native.exec(String(command))); },
                exec: function (command, options) {
                  return new Promise(function (resolve) {
                    resolve((options === undefined || options === null)
                      ? toResult(native.exec(String(command)))
                      : toResult(native.execWithOptions(String(command), JSON.stringify(options))));
                  });
                },
                download: function (url, path) {
                  return new Promise(function (resolve) {
                    resolve(toResult(native.download(String(url), String(path))));
                  });
                }
              };
              window.Shizuku = Object.freeze(api);
              window.dispatchEvent(new Event('shizuku-ready'));
            })();
        """.trimIndent()
    }
}
