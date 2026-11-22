package moe.shizuku.manager.installer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 未完成队列的断点（规格 F3 最后一条）。
 *
 * 语义刻意是「**只记不续**」：应用被杀/进程重启后队列**不会自动开始**（自动接着装
 * 等于绕过用户那一次点击，尤其是外部应用请求来的队列），只把「上次还有几项没做完」
 * 留在设置页与批量页上，用户点「继续」才跑。
 *
 * 因此这里只保存**状态**，不保存「正在安装」的那一项的历史语义：进程被杀时那一项
 * 会停留在 `INSTALLING`，[load] 会把它归一化成 `WAITING`（重试一次比留一个假状态好）。
 */
object InstallQueueStore {

    private const val PREFS_NAME = "install_queue"
    private const val KEY_TASKS = "tasks"
    private const val KEY_PAUSED = "paused"
    private const val KEY_SAVED_AT = "saved_at"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cached = p
            return p
        }
    }

    data class Saved(val tasks: List<InstallTask>, val paused: Boolean, val savedAt: Long)

    @Synchronized
    fun load(context: Context): Saved? {
        val raw = prefs(context).getString(KEY_TASKS, null) ?: return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val tasks = ArrayList<InstallTask>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            runCatching { InstallTask.fromJson(obj) }.getOrNull()?.let { tasks.add(it) }
        }
        if (tasks.isEmpty()) return null
        val normalized = tasks.map {
            if (it.status == TaskStatus.INSTALLING) it.copy(status = TaskStatus.WAITING, reason = null) else it
        }
        return Saved(
            tasks = normalized,
            paused = prefs(context).getBoolean(KEY_PAUSED, true),
            savedAt = prefs(context).getLong(KEY_SAVED_AT, 0L),
        )
    }

    /** 有未完成项才落盘；全部结束时清掉（否则会留下一条「永远未完成」的假记录）。 */
    @Synchronized
    fun save(context: Context, tasks: List<InstallTask>, paused: Boolean) {
        if (!InstallQueueModel.hasUnfinished(tasks) || tasks.isEmpty()) {
            clear(context)
            return
        }
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        prefs(context).edit()
            .putString(KEY_TASKS, array.toString())
            .putBoolean(KEY_PAUSED, paused)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_TASKS)
            .remove(KEY_PAUSED)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    /** 未完成的项数（0 表示没有断点）。设置页用它做摘要。 */
    fun unfinishedCount(context: Context): Int {
        val saved = load(context) ?: return 0
        return saved.tasks.count { !it.status.isFinished }
    }

    /** 只留一个 JSON 便于日志排查（不含文件内容）。 */
    fun describe(context: Context): String {
        val saved = load(context) ?: return "none"
        return JSONObject()
            .put("tasks", saved.tasks.size)
            .put("unfinished", saved.tasks.count { !it.status.isFinished })
            .put("paused", saved.paused)
            .put("savedAt", saved.savedAt)
            .toString()
    }
}
