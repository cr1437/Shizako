package moe.shizuku.manager.installer

/**
 * 队列的**纯状态操作**（规格 F3）。真正的执行器在 [InstallQueue]，这里只做列表变换，
 * 于是「顺序、重试回队尾、取消保留已完成」这些语义都能在 JVM 单测里穷举。
 */
object InstallQueueModel {

    fun add(existing: List<InstallTask>, incoming: List<InstallTask>): List<InstallTask> {
        if (incoming.isEmpty()) return existing
        val out = ArrayList<InstallTask>(existing.size + incoming.size)
        out.addAll(existing)
        // 同一个 URI 已经在队列里就不再重复添加（用户手滑连点两次「添加文件」很常见）
        val known = existing.mapTo(HashSet()) { it.fileUri }
        incoming.forEach { if (known.add(it.fileUri)) out.add(it) }
        return out
    }

    fun remove(tasks: List<InstallTask>, id: String): List<InstallTask> =
        tasks.filterNot { it.id == id }

    /** 排序：正数下移、负数上移；只和相邻项交换，边界不动。 */
    fun move(tasks: List<InstallTask>, id: String, delta: Int): List<InstallTask> {
        if (delta == 0) return tasks
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return tasks
        val target = (index + delta).coerceIn(0, tasks.lastIndex)
        if (target == index) return tasks
        val out = ArrayList(tasks)
        val item = out.removeAt(index)
        out.add(target, item)
        return out
    }

    /**
     * 重试：失败/取消的项重置成 WAITING 并**移到队尾**（规格 F4「重新加入队尾」），
     * retryCount + 1；其它状态不动。
     */
    fun retry(tasks: List<InstallTask>, id: String): List<InstallTask> {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return tasks
        val task = tasks[index]
        if (task.status != TaskStatus.FAILED && task.status != TaskStatus.CANCELLED) return tasks
        val out = ArrayList(tasks)
        out.removeAt(index)
        out.add(task.copy(status = TaskStatus.WAITING, reason = null, retryCount = task.retryCount + 1))
        return out
    }

    /** 取消：未开始的标 CANCELLED；[runningId] 指向当前项（由执行器负责终止并回写）。 */
    fun cancelWaiting(tasks: List<InstallTask>, runningId: String?): List<InstallTask> =
        tasks.map { task ->
            when {
                task.status != TaskStatus.WAITING -> task
                runningId != null && task.id == runningId -> task
                else -> task.copy(status = TaskStatus.CANCELLED, reason = null)
            }
        }

    fun replace(tasks: List<InstallTask>, updated: InstallTask): List<InstallTask> =
        tasks.map { if (it.id == updated.id) updated else it }

    fun nextWaiting(tasks: List<InstallTask>): InstallTask? =
        tasks.firstOrNull { it.status == TaskStatus.WAITING }

    fun hasWaiting(tasks: List<InstallTask>): Boolean =
        tasks.any { it.status == TaskStatus.WAITING }

    /** 队列是否还有未结束的项（用于「断点提示」与结束判定）。 */
    fun hasUnfinished(tasks: List<InstallTask>): Boolean =
        tasks.any { !it.status.isFinished }

    /** 「当前 X/N」的 X：已结束（成功/失败/取消）的项数。 */
    fun finishedCount(tasks: List<InstallTask>): Int = tasks.count { it.status.isFinished }

    /** 清掉已结束的项，保留未结束的（「清空已完成」按钮）。 */
    fun clearFinished(tasks: List<InstallTask>): List<InstallTask> =
        tasks.filterNot { it.status.isFinished }
}
