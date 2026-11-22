package moe.shizuku.manager.storage

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * 批量备份队列（规格 F3「复用现有队列 UI」的执行侧）。
 *
 * 与安装队列（[moe.shizuku.manager.installer.InstallQueue]）同一套约定：
 * - 顺序执行、一次一个；**单项失败不中断**，后面继续跑；
 * - 状态变化回调统一 post 到主线程（Compose 只能主线程改状态）；
 * - 取消 = 当前项在下一次 64KB 读时中止，未开始的项直接标 CANCELLED。
 *
 * 与安装队列的差别：这里**不做断点持久化**。备份的输入是「SAF 目录 + 一批应用」，
 * 用户重进界面重新勾选更直观；持久化 SAF 授权 + 选项反而容易出现「上次的目录
 * 已经没权限了，队列一直失败」这种更难解释的状态。刷新/杀进程后队列清空，
 * 已完成的备份文件都还在目录里，重新扫就是。
 */
object BackupQueue {

    private const val TAG = "ShizakoStorage"

    data class Snapshot(
        val tasks: List<BackupTask>,
        val running: Boolean,
        val currentId: String?,
        val stage: String?,
        val directoryLabel: String?,
    ) {
        val total: Int get() = tasks.size
        val finished: Int get() = tasks.count { it.status.isFinished }
        val hasWaiting: Boolean get() = tasks.any { it.status == BackupStatus.WAITING }
        val current: BackupTask? get() = tasks.firstOrNull { it.id == currentId }
    }

    interface Listener {
        fun onQueueChanged(snapshot: Snapshot)
    }

    private class CancelToken {
        @Volatile
        var cancelled: Boolean = false
    }

    private val lock = Any()
    private val listeners = LinkedHashSet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shizako-backup-queue").apply { isDaemon = true }
    }

    private var tasks: List<BackupTask> = emptyList()
    private var targets: Map<String, BackupTarget> = emptyMap()
    private var directory: SafDirectory? = null
    private var directoryLabel: String? = null
    private var includeObb: Boolean = true
    private var includeApk: Boolean = false
    private var running: Boolean = false
    private var currentId: String? = null
    private var stage: String? = null
    private var cancelToken: CancelToken? = null
    private var workerActive: Boolean = false

    // ---------------- 观察 ----------------

    fun addListener(listener: Listener) {
        synchronized(lock) { listeners.add(listener) }
        listener.onQueueChanged(snapshot())
    }

    fun removeListener(listener: Listener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            tasks = tasks.toList(),
            running = running,
            currentId = currentId,
            stage = stage,
            directoryLabel = directoryLabel,
        )
    }

    // ---------------- 准备 / 编辑 ----------------

    /**
     * 用「选中的应用 + 、SAF 目录 + 两个开关」重建队列（旧队列直接被替换）。
     * @return 入队数量（0 表示没有可备份的项）。
     */
    fun prepare(
        context: Context,
        selected: List<BackupTarget>,
        treeUri: Uri,
        includeObb: Boolean,
        includeApk: Boolean,
    ): Int {
        if (selected.isEmpty()) return 0
        val dir = SafDirectory(context.applicationContext.contentResolver, treeUri)
        val newTasks = selected.map {
            BackupTask.new(it).copy(includesObb = includeObb)
        }
        synchronized(lock) {
            if (running) return 0
            this.targets = selected.associateBy { it.packageName }
            this.tasks = newTasks
            this.directory = dir
            this.directoryLabel = dir.displayName()
            this.includeObb = includeObb
            this.includeApk = includeApk
            this.currentId = null
            this.stage = null
            this.cancelToken = CancelToken()
        }
        notifyChanged()
        return newTasks.size
    }

    fun remove(id: String) {
        synchronized(lock) {
            if (running || id == currentId) return
            tasks = tasks.filterNot { it.id == id }
        }
        notifyChanged()
    }

    fun clearFinished() {
        synchronized(lock) {
            if (running) return
            tasks = tasks.filterNot { it.status.isFinished }
        }
        notifyChanged()
    }

    /** 清空队列与目录选择（不影响已写出的备份文件）。 */
    fun reset() {
        synchronized(lock) {
            if (running) return
            tasks = emptyList()
            targets = emptyMap()
            directory = null
            directoryLabel = null
            currentId = null
            stage = null
            cancelToken = null
        }
        notifyChanged()
    }

    // ---------------- 执行 ----------------

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (running || directory == null || tasks.none { it.status == BackupStatus.WAITING }) return
            running = true
            if (workerActive) return
            workerActive = true
        }
        notifyChanged()
        worker.execute {
            try {
                loop(appContext)
            } finally {
                synchronized(lock) {
                    workerActive = false
                    running = false
                    currentId = null
                    stage = null
                }
                notifyChanged()
            }
        }
    }

    /** 取消：当前项在下一个读块中止，未开始的项标 CANCELLED 并保留结果（规格 F3）。 */
    fun cancel() {
        synchronized(lock) {
            cancelToken?.cancelled = true
            tasks = tasks.map {
                if (it.status == BackupStatus.WAITING) {
                    it.copy(status = BackupStatus.CANCELLED, reason = "CANCELLED")
                } else {
                    it
                }
            }
        }
        notifyChanged()
    }

    private fun loop(context: Context) {
        while (true) {
            var nextTask: BackupTask? = null
            var dir: SafDirectory? = null
            var token: CancelToken? = null
            var obb = true
            var apk = false

            synchronized(lock) {
                if (cancelToken?.cancelled == true) {
                    return
                }
                nextTask = tasks.firstOrNull { it.status == BackupStatus.WAITING }
                dir = directory
                token = cancelToken
                obb = includeObb
                apk = includeApk
                if (nextTask != null && dir != null) {
                    currentId = nextTask!!.id
                    stage = null
                    tasks = tasks.map {
                        if (it.id == currentId) it.copy(status = BackupStatus.RUNNING, reason = null) else it
                    }
                }
            }

            val task = nextTask ?: return
            val directory = dir ?: return
            val tokenNow = token ?: CancelToken()
            notifyChanged()

            val target = synchronized(lock) { targets[task.packageName] }
                ?: BackupTarget(task.packageName, task.label, task.versionName, 0L, false)

            val outcome = try {
                BackupEngine.backup(
                    context = context,
                    target = target,
                    dir = directory,
                    includeObb = obb,
                    includeApk = apk,
                    isCancelled = { tokenNow.cancelled },
                    onStage = { setStage(it) },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "backup task ${task.packageName} crashed: ${t.message}")
                BackupEngine.BackupOutcome(false, reason = StorageProxyClient.describeFailure(t))
            }

            synchronized(lock) {
                tasks = tasks.map {
                    if (it.id != task.id) return@map it
                    it.copy(
                        status = when {
                            outcome.cancelled -> BackupStatus.CANCELLED
                            outcome.success -> BackupStatus.SUCCESS
                            else -> BackupStatus.FAILED
                        },
                        reason = outcome.reason ?: outcome.note,
                        outputUri = outcome.meta?.externalFile
                            ?: outcome.meta?.obbFile
                            ?: outcome.meta?.apkFile,
                        bytes = (outcome.meta?.externalBytes ?: 0L) + (outcome.meta?.obbBytes ?: 0L),
                    )
                }
                stage = null
            }
            notifyChanged()
        }
    }

    private fun setStage(value: String) {
        synchronized(lock) {
            stage = value
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        val snapshot = snapshot()
        val listenersCopy = synchronized(lock) { listeners.toList() }
        main.post {
            for (listener in listenersCopy) {
                try {
                    listener.onQueueChanged(snapshot)
                } catch (t: Throwable) {
                    Log.w(TAG, "queue listener failed: ${t.message}")
                }
            }
        }
    }
}
