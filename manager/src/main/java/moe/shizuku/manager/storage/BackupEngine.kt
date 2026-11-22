package moe.shizuku.manager.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * 单个应用的备份 / 恢复执行器（规格 F3/F4）。
 *
 * 分工：
 * - 文件的「读」全部通过 [StorageProxyClient] 交给特权服务（它才进得去 /Android/data）；
 * - 文件的「写」全部通过 [SafDirectory] 写到用户选的目录（管理器自己就有权限）；
 * - 本类只做流式搬运 + 元数据，不在设备上落中间包。
 *
 * 失败语义：单项备份/恢复失败返回 Outcome（不抛异常），由队列决定「继续下一项」；
 * 只有编程错误才可能抛。
 */
object BackupEngine {

    private const val TAG = "ShizakoStorage"
    private const val BUFFER_SIZE = 64 * 1024

    private const val MIME_TAR_GZ = "application/gzip"
    private const val MIME_JSON = "application/json"

    class CancelledException : RuntimeException("cancelled")

    data class BackupOutcome(
        val success: Boolean,
        val meta: BackupMeta? = null,
        val reason: String? = null,
        val cancelled: Boolean = false,
        /** 成功但有需要告知用户的情况（机器码，UI 负责本地化）。 */
        val note: String? = null,
    )

    /** APK 导出被跳过（系统应用/白名单外路径）：数据备份仍然成功。 */
    const val NOTE_APK_SKIPPED = "apk_skipped"

    /** SAF 目录授权被回收：提示用户重新选目录，而不是当成空目录或未知错误。 */
    const val NOTE_SAF_PERMISSION = "saf_permission"

    data class RestoreOutcome(
        val externalOk: Boolean,
        val obbOk: Boolean,
        val reason: String? = null,
        val obbError: String? = null,
    ) {
        val success: Boolean get() = externalOk
    }

    // ---------------- 备份 ----------------

    fun backup(
        context: Context,
        target: BackupTarget,
        dir: SafDirectory,
        includeObb: Boolean,
        includeApk: Boolean,
        isCancelled: () -> Boolean,
        onStage: (String) -> Unit,
    ): BackupOutcome {
        val startedAt = System.currentTimeMillis()
        val packageName = target.packageName
        val base = BackupNames.base(packageName, target.versionName, startedAt)
        val created = ArrayList<String>()

        var externalFile: String? = null
        var externalBytes = 0L
        var obbFile: String? = null
        var obbBytes = 0L
        var apkFile: String? = null
        var apkSkipped = false

        try {
            if (!StorageProxyClient.available()) {
                return BackupOutcome(false, reason = "NO_SERVICE: service is not running")
            }

            // 1) external data（规格默认范围）
            val dataDir = StorageProxyClient.externalDataDir(packageName)
            if (StorageProxyClient.exists(dataDir)) {
                onStage("external")
                val name = "$base.tar.gz"
                created.add(name)
                externalBytes = streamTarToSaf(dir, name, dataDir, isCancelled)
                externalFile = name
            } else {
                Log.i(TAG, "no external data for $packageName")
            }

            // 2) OBB（与 external 分开打包：恢复时 OBB 失败不影响数据恢复，规格 §五）
            if (includeObb) {
                val obbDir = StorageProxyClient.externalObbDir(packageName)
                if (StorageProxyClient.exists(obbDir)) {
                    onStage("obb")
                    val name = "$base.obb.tar.gz"
                    created.add(name)
                    obbBytes = streamTarToSaf(dir, name, obbDir, isCancelled)
                    obbFile = name
                }
            }

            // 3) APK（可选：用于「未安装时先装回」的闭环，规格 F4）
            //
            // **best-effort**：系统应用的 APK 在 /system、/product 这类**白名单外**路径，
            // 代理会拒绝（`safePath rejected: path is outside allowed roots`）。
            // 这类失败只应该让「这次没带上 APK」，不能让整项备份变 FAILED ——
            // 模拟器上实测：勾了「同时保存 APK」备份系统应用时，整项直接 Failed。
            if (includeApk) {
                val sourceDir = sourceApkOf(context, packageName)
                if (sourceDir != null) {
                    onStage("apk")
                    val name = "$base.apk"
                    created.add(name)
                    try {
                        streamFileToSaf(dir, name, sourceDir, isCancelled)
                        apkFile = name
                    } catch (cancelled: CancelledException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        Log.w(TAG, "apk export skipped for $packageName: ${e.message}")
                        created.remove(name)
                        apkSkipped = true
                    }
                }
            }

            // 4) 元数据（最后写：前面的文件都成功了才留下可用的 meta）
            onStage("meta")
            val meta = BackupMeta(
                packageName = packageName,
                versionName = target.versionName,
                versionCode = target.versionCode,
                backupTime = startedAt,
                androidVersion = Build.VERSION.SDK_INT,
                includesObb = obbFile != null,
                includesApk = apkFile != null,
                label = target.label,
                externalFile = externalFile,
                obbFile = obbFile,
                apkFile = apkFile,
                externalBytes = externalBytes,
                obbBytes = obbBytes,
            )
            writeMeta(dir, meta)

            return BackupOutcome(
                success = true,
                meta = meta,
                note = if (apkSkipped) NOTE_APK_SKIPPED else null,
            )
        } catch (e: SafAccessException) {
            Log.w(TAG, "backup $packageName: SAF permission lost")
            cleanup(dir, created)
            return BackupOutcome(false, reason = NOTE_SAF_PERMISSION)
        } catch (e: CancelledException) {
            cleanup(dir, created)
            return BackupOutcome(false, reason = "CANCELLED", cancelled = true)
        } catch (e: Throwable) {
            Log.w(TAG, "backup $packageName failed: ${e.message}")
            cleanup(dir, created)
            return BackupOutcome(false, reason = StorageProxyClient.describeFailure(e))
        }
    }

    private fun streamTarToSaf(
        dir: SafDirectory,
        name: String,
        sourceDir: String,
        isCancelled: () -> Boolean,
    ): Long {
        val uri = dir.createFile(name, MIME_TAR_GZ)
        try {
            return dir.openOutput(uri).use { out ->
                ParcelFileDescriptor.AutoCloseInputStream(StorageProxyClient.tarDirectory(sourceDir))
                    .use { input -> copy(input, out, isCancelled) }
            }
        } catch (t: Throwable) {
            dir.delete(name)
            throw t
        }
    }

    private fun streamFileToSaf(
        dir: SafDirectory,
        name: String,
        sourcePath: String,
        isCancelled: () -> Boolean,
    ): Long {
        val uri = dir.createFile(name, "application/vnd.android.package-archive")
        try {
            return dir.openOutput(uri).use { out ->
                ParcelFileDescriptor.AutoCloseInputStream(StorageProxyClient.openFile(sourcePath))
                    .use { input -> copy(input, out, isCancelled) }
            }
        } catch (t: Throwable) {
            dir.delete(name)
            throw t
        }
    }

    private fun copy(input: InputStream, out: OutputStream, isCancelled: () -> Boolean): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw CancelledException()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            out.write(buffer, 0, read)
            total += read
        }
        out.flush()
        return total
    }

    private fun cleanup(dir: SafDirectory, names: List<String>) {
        for (name in names) {
            runCatching { dir.delete(name) }
        }
    }

    private fun writeMeta(dir: SafDirectory, meta: BackupMeta) {
        val uri = dir.createFile(BackupNames.metaFile(meta.packageName), MIME_JSON)
        dir.openOutput(uri).use { out ->
            out.write(meta.toJsonText().toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * 备份用 APK 的真实路径。拆分包（App Bundle）只保存 base.apk：
     * 单装 base 在多数情况下不可用，所以 meta 里只记 includesApk，
     * 恢复时的安装提示会明确写「拆分包请用原安装来源」。
     */
    private fun sourceApkOf(context: Context, packageName: String): String? = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).sourceDir
    }.getOrNull()

    // ---------------- 扫描 / 恢复 ----------------

    /** 列出 SAF 目录里的备份（每个 `<pkg>.meta.json` 算一套）。 */
    fun scanBackups(dir: SafDirectory): List<BackupFiles> {
        val entries = dir.list()
        val byName = entries.associateBy { it.name }
        val result = ArrayList<BackupFiles>()

        for (entry in entries) {
            if (!BackupNames.isMeta(entry.name)) continue
            val text = runCatching {
                dir.openInput(entry.uri).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            val meta = BackupMeta.fromJson(text) ?: continue

            val external = meta.externalFile?.let { byName[it] }
            val obb = meta.obbFile?.let { byName[it] }
            val apk = meta.apkFile?.let { byName[it] }
            result.add(
                BackupFiles(
                    meta = meta,
                    externalUri = external?.uri?.toString(),
                    obbUri = obb?.uri?.toString(),
                    apkUri = apk?.uri?.toString(),
                )
            )
        }
        return result.sortedByDescending { it.meta.backupTime }
    }

    /**
     * 恢复一套备份（调用前 UI 必须已经做过覆盖确认，规格 §四）。
     * OBB 失败单独回报，不影响 external 的结论（规格 §五）。
     */
    fun restore(context: Context, files: BackupFiles, onStage: (String) -> Unit): RestoreOutcome {
        val resolver = context.applicationContext.contentResolver
        var externalOk = false
        var obbError: String? = null

        try {
            if (!StorageProxyClient.available()) {
                return RestoreOutcome(false, false, reason = "NO_SERVICE: service is not running")
            }

            if (files.externalUri != null) {
                onStage("external")
                val fd = resolver.openFileDescriptor(Uri.parse(files.externalUri), "r")
                    ?: throw IllegalStateException("cannot open ${files.externalUri}")
                fd.use {
                    StorageProxyClient.restoreTarDirectory(StorageProxyClient.EXTERNAL_DATA_ROOT, it)
                }
                externalOk = true
            }

            if (files.obbUri != null) {
                onStage("obb")
                runCatching {
                    val fd = resolver.openFileDescriptor(Uri.parse(files.obbUri), "r")
                        ?: throw IllegalStateException("cannot open ${files.obbUri}")
                    fd.use {
                        StorageProxyClient.restoreTarDirectory(StorageProxyClient.EXTERNAL_OBB_ROOT, it)
                    }
                }.onFailure {
                    obbError = if (it is SafAccessException) {
                        NOTE_SAF_PERMISSION
                    } else {
                        StorageProxyClient.describeFailure(it)
                    }
                    Log.w(TAG, "obb restore failed: $obbError")
                }
            }

            if (files.externalUri == null && files.obbUri == null) {
                return RestoreOutcome(false, false, reason = "EMPTY: backup has no data files")
            }

            return RestoreOutcome(externalOk, obbError == null, reason = null, obbError = obbError)
        } catch (e: SafAccessException) {
            Log.w(TAG, "restore ${files.meta.packageName}: SAF permission lost")
            return RestoreOutcome(
                externalOk = externalOk,
                obbOk = obbError == null,
                reason = NOTE_SAF_PERMISSION,
                obbError = obbError,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "restore ${files.meta.packageName} failed: ${e.message}")
            return RestoreOutcome(
                externalOk = externalOk,
                obbOk = obbError == null,
                reason = StorageProxyClient.describeFailure(e),
                obbError = obbError,
            )
        }
    }

    /** 已安装则返回 versionCode，未安装返回 null。 */
    fun installedVersionCode(context: Context, packageName: String): Long? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrNull()
}
