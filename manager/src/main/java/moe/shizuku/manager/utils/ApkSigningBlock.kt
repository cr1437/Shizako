package moe.shizuku.manager.utils

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * 直接从 **APK 文件**里取签名证书（规格 F6.3「校验所有 split 的签名一致」的数据来源）。
 *
 * 为什么不能用 `PackageManager.getPackageArchiveInfo()`：它对**单个 split APK** 不收集签名
 * （`mSigningDetails` 停在 UNKNOWN，`signingInfo`/`signatures` 都是 null），表现为
 * 「base 读得到签名、config split 一律 unsigned」—— 实测踩到，XAPK 直接装不上。
 *
 * 所以自己读，两条路按顺序：
 * 1. **APK Signing Block**（v2 `0x7109871a` / v3 `0xf05368c0`）：ZIP 中央目录前面那段
 *    `APK Sig Block 42`，里面的 signed-data 带证书链。现代 APK（minSdk ≥ 24）只有这个，
 *    没有 `META-INF` 下的 v1 签名文件。
 * 2. **v1（JAR 签名）**：老包/双签包里才有 `META-INF` 下的 `.RSA`/`.DSA`/`.EC`，用
 *    [CertificateFactory] 解析 PKCS#7 拿证书。
 *
 * 都拿不到就返回空 —— 调用方必须按「签名不一致」拒绝（fail closed），不能当成「没签名就算了」。
 *
 * 只支持单层 ZIP + 非 ZIP64：APK 不可能超过 4 GB（安装器自己也不支持）。
 */
object ApkSigningBlock {

    private const val TAG = "ShizakoInstall"

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_MAX_SEARCH = 0xFFFF + EOCD_MIN_SIZE

    private const val BLOCK_MAGIC = "APK Sig Block 42"
    private const val BLOCK_MAGIC_SIZE = 16

    private const val ID_V2 = 0x7109871aL
    private const val ID_V3 = 0xf05368c0L

    /** 证书 DER 集合；空表示读不到（调用方按失败处理）。 */
    fun certificates(apk: File): List<ByteArray> {
        signingBlockCertificates(apk)?.takeIf { it.isNotEmpty() }?.let { return it }
        return legacyCertificates(apk)
    }

    // ---------------- APK Signing Block（v2 / v3） ----------------

    private fun signingBlockCertificates(apk: File): List<ByteArray>? = try {
        RandomAccessFile(apk, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize < EOCD_MIN_SIZE) return null

            val tailSize = minOf(EOCD_MAX_SEARCH.toLong(), fileSize).toInt()
            val tailStart = fileSize - tailSize
            val tail = ByteArray(tailSize)
            raf.seek(tailStart)
            raf.readFully(tail)

            val eocd = findEocd(tail) ?: return null
            val cdOffset = readUInt32(tail, eocd + 16)
            if (cdOffset < 32 || cdOffset > fileSize) return null

            val magic = ByteArray(BLOCK_MAGIC_SIZE)
            raf.seek(cdOffset - BLOCK_MAGIC_SIZE)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != BLOCK_MAGIC) {
                Log.w(TAG, "no apk signing block")
                return null
            }

            // 尾部：uint64 blockSize | magic；block 起点 = cdOffset - blockSize - 8
            val sizeField = ByteArray(8)
            raf.seek(cdOffset - BLOCK_MAGIC_SIZE - 8)
            raf.readFully(sizeField)
            val blockSize = readInt64LE(sizeField)
            if (blockSize <= 0) return null
            val blockStart = cdOffset - blockSize - 8
            if (blockStart < 0) return null

            // header: uint64 blockSize（与尾部相同）
            raf.seek(blockStart)
            val headerSize = ByteArray(8)
            raf.readFully(headerSize)
            if (readInt64LE(headerSize) != blockSize) return null

            var pos = blockStart + 8
            val pairsEnd = cdOffset - BLOCK_MAGIC_SIZE - 8
            var v2: ByteArray? = null
            var v3: ByteArray? = null
            while (pos + 12 <= pairsEnd) {
                val pairLenBytes = ByteArray(8)
                raf.seek(pos)
                raf.readFully(pairLenBytes)
                val pairLen = readInt64LE(pairLenBytes)
                if (pairLen < 4 || pos + 8 + pairLen > pairsEnd) break
                val idBytes = ByteArray(4)
                raf.seek(pos + 8)
                raf.readFully(idBytes)
                val id = readUInt32(idBytes, 0)
                val value = ByteArray((pairLen - 4).toInt())
                raf.seek(pos + 12)
                raf.readFully(value)
                when (id) {
                    ID_V2 -> v2 = value
                    ID_V3 -> v3 = value
                }
                pos += 8 + pairLen
            }

            val block = v3 ?: v2 ?: return null
            parseSigners(block)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "signing block parse failed: ${t.message}")
        null
    }

    /**
     * v2/v3 的 value：
     * `signed data (uint32 len)` + `signatures (uint32 len)` + `public key (uint32 len)`（v3 在
     * signatures 前多了 minSdk/maxSdk 两个 uint32，signed data 在前，所以不影响取证书）。
     * signed data 内部：`digests (uint32 len)` + `certificates (uint32 len)`；
     * certificates = 若干个 `uint32 len + DER`。
     */
    private fun parseSigners(block: ByteArray): List<ByteArray>? {
        var offset = 0
        val signedDataLen = readUInt32OrNull(block, offset) ?: return null
        offset += 4
        if (signedDataLen < 0 || offset + signedDataLen > block.size) return null
        val signedData = block.copyOfRange(offset, offset + signedDataLen)

        // signed data 里第一个字段是 digests，跳过
        var p = 0
        val digestsLen = readUInt32OrNull(signedData, p) ?: return null
        p += 4 + digestsLen
        val certsLen = readUInt32OrNull(signedData, p) ?: return null
        p += 4
        if (p + certsLen > signedData.size) return null

        val out = ArrayList<ByteArray>()
        var cp = p
        val end = p + certsLen
        while (cp + 4 <= end) {
            val len = readUInt32OrNull(signedData, cp) ?: break
            cp += 4
            if (len <= 0 || cp + len > end) break
            out.add(signedData.copyOfRange(cp, cp + len))
            cp += len
        }
        return out.ifEmpty { null }
    }

    // ---------------- v1（JAR 签名） ----------------

    private fun legacyCertificates(apk: File): List<ByteArray> = try {
        ZipFile(apk).use { zip ->
            val out = ArrayList<ByteArray>()
            val names = zip.entries()
            while (names.hasMoreElements()) {
                val entry = names.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.uppercase()
                if (!name.startsWith("META-INF/")) continue
                if (!(name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) continue
                zip.getInputStream(entry)?.use { input ->
                    val factory = CertificateFactory.getInstance("X.509")
                    factory.generateCertificates(input).forEach { cert ->
                        if (cert is X509Certificate) out.add(cert.encoded)
                    }
                }
            }
            out
        }
    } catch (t: Throwable) {
        emptyList()
    }

    // ---------------- 小工具 ----------------

    private fun findEocd(tail: ByteArray): Int? {
        for (i in tail.size - EOCD_MIN_SIZE downTo 0) {
            if (readUInt32(tail, i) == EOCD_SIGNATURE) return i
        }
        return null
    }

    /** 无符号 32 位小端；读不到返回 -1。 */
    private fun readUInt32OrNull(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val v = readUInt32(bytes, offset)
        return if (v < 0) null else v.toInt()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readInt64LE(bytes: ByteArray): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return result
    }
}
