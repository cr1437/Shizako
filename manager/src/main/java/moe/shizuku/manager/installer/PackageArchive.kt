package moe.shizuku.manager.installer

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.util.Log
import moe.shizuku.manager.utils.AppSignatures
import moe.shizuku.manager.utils.FileSafety
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 把一个待安装的东西（APK / APK1 / XAPK / APKS）变成「可以写进 PackageInstaller 会话的
 * 私有一份文件」（规格 F6/F7 的解析步骤 1–3）。
 *
 * 做的三件事，顺序不能换：
 * 1. 拷进 `cacheDir/install/<uuid>/`（私有目录，装完删；规格安全要求「解压目录限私有缓存」）；
 * 2. 解析归档，得到 base + split 列表与 OBB 列表（[ArchivePlanner]，纯逻辑）；
 * 3. 校验：base 存在、包名与 manifest 一致、**所有 split 的签名一致**、磁盘空间够。
 *    校验不过就抛 [InstallFailure]，**一个字节都不进会话**。
 *
 * 解压时只用 `ArchivePlanner` 给出的条目名 + 归一化后的会话名写文件，ZIP 里的
 * `../` 之类路径根本不会参与拼接（另加 [FileSafety] 兜底）。
 */
object PackageArchive {

    private const val TAG = "ShizakoInstall"
    private const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST_BYTES = 1L shl 20
    private const val MAX_TOC_BYTES = 8L shl 20
    private const val MIN_FREE_EXTRA = 32L shl 20
    private const val BUFFER = 64 * 1024

    fun prepare(context: Context, uri: Uri, displayName: String): PreparedPackage {
        val workDir = File(context.cacheDir, "install/${UUID.randomUUID()}")
        if (!workDir.mkdirs()) throw InstallFailure(InstallError.IO, "mkdirs failed")
        try {
            val raw = File(workDir, "upload.bin")
            copyUri(context, uri, raw)
            if (raw.length() <= 0L) throw InstallFailure(InstallError.IO, "empty file")

            val names = listEntries(raw)
            val hasToc = names.contains("toc.pb")
            val hasManifestJson = names.contains("manifest.json")
            val hasAndroidManifest = names.contains("AndroidManifest.xml")
            return when {
                // 顺序有意义：XAPK/APKS 里也会有 `AndroidManifest.xml`（在 base.apk 内部，
                // 不在根上），所以先看 toc.pb / manifest.json 这两个根标记
                hasToc -> prepareArchive(context, workDir, raw, names, PackageFormat.APKS)
                hasManifestJson -> prepareArchive(context, workDir, raw, names, PackageFormat.XAPK)
                hasAndroidManifest -> preparePlain(context, workDir, raw, displayName)
                else -> throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "neither apk nor archive")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prepare failed: ${t.message}")
            workDir.deleteRecursively()
            throw t
        }
    }

    // ---------------- 单个 APK ----------------

    private fun preparePlain(
        context: Context,
        workDir: File,
        raw: File,
        displayName: String,
    ): PreparedPackage {
        val splitsDir = File(workDir, "splits").apply { mkdirs() }
        val base = File(splitsDir, ArchivePlanner.BASE_SESSION_NAME)
        if (!raw.renameTo(base)) {
            raw.inputStream().use { input -> base.outputStream().use { input.copyTo(it, BUFFER) } }
            raw.delete()
        }
        if (base.length() <= 0L) throw InstallFailure(InstallError.IO, "empty apk")

        val info = archiveInfo(context, base)
            ?: throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "cannot read AndroidManifest")
        // 单 APK 不比对 split 签名（只有一个），但签名读不到时提前说清楚，
        // 免得 `pm install-commit` 回一句看不懂的 INSTALL_PARSE_FAILED_NO_CERTIFICATES
        if (AppSignatures.archiveHash(context, base) == null) {
            throw InstallFailure(InstallError.SIGNATURE_MISMATCH, "unsigned apk")
        }
        return PreparedPackage(
            workDir = workDir,
            format = PackageFormat.fromFileName(displayName),
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
            splits = listOf(PreparedSplit(ArchivePlanner.BASE_SESSION_NAME, base, isBase = true)),
            obbs = emptyList(),
            planSource = PlanSource.PLAIN_APK,
        )
    }

    // ---------------- XAPK / APKS ----------------

    private fun prepareArchive(
        context: Context,
        workDir: File,
        raw: File,
        names: List<String>,
        format: PackageFormat,
    ): PreparedPackage {
        val manifestJson = if (format == PackageFormat.XAPK) {
            readEntryText(raw, "manifest.json", MAX_MANIFEST_BYTES)
        } else {
            null
        }
        val tocBytes = if (format == PackageFormat.APKS) {
            readEntryBytes(raw, "toc.pb", MAX_TOC_BYTES)
        } else {
            null
        }

        val plan = try {
            ArchivePlanner.plan(format, names, manifestJson, tocBytes)
        } catch (e: ArchiveException) {
            throw e.toInstallFailure()
        }

        val splitsDir = File(workDir, "splits").apply { mkdirs() }
        val obbDir = File(workDir, "obb").apply { mkdirs() }
        val splits = ArrayList<PreparedSplit>(plan.splits.size)
        val obbs = ArrayList<PreparedObb>(plan.obbs.size)

        ZipFile(raw).use { zip ->
            // 空间检查放在解压前：按 ZIP 里声明的原始大小估算（解压后还要再占一份）
            val declared = (plan.splits.map { it.entryPath } + plan.obbs.map { it.entryPath })
                .sumOf { path -> (zip.getEntry(path)?.size ?: 0L).coerceAtLeast(0L) }
            val available = availableBytes(workDir)
            if (declared > 0 && available < declared + declared / 5 + MIN_FREE_EXTRA) {
                throw InstallFailure(InstallError.NO_SPACE, "need $declared, have $available")
            }

            plan.splits.forEach { spec ->
                val file = File(splitsDir, spec.sessionName)
                if (!FileSafety.isInside(splitsDir, file)) {
                    throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "entry escapes dir")
                }
                extract(zip, spec.entryPath, file)
                splits.add(PreparedSplit(spec.sessionName, file, spec.isBase))
            }
            plan.obbs.forEach { spec ->
                val file = File(obbDir, spec.fileName)
                if (!FileSafety.isInside(obbDir, file)) {
                    throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "obb escapes dir")
                }
                extract(zip, spec.entryPath, file)
                obbs.add(PreparedObb(spec.fileName, file, spec.entryPath))
            }
        }

        val baseFile = splits.first { it.isBase }.file
        val info = archiveInfo(context, baseFile)
            ?: throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "cannot read base manifest")
        // manifest.json 与 base APK 的包名必须一致：不一致要么归档被改过，要么 base 不是这个包的
        if (plan.packageName != null && plan.packageName != info.packageName) {
            throw InstallFailure(
                InstallError.UNKNOWN_ARCHIVE,
                "package mismatch: ${plan.packageName} != ${info.packageName}",
            )
        }

        checkSignatures(context, splits)

        Log.i(
            TAG,
            "prepared format=$format source=${plan.source} pkg=${info.packageName} " +
                "splits=${splits.size} obbs=${obbs.size}",
        )
        return PreparedPackage(
            workDir = workDir,
            format = format,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
            splits = splits,
            obbs = obbs,
            planSource = plan.source,
        )
    }

    /**
     * 规格 F6.3 / 安全要求：**所有 split 的签名必须一致**，不一致直接拒绝、不提交会话。
     * 读不到签名也按不一致处理（fail closed）——否则「未签名的一份」会被当成「没意见」。
     */
    private fun checkSignatures(context: Context, splits: List<PreparedSplit>) {
        var reference: String? = null
        var referenceName: String? = null
        splits.forEach { split ->
            val hash = AppSignatures.archiveHash(context, split.file)
            if (hash == null) {
                throw InstallFailure(InstallError.SIGNATURE_MISMATCH, "${split.sessionName} unsigned")
            }
            if (reference == null) {
                reference = hash
                referenceName = split.sessionName
            } else if (!hash.equals(reference, ignoreCase = true)) {
                throw InstallFailure(
                    InstallError.SIGNATURE_MISMATCH,
                    "${split.sessionName} != $referenceName",
                )
            }
        }
    }

    // ---------------- 工具 ----------------

    private fun copyUri(context: Context, uri: Uri, target: File) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            throw InstallFailure(InstallError.IO, t.message ?: "open failed", t)
        } ?: throw InstallFailure(InstallError.IO, "cannot open $uri")
        input.use { source ->
            target.outputStream().use { out -> source.copyTo(out, BUFFER) }
        }
    }

    private fun listEntries(file: File): List<String> = try {
        ZipFile(file).use { zip ->
            val out = ArrayList<String>(256)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                out.add(entry.name)
                if (out.size > MAX_ENTRIES) {
                    throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "too many entries")
                }
            }
            out
        }
    } catch (e: InstallFailure) {
        throw e
    } catch (t: Throwable) {
        throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, t.message ?: "not a zip", t)
    }

    private fun extract(zip: ZipFile, entryName: String, target: File) {
        val entry = zip.getEntry(entryName)
            ?: throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "missing $entryName")
        val input = zip.getInputStream(entry)
            ?: throw InstallFailure(InstallError.IO, "cannot read $entryName")
        input.use { source -> target.outputStream().use { out -> source.copyTo(out, BUFFER) } }
        if (target.length() <= 0L) throw InstallFailure(InstallError.IO, "empty $entryName")
    }

    private fun readEntryBytes(file: File, name: String, max: Long): ByteArray? {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            if (entry.size > max) throw InstallFailure(InstallError.UNKNOWN_ARCHIVE, "$name too large")
            return zip.getInputStream(entry)?.use { it.readBytes() }
        }
    }

    private fun readEntryText(file: File, name: String, max: Long): String? =
        readEntryBytes(file, name, max)?.toString(Charsets.UTF_8)

    private fun availableBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (t: Throwable) {
        0L
    }

    private fun archiveInfo(context: Context, file: File): PackageInfo? = try {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
    } catch (t: Throwable) {
        Log.w(TAG, "getPackageArchiveInfo failed: ${t.message}")
        null
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}

/** 一份准备好的安装载荷（装完必须 [cleanup]，避免缓存里留包）。 */
class PreparedPackage(
    val workDir: File,
    val format: PackageFormat,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val splits: List<PreparedSplit>,
    val obbs: List<PreparedObb>,
    val planSource: PlanSource,
) {
    fun cleanup() {
        runCatching { workDir.deleteRecursively() }
    }
}

data class PreparedSplit(val sessionName: String, val file: File, val isBase: Boolean)
data class PreparedObb(val fileName: String, val file: File, val entryPath: String)
