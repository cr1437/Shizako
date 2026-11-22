package moe.shizuku.manager.installer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * 批量安装队列（规格 F3/F4 的执行侧）：**顺序执行、一次一个、单项失败不中断**。
 *
 * 三条约束决定了实现形态：
 * 1. 只有本应用 UI 能启动它 —— 没有广播/服务入口，[start] 只由批量页的按钮调用；
 * 2. 队列状态要能在界面反复进出、进程被杀后仍能提示「还有未完成项」→ 每次变化都
 *    持久化到 [InstallQueueStore]（该 store 只在「有未完成项」时落盘）；
 * 3. 安装本身是分钟级阻塞操作 → 单独一个线程跑，状态加锁，回调统一 post 到主线程
 *    （Compose 只能从主线程改状态）。
 *
 * 取消的语义：当前项在**提交会话之前**可中止（回写 CANCELLED），已提交的 commits
 * 不可撤；未开始的项直接标 CANCELLED 并保留已完成项（规格 F3）。
 */
object InstallQueue {

    private const val TAG = "ShizakoInstall"

    data class Snapshot(
        val tasks: List<InstallTask>,
        val running: Boolean,
        val paused: Boolean,
        val currentId: String?,
        /** 当前项的 split 写入进度（已写入, 总数）；不在安装时为 null。 */
        val stage: Pair<Int, Int>?,
    ) {
        val finished: Int get() = InstallQueueModel.finishedCount(tasks)
        val total: Int get() = tasks.size
        val hasUnfinished: Boolean get() = InstallQueueModel.hasUnfinished(tasks)
    }

    interface Listener {
        fun onQueueChanged(snapshot: Snapshot)
    }

    private val lock = Any()
    private val listeners = LinkedHashSet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shizako-install-queue").apply { isDaemon = true }
    }

    private var tasks: List<InstallTask> = emptyList()
    private var running = false
    private var paused = true
    private var currentId: String? = null
    private var stage: Pair<Int, Int>? = null
    private var cancelToken: SessionInstaller.CancelToken? = null
    private var workerActive = false
    private var restored = false

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
            paused = paused,
            currentId = currentId,
            stage = stage,
        )
    }

    /**
     * 启动时把断点读回内存（**不自动开始**）。批量页/设置页进入时调一次即可。
     */
    fun restore(context: Context) {
        synchronized(lock) {
            if (restored) return
            restored = true
            if (tasks.isNotEmpty()) return
            val saved = InstallQueueStore.load(context) ?: return
            tasks = saved.tasks
            paused = true
            running = false
        }
        notifyChanged()
    }

    // ---------------- 编辑 ----------------

    fun add(context: Context, requests: List<InstallRequest>): Int {
        if (requests.isEmpty()) return 0
        val incoming = requests.map {
            InstallTask.new(
                fileUri = it.fileUri,
                fileName = it.fileName,
                format = it.format,
                sizeBytes = it.sizeBytes,
                source = it.source,
                sourcePackage = it.sourcePackage,
            )
        }
        val before = snapshot().tasks.size
        synchronized(lock) {
            if (!restored) restored = true
            tasks = InstallQueueModel.add(tasks, incoming)
        }
        persist(context)
        notifyChanged()
        return snapshot().tasks.size - before
    }

    fun remove(context: Context, id: String) {
        synchronized(lock) {
            if (id == currentId) return // 正在装的项不能抽走
            tasks = InstallQueueModel.remove(tasks, id)
        }
        persist(context)
        notifyChanged()
    }

    fun move(context: Context, id: String, delta: Int) {
        synchronized(lock) {
            if (running) return // 执行中排序没有意义，还会让「当前 X/N」错位
            tasks = InstallQueueModel.move(tasks, id, delta)
        }
        persist(context)
        notifyChanged()
    }

    fun retry(context: Context, id: String) {
        synchronized(lock) { tasks = InstallQueueModel.retry(tasks, id) }
        persist(context)
        notifyChanged()
    }

    fun clearFinished(context: Context) {
        synchronized(lock) {
            if (running) return
            tasks = InstallQueueModel.clearFinished(tasks)
        }
        persist(context)
        notifyChanged()
    }

    /** 丢弃整个队列（含断点）。只允许在没在跑的时候调。 */
    fun discard(context: Context) {
        synchronized(lock) {
            if (running) return
            tasks = emptyList()
            paused = true
            currentId = null
            stage = null
        }
        InstallQueueStore.clear(context)
        notifyChanged()
    }

    // ---------------- 执行 ----------------

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (running || !InstallQueueModel.hasWaiting(tasks)) return
            paused = false
            running = true
            if (workerActive) return
            workerActive = true
        }
        notifyChanged()
        worker.execute {
            try {
                loop(appContext)
            } finally {
                synchronized(lock) { workerActive = false }
                persist(appContext)
                notifyChanged()
            }
        }
    }

    /** 暂停：当前项**跑完**才停（规格没要求立刻中止；立刻中止就是取消的语义）。 */
    fun pause(context: Context) {
        synchronized(lock) { paused = true }
        persist(context)
        notifyChanged()
    }

    fun cancel(context: Context) {
        synchronized(lock) {
            cancelToken?.cancelled = true
            paused = true
            // 正在装的那一项由执行器回写 CANCELLED；这里只处理还没开始的
            tasks = InstallQueueModel.cancelWaiting(tasks, currentId)
            if (!running) {
                currentId = null
                stage = null
            }
        }
        persist(context)
        notifyChanged()
    }

    private fun loop(context: Context) {
        val token = SessionInstaller.CancelToken()
        synchronized(lock) { cancelToken = token }
        while (true) {
            val task = synchronized(lock) {
                if (!running || paused) {
                    null
                } else {
                    InstallQueueModel.nextWaiting(tasks)
                }
            } ?: break

            synchronized(lock) {
                currentId = task.id
                stage = null
                tasks = InstallQueueModel.replace(
                    tasks,
                    task.copy(status = TaskStatus.INSTALLING, reason = null),
                )
            }
            persist(context)
            notifyChanged()

            val outcome = try {
                Installer.install(
                    context = context,
                    request = task.toRequest(),
                    cancel = token,
                    onStage = { done, total ->
                        synchronized(lock) { stage = done to total }
                        notifyChanged()
                    },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "install task crashed", t)
                Installer.Outcome(
                    success = false,
                    status = TaskStatus.FAILED,
                    error = InstallError.UNKNOWN,
                    reason = t.message ?: t.javaClass.simpleName,
                    packageName = null,
                    versionName = null,
                    format = task.format,
                    durationMs = 0L,
                )
            }

            synchronized(lock) {
                tasks = InstallQueueModel.replace(
                    tasks,
                    task.copy(
                        status = outcome.status,
                        reason = outcome.reason,
                        format = outcome.format,
                    ),
                )
                currentId = null
                stage = null
            }
            persist(context)
            notifyChanged()

            if (token.cancelled) break
        }

        synchronized(lock) {
            // 取消后把还挂着的 WAITING 一并标掉（规格：未开始项 CANCELLED，已完成保留）
            if (token.cancelled) {
                tasks = InstallQueueModel.cancelWaiting(tasks, null)
            }
            running = false
            currentId = null
            stage = null
        }
        persist(context)
        notifyChanged()
    }

    // ---------------- 内部 ----------------

    private fun persist(context: Context) {
        val snapshot = snapshot()
        runCatching { InstallQueueStore.save(context, snapshot.tasks, snapshot.paused) }
            .onFailure { Log.w(TAG, "queue persist failed", it) }
    }

    private fun notifyChanged() {
        val snapshot = snapshot()
        val copy = synchronized(lock) { listeners.toList() }
        if (copy.isEmpty()) return
        main.post {
            copy.forEach { listener ->
                runCatching { listener.onQueueChanged(snapshot) }
                    .onFailure { Log.w(TAG, "queue listener failed", it) }
            }
        }
    }
}
