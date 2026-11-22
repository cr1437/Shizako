package moe.shizuku.manager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份元数据 / 文件名 / 错误码（规格 §三，纯 JVM 可测）。 */
class StorageModelsTest {

    private fun meta(
        pkg: String = "com.example.app",
        obb: Boolean = true,
        apk: Boolean = false,
    ) = BackupMeta(
        packageName = pkg,
        versionName = "1.2.3",
        versionCode = 123L,
        backupTime = 1_700_000_000_000L,
        androidVersion = 33,
        includesObb = obb,
        includesApk = apk,
        label = "Example",
        externalFile = "com.example.app_1.2.3_20251122-120000.tar.gz",
        obbFile = if (obb) "com.example.app_1.2.3_20251122-120000.obb.tar.gz" else null,
        apkFile = if (apk) "com.example.app_1.2.3_20251122-120000.apk" else null,
        externalBytes = 4096L,
        obbBytes = if (obb) 8192L else 0L,
    )

    @Test
    fun `meta json roundtrip keeps every field`() {
        val original = meta(apk = true)
        val parsed = BackupMeta.fromJson(original.toJsonText())

        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `meta json keeps null optional files`() {
        val parsed = BackupMeta.fromJson(meta(obb = false, apk = false).toJsonText())
        assertNotNull(parsed)
        assertNull(parsed!!.obbFile)
        assertNull(parsed.apkFile)
        assertEquals(4096L, parsed.externalBytes)
    }

    @Test
    fun `meta rejects unknown future format`() {
        assertNull(
            BackupMeta.fromJson("""{"packageName":"com.example.app","formatVersion":${BACKUP_FORMAT_VERSION + 1}}""")
        )
    }

    @Test
    fun `meta rejects missing package`() {
        assertNull(BackupMeta.fromJson("""{"versionName":"1"}"""))
        assertNull(BackupMeta.fromJson("not json at all"))
    }

    @Test
    fun `file names follow the spec and stay inside the directory`() {
        val ts = 1_700_000_000_000L
        val external = BackupNames.externalTar("com.example.app", "1.2.3", ts)
        // 时间戳用本地时区格式化成 yyyyMMdd-HHmmss，测试只校验形状，不依赖运行机器的时区
        assertTrue(Regex("^com\\.example\\.app_1\\.2\\.3_\\d{8}-\\d{6}\\.tar\\.gz$").matches(external))
        assertEquals(
            external.removeSuffix(".tar.gz") + ".obb.tar.gz",
            BackupNames.obbTar("com.example.app", "1.2.3", ts),
        )
        assertEquals("com.example.app.meta.json", BackupNames.metaFile("com.example.app"))
        assertTrue(BackupNames.isMeta("x.meta.json"))
        assertFalse(BackupNames.isMeta("x.tar.gz"))
    }

    @Test
    fun `names neutralize path separators coming from metadata`() {
        // meta.json 可以被用户改成任意文本，拼出的名字不能逃出目录
        val name = BackupNames.externalTar("../../evil/app", "1.0/../x", 0L)
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertTrue(name.startsWith(".._.._evil_app_1.0_.._x_"))
        assertTrue(name.endsWith(".tar.gz"))
    }

    @Test
    fun `error codes are parsed from server messages`() {
        assertEquals(StorageError.NO_SPACE, StorageError.parse("IllegalStateException: storage:no_space:no space left on device"))
        assertEquals(StorageError.DENIED, StorageError.parse("storage:denied:Permission denied"))
        assertEquals(StorageError.NOT_FOUND, StorageError.parse("storage:not_found:cannot list /sdcard/x"))
        assertEquals(StorageError.CANCELLED, StorageError.parse("storage:cancelled:tar cancelled"))
        assertEquals(StorageError.NO_SERVICE, StorageError.parse("binder haven't been received"))
        assertEquals(StorageError.DENIED, StorageError.parse("Permission Denial: storage.tarDirectory"))
        assertEquals(StorageError.UNKNOWN, StorageError.parse("something else"))
        assertEquals(StorageError.UNKNOWN, StorageError.parse(null))
    }

    @Test
    fun `task starts waiting and carries its package`() {
        val target = BackupTarget("com.example.app", "Example", "1.0", 1L, debuggable = true)
        val task = BackupTask.new(target)
        assertEquals(BackupStatus.WAITING, task.status)
        assertFalse(task.status.isFinished)
        assertEquals("com.example.app", task.packageName)
        assertTrue(task.id.isNotEmpty())
        assertTrue(BackupStatus.SUCCESS.isFinished)
        assertTrue(BackupStatus.CANCELLED.isFinished)
        assertFalse(BackupStatus.RUNNING.isFinished)
    }
}
