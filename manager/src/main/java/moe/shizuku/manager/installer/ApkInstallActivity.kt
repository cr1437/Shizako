package moe.shizuku.manager.installer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import moe.shizuku.manager.R
import moe.shizuku.manager.batch.ApiAuditLog
import java.io.File
import java.util.Locale

/**
 * 「用其他应用打开」的安装中转：微信/QQ 的 `xxx.apk.1`、文件管理器的 apk、XAPK、APKS，
 * 以及外部应用显式拉起本组件请求静默安装，都从这里进来。
 *
 * 本类只做**判定与编排**，真正的安装链在 [Installer]（准备 → Session → 历史 → 审计）：
 *
 * 1. 先按规格 F2 判定调用方（[InstallCaller] + [InstallPolicy]）；
 *    - 本应用 UI / 系统 Resolver / 信任列表应用 → 直接装；
 *    - 未受信任的外部应用 → 弹 [InstallConfirmActivity]（允许本次 / 加入信任 / 拒绝）；
 *    - 拿不到调用方 / UID 或签名对不上 → 拒绝并写审计日志。
 * 2. 多文件分享（`ACTION_SEND_MULTIPLE`）不直接开装，而是打开批量页并把文件**预置**进队列，
 *    由用户点「开始」—— 规格安全要求「批量队列只能由本应用 UI 手动触发」。
 * 3. 装失败且是普通 APK 时保留原有兜底：退回系统安装器，用户还能手动完成。
 *
 * 页面无 UI（透明主题），结果用 Toast 告知，随即 finish。唯一的例外是要用户确认时，
 * 会起一个带对话框的 [InstallConfirmActivity]。
 */
class ApkInstallActivity : Activity() {

    private data class Incoming(
        val uri: Uri,
        val name: String,
        val size: Long,
        val format: PackageFormat,
    )

    private var caller: CallerInfo? = null
    private var pending: Incoming? = null
    private var pendingBatch: List<Incoming>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // 调用方身份必须在最前面取（launchedFromUid 是这次启动的元数据）
        caller = InstallCaller.fromActivity(this)

        val uris = resolveUris(intent)
        if (uris.isEmpty()) {
            finishWithToast(getString(R.string.apk1_read_failed, "no uri"))
            return
        }
        if (uris.size > 1) {
            Thread({ prepareBatch(uris) }, "apk-install-batch").start()
        } else {
            Thread({ prepareSingle(uris[0]) }, "apk-install").start()
        }
    }

    // ---------------- 读取 / 判定 ----------------

    private fun resolveUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (!list.isNullOrEmpty()) return list.filterNotNull().distinct()
        }
        intent.data?.let { return listOf(it) }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { return listOf(it) }
        return emptyList()
    }

    private fun prepareSingle(uri: Uri) {
        val incoming = inspect(uri) ?: run {
            finishWithToast(getString(R.string.apk1_not_apk))
            return
        }
        pendingBatch = null
        pending = incoming
        decideAndProceed(incoming)
    }

    private fun prepareBatch(uris: List<Uri>) {
        val list = uris.mapNotNull { inspect(it) }
        if (list.isEmpty()) {
            finishWithToast(getString(R.string.apk1_not_apk))
            return
        }
        pending = null
        pendingBatch = list
        decideAndProceed(list.first())
    }

    private fun inspect(uri: Uri): Incoming? {
        val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (!looksLikePackage(name, mime)) return null
        return Incoming(
            uri = uri,
            name = name,
            size = querySize(uri),
            format = PackageFormat.fromFileName(name),
        )
    }

    private fun decideAndProceed(first: Incoming) {
        val trusted = TrustedInstallerStore.findByPackage(this, caller?.packageName)
        val decision = InstallPolicy.decide(caller, trusted, caller?.signatureHash)
        val target = pendingBatch?.let { getString(R.string.installer_batch_count, it.size) } ?: first.name

        when {
            decision.allow -> {
                recordDecision(decision, target, allowed = true)
                ui { startInstall(InstallPolicy.sourceOf(decision)) }
            }

            decision.needsConfirm -> ui { askUser(first) }

            else -> {
                recordDecision(decision, target, allowed = false)
                ui { finishWithToast(denyReason(decision)) }
            }
        }
    }

    private fun askUser(first: Incoming) {
        val target = pendingBatch?.let { getString(R.string.installer_batch_count, it.size) } ?: first.name
        val callerPackage = caller?.packageName
        startActivityForResult(
            InstallConfirmActivity.intent(
                context = this,
                callerLabel = InstallCaller.labelOf(this, callerPackage),
                callerPackage = callerPackage,
                fileName = target,
                sizeText = if (pendingBatch == null) formatSize(first.size) else "",
                format = if (pendingBatch == null) first.format else PackageFormat.APK,
            ),
            REQUEST_CONFIRM,
        )
    }

    @Deprecated("Activity 里只用这一处结果回调，换成 registerForActivityResult 需要 ComponentActivity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIRM) return
        val target = pending?.name ?: pendingBatch?.let { getString(R.string.installer_batch_count, it.size) } ?: ""
        when (InstallConfirmActivity.decisionOf(resultCode, data)) {
            InstallConfirmActivity.DECISION_ONCE -> {
                recordUserDecision("once", target, allowed = true)
                startInstall(InstallSource.USER_APPROVED)
            }

            InstallConfirmActivity.DECISION_TRUST -> {
                val trusted = caller?.packageName?.let {
                    TrustedInstallerStore.fromCurrentPackage(this, it, null)
                }
                if (trusted != null) {
                    TrustedInstallerStore.put(this, trusted)
                    recordUserDecision("trust", target, allowed = true)
                } else {
                    toast(getString(R.string.installer_trust_failed))
                }
                startInstall(InstallSource.TRUSTED_APP)
            }

            else -> {
                recordUserDecision("deny", target, allowed = false)
                finishWithToast(getString(R.string.installer_denied_by_user))
            }
        }
    }

    // ---------------- 执行 ----------------

    private fun startInstall(source: InstallSource) {
        val batch = pendingBatch
        if (batch != null) {
            openBatch(batch, source)
            return
        }
        val incoming = pending ?: run {
            finish()
            return
        }
        toast(getString(R.string.apk1_installing, incoming.name))
        Thread({
            val request = InstallRequest(
                fileUri = incoming.uri.toString(),
                fileName = incoming.name,
                format = incoming.format,
                source = source,
                sourcePackage = caller?.packageName,
                sizeBytes = incoming.size,
            )
            val outcome = Installer.install(this, request)
            ui { handleOutcome(incoming, outcome) }
        }, "apk-install-run").start()
    }

    private fun handleOutcome(incoming: Incoming, outcome: Installer.Outcome) {
        if (outcome.success) {
            // 受信来源每用一次都刷新「最近使用」，信任列表页能看出哪个来源还活着
            if (outcome.packageName != null && caller?.packageName != null &&
                outcome.status == TaskStatus.SUCCESS
            ) {
                runCatching { TrustedInstallerStore.touch(this, caller!!.packageName!!) }
            }
            if (outcome.obbNote != null) {
                toast(outcome.reason ?: getString(R.string.installer_obb_not_installed, outcome.obbNote))
            }
            finishWithToast(getString(R.string.apk1_installed, incoming.name))
            return
        }

        toast(getString(R.string.apk1_install_failed, outcome.reason ?: ""))
        // 兜底：普通 APK 还能走系统安装器手动装；XAPK/APKS 系统安装器不认，不去误导用户
        if (outcome.status != TaskStatus.CANCELLED &&
            (outcome.format == PackageFormat.APK || outcome.format == PackageFormat.APK1)
        ) {
            launchSystemInstaller(incoming.uri, incoming.name)
        } else {
            finish()
        }
    }

    private fun openBatch(batch: List<Incoming>, source: InstallSource) {
        val intent = BatchInstallActivity.intent(
            context = this,
            uris = batch.map { it.uri.toString() },
            names = batch.map { it.name },
            sizes = batch.map { it.size },
            source = source,
            sourcePackage = caller?.packageName,
        )
        runCatching { startActivity(intent) }
            .onFailure { finishWithToast(getString(R.string.apk1_install_failed, it.message ?: "")) }
        finish()
    }

    // ---------------- 审计 ----------------

    private fun recordDecision(decision: TrustDecision, target: String, allowed: Boolean) {
        val detail = "src=install;caller=${caller?.packageName ?: "?"};uid=${caller?.uid ?: -1};" +
            "decision=${decision.code};file=${sanitize(target)};fmt=${pending?.format ?: pendingBatch?.firstOrNull()?.format}"
        runCatching { ApiAuditLog.record(this, "install.request", allowed, detail) }
    }

    private fun recordUserDecision(kind: String, target: String, allowed: Boolean) {
        val detail = "src=install;caller=${caller?.packageName ?: "?"};uid=${caller?.uid ?: -1};" +
            "decision=user_$kind;file=${sanitize(target)};fmt=${pending?.format ?: pendingBatch?.firstOrNull()?.format}"
        runCatching { ApiAuditLog.record(this, "install.request", allowed, detail) }
    }

    // ---------------- 系统安装器兜底 ----------------

    private fun launchSystemInstaller(uri: Uri, name: String) {
        runCatching {
            val dir = File(cacheDir, CACHE_DIR).apply { mkdirs() }
            // 系统安装器兜底用的副本从来没被清理过会一直堆在 cache 里（旧实现有 24h 清理），
            // 这里顺手清掉一天前的
            val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
            dir.listFiles { f -> f.isFile && f.name.startsWith("install-") && f.lastModified() < cutoff }
                ?.forEach { it.delete() }
            val target = File(dir, "install-${System.currentTimeMillis()}.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("cannot open source")
            Log.i(TAG, "fallback to system installer: ${name}")
            ui { startSystemInstaller(target) }
        }.onFailure {
            Log.w(TAG, "fallback failed", it)
            ui { finishWithToast(getString(R.string.apk1_install_failed, it.message ?: "")) }
        }
    }

    private fun startSystemInstaller(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.update_file_provider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MIME_APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            toast(getString(R.string.apk1_install_failed, it.message ?: it.javaClass.simpleName))
        }
        finish()
    }

    // ---------------- 小工具 ----------------

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 扩展名是 apk / apk1 / apk.1 / xapk / apks，或 MIME 是安装包。内容校验在解包时做。 */
    private fun looksLikePackage(name: String?, mime: String?): Boolean {
        val n = (name ?: "").lowercase(Locale.ROOT)
        val byName = n.endsWith(".apk") || n.endsWith(".apk1") || n.endsWith(".apk.1") ||
            n.endsWith(".xapk") || n.endsWith(".apks")
        val byMime = mime == MIME_APK
        return byName || byMime
    }

    private fun denyReason(decision: TrustDecision): String = when (decision.code) {
        TrustCodes.NO_CALLER -> getString(R.string.installer_denied_no_caller)
        TrustCodes.PKG_MISMATCH -> getString(R.string.installer_denied_pkg_mismatch)
        TrustCodes.UID_MISMATCH -> getString(R.string.installer_denied_uid_mismatch)
        TrustCodes.SIG_MISMATCH -> getString(R.string.installer_denied_sig_mismatch)
        TrustCodes.SIG_UNAVAILABLE -> getString(R.string.installer_denied_sig_unavailable)
        else -> getString(R.string.installer_denied_generic)
    }

    private fun sanitize(text: String): String =
        text.replace(';', '_').replace('\n', ' ').replace('\r', ' ').take(120)

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun toast(text: CharSequence) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun finishWithToast(text: CharSequence) {
        ui {
            toast(text)
            finish()
        }
    }

    companion object {
        private const val TAG = "ApkInstall"
        private const val CACHE_DIR = "updates"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private const val MIME_APK = "application/vnd.android.package-archive"
        private const val REQUEST_CONFIRM = 1001
    }
}
