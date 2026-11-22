package moe.shizuku.manager.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * XAPK / APKS 的归档解析（规格 F6/F7）。
 *
 * 这里不碰 `ZipFile`：解析只吃「ZIP 里有哪些条目」+「manifest.json / toc.pb 的内容」，
 * 所以可以对着构造出来的数据把「base 判定、split 顺序、签名一致性之外的校验、旧格式回退」
 * 全部测到。真实解压/签名/提权安装需要设备，见更新日志里的实测记录。
 */
class ArchivePlanTest {

    private val entriesWithSplits = listOf(
        "manifest.json",
        "base.apk",
        "config.arm64_v8a.apk",
        "config.armeabi_v7a.apk",
        "icon.png",
        "Android/obb/com.foo/main.1.com.foo.obb",
    )

    private val xapkManifest = """
        {
          "xapk_version": 2,
          "package_name": "com.foo",
          "name": "Foo",
          "version_code": "120",
          "version_name": "1.2.0",
          "split_apks": [
            {"file": "base.apk", "id": "base"},
            {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"},
            {"file": "config.armeabi_v7a.apk", "id": "config.armeabi_v7a"}
          ],
          "obb": [
            {"file": "Android/obb/com.foo/main.1.com.foo.obb"}
          ]
        }
    """.trimIndent()

    @Test
    fun `plain apk plan is a single base split`() {
        val plan = ArchivePlanner.planPlainApk()
        assertEquals(PlanSource.PLAIN_APK, plan.source)
        assertEquals(1, plan.splits.size)
        assertEquals("base.apk", plan.splits[0].sessionName)
        assertTrue(plan.splits[0].isBase)
    }

    @Test
    fun `xapk manifest yields base first and obb list`() {
        val plan = ArchivePlanner.planXapk(entriesWithSplits, xapkManifest)
        assertEquals(PlanSource.XAPK_MANIFEST, plan.source)
        assertEquals("com.foo", plan.packageName)
        assertEquals("1.2.0", plan.versionName)
        assertEquals(120L, plan.versionCode)
        assertEquals(3, plan.splits.size)
        assertEquals("base.apk", plan.splits[0].sessionName)
        assertEquals("base.apk", plan.base.entryPath)
        assertEquals(1, plan.obbs.size)
        assertEquals("main.1.com.foo.obb", plan.obbs[0].fileName)
    }

    @Test
    fun `xapk manifest pointing at a missing entry is rejected`() {
        val manifest = """{"package_name":"com.foo","split_apks":[{"file":"base.apk","id":"base"},{"file":"config.x86.apk","id":"config.x86"}]}"""
        try {
            ArchivePlanner.planXapk(entriesWithSplits, manifest)
            fail("expected ArchiveException")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveError.MISSING_SPLIT_ENTRY, e.code)
        }
    }

    @Test
    fun `xapk without split list falls back to zip scan but keeps package info`() {
        val manifest = """{"package_name":"com.foo","version_name":"9.9","version_code":990}"""
        val plan = ArchivePlanner.planXapk(entriesWithSplits, manifest)
        assertEquals(PlanSource.XAPK_ZIP_SCAN, plan.source)
        assertEquals("com.foo", plan.packageName)
        assertEquals("9.9", plan.versionName)
        assertEquals(3, plan.splits.size)
        assertEquals("base.apk", plan.base.sessionName)
    }

    @Test
    fun `broken manifest falls back to zip scan instead of failing`() {
        val plan = ArchivePlanner.planXapk(entriesWithSplits, "{not json")
        assertEquals(PlanSource.XAPK_ZIP_SCAN, plan.source)
        assertEquals(3, plan.splits.size)
    }

    @Test
    fun `xapk with obb only in zip entries still lists it`() {
        val manifest = """{"package_name":"com.foo","split_apks":[{"file":"base.apk","id":"base"}]}"""
        val plan = ArchivePlanner.planXapk(entriesWithSplits, manifest)
        assertEquals(1, plan.splits.size)
        assertEquals(1, plan.obbs.size)
    }

    @Test
    fun `apks toc paths are used and base is detected`() {
        val toc = buildApksResult(
            variant(
                apkSet("splits/base-master.apk", "splits/base-arm64_v8a.apk"),
            ),
        )
        val entries = listOf("toc.pb", "splits/base-master.apk", "splits/base-arm64_v8a.apk")
        val plan = ArchivePlanner.planApks(entries, toc)
        assertEquals(PlanSource.TOC_PB, plan.source)
        assertEquals(2, plan.splits.size)
        assertEquals("base.apk", plan.splits[0].sessionName)
        assertEquals("splits/base-master.apk", plan.base.entryPath)
        assertEquals("base-arm64_v8a.apk", plan.splits[1].sessionName)
    }

    @Test
    fun `apks toc with unknown protobuf layout falls back to zip scan`() {
        // 完全不是 protobuf 的字节：结构化解析与应用场景回退（ASC II 扫描）都拿不到 .apk 路径
        val toc = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val entries = listOf("toc.pb", "splits/base-master.apk", "splits/base-arm64_v8a.apk")
        val plan = ArchivePlanner.planApks(entries, toc)
        assertEquals(PlanSource.APKS_ZIP_SCAN, plan.source)
        assertEquals(2, plan.splits.size)
    }

    @Test
    fun `ascii scan finds paths when structured walk fails`() {
        // 模拟字段号变了的 toc.pb：结构解析走不出来，但路径字符串是明文
        val raw = "splits/base-master.apk".toByteArray(Charsets.UTF_8)
        val toc = byteArrayOf(0x0A, raw.size.toByte(), 0x1A.toByte()) + raw + byteArrayOf(0x00)
        val paths = TocPb.parsePaths(toc)
        assertTrue(paths.any { it == "splits/base-master.apk" })
    }

    @Test
    fun `zip scan without base apk falls back to first entry`() {
        val entries = listOf("x.apk", "config.arm64_v8a.apk")
        val plan = ArchivePlanner.planZipScan(entries, PlanSource.XAPK_ZIP_SCAN)
        assertEquals(2, plan.splits.size)
        assertNotNull(plan.base)
        assertEquals("base.apk", plan.base.sessionName)
    }

    @Test
    fun `archive without any apk is rejected`() {
        try {
            ArchivePlanner.planZipScan(listOf("manifest.json", "readme.txt"), PlanSource.XAPK_ZIP_SCAN)
            fail("expected ArchiveException")
        } catch (e: ArchiveException) {
            assertEquals(ArchiveError.NO_APK, e.code)
        }
    }

    @Test
    fun `split session names are unique`() {
        val entries = listOf("base.apk", "splits/base.apk")
        val plan = ArchivePlanner.planApks(entries, buildApksResult(variant(apkSet("base.apk", "splits/base.apk"))))
        assertEquals(2, plan.splits.size)
        assertEquals(plan.splits.size, plan.splits.map { it.sessionName }.toSet().size)
    }

    // ---------------- 构造 protobuf（bundletool 的 BuildApksResult 结构） ----------------

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun lenField(field: Int, payload: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or 2L) + varint(payload.size.toLong()) + payload

    private fun concat(parts: Array<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun desc(path: String): ByteArray = lenField(1, path.toByteArray(Charsets.UTF_8))

    private fun apkSet(vararg paths: String): ByteArray =
        concat(paths.map { lenField(2, desc(it)) }.toTypedArray())

    private fun variant(vararg sets: ByteArray): ByteArray =
        concat(sets.map { lenField(2, it) }.toTypedArray())

    private fun buildApksResult(vararg variants: ByteArray): ByteArray =
        concat(variants.map { lenField(2, it) }.toTypedArray())
}
