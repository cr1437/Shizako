package moe.shizuku.manager.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队列状态机（规格 F3 的顺序执行 / 重试回队尾 / 取消保留已完成）。 */
class InstallQueueModelTest {

    private fun task(
        id: String,
        uri: String = "content://x/$id",
        status: TaskStatus = TaskStatus.WAITING,
        retry: Int = 0,
    ) = InstallTask(
        id = id,
        fileUri = uri,
        fileName = "$id.apk",
        format = PackageFormat.APK,
        status = status,
        reason = if (status == TaskStatus.FAILED) "boom" else null,
        retryCount = retry,
        sizeBytes = 1024,
    )

    @Test
    fun `add appends and skips the same uri`() {
        val existing = listOf(task("a"))
        val added = InstallQueueModel.add(existing, listOf(task("b"), task("a-copy", uri = "content://x/a")))
        assertEquals(listOf("a", "b"), added.map { it.id })
    }

    @Test
    fun `remove keeps order`() {
        val tasks = listOf(task("a"), task("b"), task("c"))
        assertEquals(listOf("a", "c"), InstallQueueModel.remove(tasks, "b").map { it.id })
    }

    @Test
    fun `move swaps neighbouring items and clamps at the edges`() {
        val tasks = listOf(task("a"), task("b"), task("c"))
        assertEquals(listOf("b", "a", "c"), InstallQueueModel.move(tasks, "a", 1).map { it.id })
        assertEquals(listOf("a", "c", "b"), InstallQueueModel.move(tasks, "c", -1).map { it.id })
        // 已经在最前面，再上移不动
        assertEquals(listOf("a", "b", "c"), InstallQueueModel.move(tasks, "a", -1).map { it.id })
        assertEquals(listOf("a", "b", "c"), InstallQueueModel.move(tasks, "c", 1).map { it.id })
    }

    @Test
    fun `retry resets a failed item and moves it to the tail`() {
        val tasks = listOf(
            task("a"),
            task("b", status = TaskStatus.FAILED, retry = 1),
            task("c"),
        )
        val retried = InstallQueueModel.retry(tasks, "b")
        assertEquals(listOf("a", "c", "b"), retried.map { it.id })
        val item = retried.last()
        assertEquals(TaskStatus.WAITING, item.status)
        assertNull(item.reason)
        assertEquals(2, item.retryCount)
    }

    @Test
    fun `retry ignores successful or running items`() {
        val tasks = listOf(task("a", status = TaskStatus.SUCCESS), task("b", status = TaskStatus.INSTALLING))
        assertEquals(tasks, InstallQueueModel.retry(tasks, "a"))
        assertEquals(tasks, InstallQueueModel.retry(tasks, "b"))
    }

    @Test
    fun `retry of cancelled item is allowed`() {
        val tasks = listOf(task("a"), task("b", status = TaskStatus.CANCELLED))
        val retried = InstallQueueModel.retry(tasks, "b")
        assertEquals(listOf("a", "b"), retried.map { it.id })
        assertEquals(TaskStatus.WAITING, retried.last().status)
    }

    @Test
    fun `cancel marks waiting items but keeps the running one and finished ones`() {
        val tasks = listOf(
            task("a", status = TaskStatus.SUCCESS),
            task("b", status = TaskStatus.INSTALLING),
            task("c"),
            task("d"),
        )
        val cancelled = InstallQueueModel.cancelWaiting(tasks, runningId = "b")
        assertEquals(TaskStatus.SUCCESS, cancelled[0].status)
        assertEquals(TaskStatus.INSTALLING, cancelled[1].status)
        assertEquals(TaskStatus.CANCELLED, cancelled[2].status)
        assertEquals(TaskStatus.CANCELLED, cancelled[3].status)
    }

    @Test
    fun `next waiting follows list order`() {
        val tasks = listOf(
            task("a", status = TaskStatus.SUCCESS),
            task("b", status = TaskStatus.FAILED),
            task("c"),
            task("d"),
        )
        assertEquals("c", InstallQueueModel.nextWaiting(tasks)?.id)
        assertNull(InstallQueueModel.nextWaiting(tasks.filter { it.status.isFinished }))
    }

    @Test
    fun `counters reflect finished items only`() {
        val tasks = listOf(
            task("a", status = TaskStatus.SUCCESS),
            task("b", status = TaskStatus.FAILED),
            task("c", status = TaskStatus.INSTALLING),
            task("d"),
        )
        assertEquals(2, InstallQueueModel.finishedCount(tasks))
        assertTrue(InstallQueueModel.hasUnfinished(tasks))
        assertTrue(InstallQueueModel.hasWaiting(tasks))
    }

    @Test
    fun `clear finished keeps unfinished items`() {
        val tasks = listOf(
            task("a", status = TaskStatus.SUCCESS),
            task("b"),
            task("c", status = TaskStatus.CANCELLED),
        )
        assertEquals(listOf("b"), InstallQueueModel.clearFinished(tasks).map { it.id })
    }

    @Test
    fun `replace updates only the matching task`() {
        val tasks = listOf(task("a"), task("b"))
        val updated = InstallQueueModel.replace(tasks, tasks[1].copy(status = TaskStatus.SUCCESS))
        assertEquals(TaskStatus.WAITING, updated[0].status)
        assertEquals(TaskStatus.SUCCESS, updated[1].status)
    }

    @Test
    fun `history filter combines result source and time`() {
        val entries = listOf(
            entry("1", TaskStatus.SUCCESS, InstallSource.UI, 1_000L),
            entry("2", TaskStatus.FAILED, InstallSource.UI, 2_000L),
            entry("3", TaskStatus.FAILED, InstallSource.TRUSTED_APP, 3_000L),
        )
        assertEquals(listOf("1", "2", "3"), filterInstallHistory(entries).map { it.id })
        assertEquals(listOf("2", "3"), filterInstallHistory(entries, result = TaskStatus.FAILED).map { it.id })
        assertEquals(listOf("3"), filterInstallHistory(entries, source = InstallSource.TRUSTED_APP).map { it.id })
        assertEquals(listOf("3"), filterInstallHistory(entries, sinceMs = 2_500L).map { it.id })
        assertEquals(
            listOf("3"),
            filterInstallHistory(entries, result = TaskStatus.FAILED, source = InstallSource.TRUSTED_APP, sinceMs = 2_500L)
                .map { it.id },
        )
        assertFalse(filterInstallHistory(entries, sinceMs = 9_000L).isNotEmpty())
    }

    private fun entry(id: String, result: TaskStatus, source: InstallSource, at: Long) =
        InstallHistoryEntry(
            id = id,
            fileName = "$id.apk",
            packageName = "com.foo",
            versionName = "1.0",
            format = PackageFormat.APK,
            source = source,
            sourcePackage = null,
            result = result,
            reason = null,
            durationMs = 100,
            timestamp = at,
        )
}
