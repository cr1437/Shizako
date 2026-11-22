package moe.shizuku.manager.installer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 安装历史（规格 F5）：只保留最近 [MAX_ENTRIES] 条，新的在前。
 *
 * 和 `batch/BatchStore` 一样独立一个 prefs：这些是审计类数据，
 * 「备份/恢复设置」不该把它们一起搬走，也不该被恢复动作清掉。
 */
object InstallHistoryStore {

    private const val PREFS_NAME = "install_history"
    private const val KEY_ENTRIES = "entries"

    const val MAX_ENTRIES = 200

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

    @Synchronized
    fun all(context: Context): List<InstallHistoryEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<InstallHistoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            runCatching { InstallHistoryEntry.fromJson(obj) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    @Synchronized
    fun append(context: Context, entry: InstallHistoryEntry) {
        val list = ArrayList<InstallHistoryEntry>(MAX_ENTRIES)
        list.add(entry)
        list.addAll(all(context).take(MAX_ENTRIES - 1))
        write(context, list)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun write(context: Context, list: List<InstallHistoryEntry>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
