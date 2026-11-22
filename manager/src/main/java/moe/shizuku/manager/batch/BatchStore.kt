package moe.shizuku.manager.batch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 批量操作记录的持久化（F5）：只保留最近 [MAX_RECORDS] 条，供「批次历史」查看
 * 与「撤销本批次」使用。
 *
 * 存在独立的 SharedPreferences（不和主设置混）：这些是操作日志类数据，
 * 「备份/恢复设置」不应该把它们一起搬走，也不该被恢复动作清掉。
 */
object BatchStore {

    private const val PREFS_NAME = "batch_store"
    private const val KEY_RECORDS = "records"

    const val MAX_RECORDS = 50

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cached = p
            return p
        }
    }

    /** 最近在前（第一条是最新的）。 */
    @Synchronized
    fun all(context: Context): List<BatchOperationRecord> {
        val raw = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<BatchOperationRecord>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            runCatching { BatchOperationRecord.fromJson(obj) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    @Synchronized
    fun find(context: Context, id: String): BatchOperationRecord? =
        all(context).firstOrNull { it.id == id }

    @Synchronized
    fun append(context: Context, record: BatchOperationRecord) {
        val records = ArrayList<BatchOperationRecord>(MAX_RECORDS + 1)
        records.add(record)
        records.addAll(all(context).take(MAX_RECORDS - 1))

        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_RECORDS, array.toString()).apply()
    }
}
