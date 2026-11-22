package moe.shizuku.manager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.server.storage.TarExtractor
import rikka.shizuku.server.storage.TarWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * tar.gz 打包 / 安全解压（规格 F3 的 external.tar.gz、F4 的 restoreTarDirectory）。
 *
 * 服务端实现只用 java.io + android.system.Os（lstat）/android.os.StatFs，
 * 后两者在 JVM 单测里是 stub，会被实现里的 catch 兜住并退回 File API，
 * 所以这里可以完整跑「打包 → 解压 → 比对」。
 */
class TarRoundTripTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun `roundtrip keeps names content and nesting`() {
        val work = tempDir("tar-src")
        val source = File(work, "com.example.app").apply { mkdirs() }
        File(source, "a.txt").writeText("hello")
        File(source, "中文 名字.txt").writeText("你好")
        val nested = File(source, "sub").apply { mkdirs() }
        val binary = ByteArray(4096) { (it % 251).toByte() }
        File(nested, "b.bin").writeBytes(binary)
        File(source, "empty.txt").writeBytes(ByteArray(0))

        val packed = ByteArrayOutputStream()
        val bytes = TarWriter.write(source, packed, null)
        assertEquals(5L + 6L + 4096L, bytes) // hello(5) + 你好(UTF-8 6) + b.bin(4096)

        val dest = tempDir("tar-dst")
        val entries = TarExtractor.extract(
            dest,
            ByteArrayInputStream(packed.toByteArray()),
            null,
        )
        assertTrue(entries >= 5) // 目录 + a.txt + 中文名 + sub + b.bin + empty

        val restored = File(dest, "com.example.app")
        assertEquals("hello", File(restored, "a.txt").readText())
        assertEquals("你好", File(restored, "中文 名字.txt").readText())
        assertTrue(File(restored, "sub/b.bin").readBytes().contentEquals(binary))
        assertEquals(0L, File(restored, "empty.txt").length())

        source.deleteRecursively()
        dest.deleteRecursively()
        work.deleteRecursively()
    }

    @Test
    fun `roundtrip keeps file mtime`() {
        val work = tempDir("tar-mtime")
        val source = File(work, "com.example.app").apply { mkdirs() }
        val file = File(source, "a.txt").apply { writeText("hello") }
        val expected = 1_600_000_000_000L
        file.setLastModified(expected)

        val packed = ByteArrayOutputStream()
        TarWriter.write(source, packed, null)

        val dest = tempDir("tar-mtime-dst")
        TarExtractor.extract(dest, ByteArrayInputStream(packed.toByteArray()), null)
        val restored = File(dest, "com.example.app/a.txt")
        // tar 的 mtime 只有秒精度，允许 2 秒误差（不同文件系统的时间戳精度不同）
        val delta = kotlin.math.abs(restored.lastModified() - expected)
        assertTrue("mtime delta=$delta", delta <= 2_000L)

        work.deleteRecursively()
        dest.deleteRecursively()
    }

    @Test
    fun `replacing extract removes stale files and keeps data on failure`() {
        val source = tempDir("tar-replace-src")
        val pkgSource = File(source, "com.example.app").apply { mkdirs() }
        File(pkgSource, "keep.txt").writeText("new")
        val packed = ByteArrayOutputStream()
        TarWriter.write(pkgSource, packed, null)
        val tarBytes = packed.toByteArray()

        val dest = tempDir("tar-replace")
        val pkgDest = File(dest, "com.example.app").apply { mkdirs() }
        File(pkgDest, "stale.txt").writeText("old")
        File(pkgDest, "keep.txt").writeText("old")

        // 替换式：同名目录里不在备份中的文件必须消失
        TarExtractor.extractReplacing(dest, ByteArrayInputStream(tarBytes), null)
        assertFalse("stale.txt should be replaced away", File(pkgDest, "stale.txt").exists())
        assertEquals("new", File(pkgDest, "keep.txt").readText())
        assertFalse(
            "staging directory must be cleaned up",
            dest.listFiles()?.any { it.name.startsWith(".shizako-restore-") } == true,
        )

        // 失败路径：坏包不能动现有数据（先解到 staging，失败只清 staging）
        File(pkgDest, "keep.txt").writeText("survives")
        File(pkgDest, "stale2.txt").writeText("survives")
        assertThrows(IOException::class.java) {
            TarExtractor.extractReplacing(dest, ByteArrayInputStream(byteArrayOf(1, 2, 3)), null)
        }
        assertEquals("survives", File(pkgDest, "keep.txt").readText())
        assertTrue(File(pkgDest, "stale2.txt").exists())

        source.deleteRecursively()
        dest.deleteRecursively()
    }

    @Test
    fun `rejects entries that escape the destination`() {
        val dest = tempDir("tar-escape")

        assertThrows(IOException::class.java) {
            extractRaw(dest, entry("../../evil.txt", "pwned".toByteArray()))
        }
        assertThrows(IOException::class.java) {
            extractRaw(dest, entry("/data/evil.txt", "pwned".toByteArray()))
        }
        assertThrows(IOException::class.java) {
            extractRaw(dest, entry("sub/../../evil.txt", "pwned".toByteArray()))
        }
        assertFalse(File(dest.parentFile, "evil.txt").exists())

        dest.deleteRecursively()
    }

    @Test
    fun `symlinks and devices are skipped`() {
        val dest = tempDir("tar-symlink")
        val entries = extractRaw(
            dest,
            entry("link", ByteArray(0), type = '2'),
            entry("dev", ByteArray(0), type = '3'),
            entry("ok.txt", "ok".toByteArray()),
        )
        assertEquals(1, entries)
        assertFalse(File(dest, "link").exists())
        assertFalse(File(dest, "dev").exists())
        assertEquals("ok", File(dest, "ok.txt").readText())
        dest.deleteRecursively()
    }

    @Test
    fun `truncated stream fails instead of leaving half a file`() {
        val dest = tempDir("tar-trunc")
        val full = entry("big.bin", ByteArray(2048) { 1 })
        // 砍掉后半截：大小字段声明 2048，实际只给 512
        val truncated = full.copyOf(512 + 512)
        assertThrows(IOException::class.java) {
            extractRaw(dest, truncated)
        }
        dest.deleteRecursively()
    }

    @Test
    fun `bad checksum is rejected`() {
        val dest = tempDir("tar-checksum")
        val broken = entry("x.txt", "x".toByteArray())
        broken[0] = 'Z'.code.toByte() // 改名字第一个字节：校验和必然对不上
        assertThrows(IOException::class.java) {
            extractRaw(dest, broken)
        }
        dest.deleteRecursively()
    }

    // ---------------- helpers ----------------

    private fun extractRaw(dest: File, vararg entries: ByteArray): Int {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (e in entries) gz.write(e)
            gz.write(ByteArray(1024))
        }
        return TarExtractor.extract(dest, ByteArrayInputStream(out.toByteArray()), null)
    }

    /** 手工构造一条 tar 记录（头部 + 内容 + 512 对齐）。 */
    private fun entry(name: String, content: ByteArray, type: Char = '0'): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

        putOctal(header, 100, 8, 493)  // 0755
        putOctal(header, 108, 8, 0)    // uid
        putOctal(header, 116, 8, 0)    // gid
        putOctal(header, 124, 12, content.size.toLong())
        putOctal(header, 136, 12, 0)   // mtime
        for (i in 148 until 156) header[i] = 32  // 校验和字段先按空格算
        header[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        header[262] = 0
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()

        var sum = 0L
        for (b in header) sum += (b.toInt() and 0xFF).toLong()
        val checksum = sum.toString(8).padStart(6, '0')
        checksum.toByteArray().copyInto(header, 148)
        header[154] = 0
        header[155] = 32

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(content)
        val padding = (512 - content.size % 512) % 512
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun putOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val octal = value.toString(8)
        val digits = length - 1
        val padded = octal.padStart(digits, '0').takeLast(digits)
        padded.toByteArray().copyInto(header, offset)
        header[offset + length - 1] = 0
    }
}
