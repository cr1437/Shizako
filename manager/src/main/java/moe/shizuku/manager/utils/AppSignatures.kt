package moe.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * APK / 应用签名的摘要计算（**当前安装**的签名，以及**单个 APK 文件**的签名）。
 *
 * 两处旧实现（`automation/AutomationCrypto.signatureHash` 与本次的安装器）本来各写一份，
 * 这里抽成同一份：签名集合的规范化顺序（先按证书字节的 SHA-256 hex 排序再哈希）
 * 只要两边有一点不一致，同一个包算出来的摘要就会不同，表现为「信任列表明明加过却
 * 一直签名不符」——这种 bug 很难从现象上看出来，所以只留一套实现。
 *
 * 对**归档文件**（还没安装的 APK）用 `PackageManager.getPackageArchiveInfo` 取签名：
 * - API 28+ 用 `GET_SIGNING_CERTIFICATES`，读 `signingInfo`（支持 v2/v3 签名方案）；
 * - API 24–27 没有 `signingInfo`，退回 `GET_SIGNATURES`（v1 时代的写法）。
 *
 * 拿不到签名时一律返回 null —— 调用方必须把它当「校验失败」，不能当「跳过校验」。
 */
object AppSignatures {

    private val HEX = "0123456789abcdef".toCharArray()

    /** 某个**已安装**包的签名摘要。 */
    fun packageHash(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getPackageInfo(packageName, signatureFlags())
        hashOf(info)
    } catch (t: Throwable) {
        null
    }

    /**
     * 某个 **APK 文件**的签名摘要（不要求已安装）。
     *
     * 先读 **APK 签名块 / v1 证书**（[ApkSigningBlock]），`PackageManager` 只作兜底：
     * `getPackageArchiveInfo()` 对单个 **split APK** 不收集签名，`signingInfo` 为 null，
     * 拿它判断「split 是否同签名」会一律得到 unsigned（实测踩到，XAPK 装不上）。
     */
    fun archiveHash(context: Context, apk: File): String? {
        if (!apk.isFile || !apk.canRead()) return null
        val certs = ApkSigningBlock.certificates(apk)
        if (certs.isNotEmpty()) return hashCertificates(certs)
        return try {
            val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
            if (info == null) null else hashOf(info)
        } catch (t: Throwable) {
            null
        }
    }

    /** 读一个 [PackageInfo] 里的签名证书字节（多签名/签名历史都按规范顺序返回）。 */
    fun certificates(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            return if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
            } else {
                signingInfo.signingCertificateHistory?.map { it.toByteArray() } ?: emptyList()
            }
        }
        @Suppress("DEPRECATION")
        val signatures = info.signatures ?: return emptyList()
        @Suppress("DEPRECATION")
        return signatures.map { it.toByteArray() }
    }

    /** [PackageInfo] → 摘要；签名读不到返回 null。 */
    fun hashOf(info: PackageInfo): String? {
        val certs = certificates(info)
        if (certs.isEmpty()) return null
        return hashCertificates(certs)
    }

    /** 把证书字节集合规范化成一个摘要（先按各证书的 SHA-256 hex 排序，规避签名顺序差异）。 */
    fun hashCertificates(certs: List<ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        certs.map { sha256Hex(it) }.sorted().forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().toHex()
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
