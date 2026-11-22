package moe.shizuku.manager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.server.storage.StoragePaths

/**
 * 服务端的路径白名单 / 注入防线（规格 F2 安全要求）。
 *
 * 这些是纯字符串/文件路径逻辑，放在管理器的 JVM 单测里跑（`:manager:testDebugUnitTest`），
 * 不需要模拟器。注意：**「合法路径被接受」只能在设备上验证** —— JVM 跑在 Windows 上时
 * `File("/sdcard/...").canonicalPath` 会被解析成 `E:\sdcard\...`，白名单永远匹配不上。
 * 所以这里只覆盖「必须拒绝」的分支 + 与平台无关的纯函数。
 */
class StoragePathsTest {

    @Test
    fun `shell metacharacter blacklist does not block android 10+ apk paths`() {
        // Android 10+ 的 /data/app 目录名是 ~~<hash>==/，APK 路径天然带 '~'。
        // 之前把 '~' 当 shell 元字符拒了 → 「同时保存 APK」对用户应用直接失败（模拟器实测）。
        assertFalse(
            StoragePaths.hasShellMetacharacter(
                "/data/app/~~nyILo0AI92dLnREKcgwZzQ==/com.test.payload1-SvftblGolbvD-BDJYaaE9w==/base.apk"
            )
        )
        assertFalse(StoragePaths.hasShellMetacharacter("/sdcard/Android/data/com.example/files/a b.txt"))
        assertTrue(StoragePaths.hasShellMetacharacter("/sdcard/a;b"))
        assertTrue(StoragePaths.hasShellMetacharacter("/sdcard/a|b"))
        assertTrue(StoragePaths.hasShellMetacharacter("/sdcard/a`id`"))
        assertTrue(StoragePaths.hasShellMetacharacter("/sdcard/a'b"))
        assertTrue(StoragePaths.hasShellMetacharacter("/sdcard/a\nb"))
        assertTrue(StoragePaths.hasShellMetacharacter(null))
    }

    @Test
    fun `rejects path traversal before canonicalization`() {
        assertThrows(SecurityException::class.java) {
            StoragePaths.check("/sdcard/Android/data/../../../data/data/com.example")
        }
        assertThrows(SecurityException::class.java) {
            StoragePaths.check("/data/data/com.example/../com.other/files")
        }
        assertThrows(SecurityException::class.java) {
            StoragePaths.check("/sdcard/..")
        }
    }

    @Test
    fun `rejects shell metacharacters`() {
        for (bad in listOf(
            "/sdcard/x;rm -rf /",
            "/sdcard/x|cat",
            "/sdcard/x&y",
            "/sdcard/\$HOME",
            "/sdcard/`id`",
            "/sdcard/a(b)",
            "/sdcard/a<b",
            "/sdcard/a*b",
            "/sdcard/a?b",
            "/sdcard/a'b",
            "/sdcard/a\"b",
            "/sdcard/a\\b",
        )) {
            assertThrows("should reject: $bad", SecurityException::class.java) {
                StoragePaths.check(bad)
            }
        }
    }

    @Test
    fun `rejects relative empty and control characters`() {
        assertThrows(SecurityException::class.java) { StoragePaths.check("sdcard/Android") }
        assertThrows(SecurityException::class.java) { StoragePaths.check("") }
        assertThrows(SecurityException::class.java) { StoragePaths.check("/sdcard/a\u0000b") }
        assertThrows(SecurityException::class.java) { StoragePaths.check("/sdcard/a\nb") }
        assertThrows(SecurityException::class.java) { StoragePaths.check("/" + "x".repeat(5000)) }
    }

    @Test
    fun `rejects paths outside the allow list`() {
        assertThrows(SecurityException::class.java) { StoragePaths.check("/system/build.prop") }
        assertThrows(SecurityException::class.java) { StoragePaths.check("/proc/self/cmdline") }
        assertThrows(SecurityException::class.java) { StoragePaths.check("/data/system/shizuku.json") }
    }

    @Test
    fun `allow list matching is separator aware`() {
        assertTrue(StoragePaths.isInsideAllowedRoot("/sdcard"))
        assertTrue(StoragePaths.isInsideAllowedRoot("/sdcard/Android/data/com.example"))
        assertTrue(StoragePaths.isInsideAllowedRoot("/data/data/com.example/files"))
        assertFalse(StoragePaths.isInsideAllowedRoot("/sdcardX/evil"))
        assertFalse(StoragePaths.isInsideAllowedRoot("/data/dataX/evil"))
        assertFalse(StoragePaths.isInsideAllowedRoot("/data/system/shizuku.json"))
    }

    @Test
    fun `deleting a storage root or its ancestor is refused`() {
        assertTrue(StoragePaths.isRootOrAncestor("/sdcard"))
        assertTrue(StoragePaths.isRootOrAncestor("/data"))
        assertFalse(StoragePaths.isRootOrAncestor("/sdcard/Android"))
        assertFalse(StoragePaths.isRootOrAncestor("/sdcard/Android/data/com.example"))
        assertFalse(StoragePaths.isRootOrAncestor("/data/data/com.example"))
    }

    @Test
    fun `deleting the shared external containers is refused too`() {
        // /sdcard/Android 不是白名单根目录，但删掉它 = 所有应用的外部数据一起没了
        assertTrue(StoragePaths.isDeleteForbidden("/sdcard/Android"))
        assertTrue(StoragePaths.isDeleteForbidden("/sdcard/Android/data"))
        assertTrue(StoragePaths.isDeleteForbidden("/sdcard/Android/obb"))
        assertTrue(StoragePaths.isDeleteForbidden("/sdcard"))
        assertFalse(StoragePaths.isDeleteForbidden("/sdcard/Android/data/com.example"))
        assertFalse(StoragePaths.isDeleteForbidden("/sdcard/Android/obb/com.example"))
    }

    @Test
    fun `content uris are validated separately`() {
        assertEquals(
            "content://com.example.provider/document/1",
            StoragePaths.checkContentUri("content://com.example.provider/document/1"),
        )
        assertThrows(SecurityException::class.java) { StoragePaths.checkContentUri("file:///sdcard/x") }
        assertThrows(SecurityException::class.java) { StoragePaths.checkContentUri("content://") }
        assertThrows(SecurityException::class.java) { StoragePaths.checkContentUri("content:///x") }
        assertThrows(SecurityException::class.java) { StoragePaths.checkContentUri("content://a/b;id") }
        assertTrue(StoragePaths.isContentUri("CONTENT://a/b"))
        assertTrue(StoragePaths.checkAny("content://a/b").startsWith("content://"))
    }

    @Test
    fun `package names are extracted for the run-as layer`() {
        assertEquals("com.example.app", StoragePaths.packageOf("/data/data/com.example.app/files"))
        assertEquals("com.example.app", StoragePaths.packageOf("/data/user/0/com.example.app/files/x"))
        assertEquals("com.example.app", StoragePaths.packageOf("/sdcard/Android/data/com.example.app/files"))
        assertEquals("com.example.app", StoragePaths.packageOf("/storage/emulated/0/Android/obb/com.example.app"))
        assertNull(StoragePaths.packageOf("/sdcard/DCIM"))
        assertNull(StoragePaths.packageOf("/data/data/com..app/x"))
        assertNull(StoragePaths.packageOf("/data/data"))
        assertNull(StoragePaths.packageOf(null))
    }

    @Test
    fun `package name format is strict`() {
        assertTrue(StoragePaths.isPackageName("com.example.app"))
        assertTrue(StoragePaths.isPackageName("a.b_c123"))
        assertFalse(StoragePaths.isPackageName("com..app"))
        assertFalse(StoragePaths.isPackageName(".app"))
        assertFalse(StoragePaths.isPackageName("app."))
        assertFalse(StoragePaths.isPackageName("app/evil"))
        assertFalse(StoragePaths.isPackageName("app;rm"))
        assertFalse(StoragePaths.isPackageName(""))
        assertFalse(StoragePaths.isPackageName("1abc.def"))
    }

    @Test
    fun `audit digest does not leak the full path`() {
        val digest = StoragePaths.digest("/sdcard/Android/data/com.example.app/files/token.db")
        assertFalse(digest.contains("/"))
        assertFalse(digest.contains("sdcard"))
        assertTrue(digest.startsWith("token.db#"))
        assertEquals(8, digest.substringAfter('#').length)
        assertEquals("-", StoragePaths.digest(null))
    }
}
