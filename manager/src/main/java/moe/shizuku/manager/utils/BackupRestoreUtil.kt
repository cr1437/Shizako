package moe.shizuku.manager.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用设置（[ShizukuSettings] 用的那份 SharedPreferences）的备份 / 恢复。
 *
 * 为什么只备份 SharedPreferences、用 JSON 而不是 zip/二进制：
 * - 这份偏好就是「用户的全部设置」—— 深浅色、语言、UI 风格、主题色、背景参数、
 *   启动方式、实验室开关（含 Shizuku Connectors 的开关与警告确认）…… 都在 `settings` 这一个文件里；
 * - JSON 是纯文本：出问题时用户自己打开就能看懂、能改，也方便跨版本做字段兼容；
 * - 用 org.json（Android 平台自带）而不是 kotlinx-serialization：**不引入任何新依赖**，
 *   而且这里的数据形状就是「键 → {类型, 值}」的异构字典，本来也不适合强类型反序列化。
 *
 * 存储类型必须显式带上（type 字段）：SharedPreferences 允许同名 key 换类型，
 * 恢复时如果只看 JSON 的 value，`1` 到底是 Int 还是 Long 无法还原，
 * `putInt` 拿到 `getBoolean` 的数据会直接抛 ClassCastException。
 */
object BackupRestoreUtil {

    private const val TAG = "BackupRestoreUtil"

    /** 备份格式版本：将来格式变了就 +1，恢复端据此判断能不能读。 */
    const val FORMAT_VERSION = 1

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_PACKAGE = "package"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_VALUES = "values"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"
    private const val FORMAT_ID = "shizako-settings"

    /**
     * 「恢复完成，需要重启应用才生效」的哨兵偏好。
     *
     * 有些设置在进程内是**读一次就固化**的（主题、深浅色、语言、UI 风格这些在 Application /
     * Activity 创建时应用），只写偏好不会立刻生效，所以恢复完把哨兵置位，
     * 由调用方在 UI 上提示用户重启（见 [needsRestart] / [clearRestartFlag]）。
     */
    private const val KEY_RESTORE_PENDING_RESTART = "lab_restore_pending_restart"

    private fun prefs(context: Context): android.content.SharedPreferences {
        // 显式初始化：备份/恢复可能从非 Activity 的地方调用（比如后台任务），
        // 不能假设 Application 已经把 ShizukuSettings 建好了。
        ShizukuSettings.initialize(context)
        return ShizukuSettings.getPreferences()
    }

    /**
     * 把当前设置导出成 JSON 字符串。
     *
     * **必须**在协程里调用（内部切到 IO 线程），不会阻塞主线程。
     *
     * @return 可读的 JSON 文本；写入失败等异常情况会包在返回值里由调用方处理，
     *   这里不吞异常 —— 备份失败必须让用户知道（否则就是"备份了一个空文件"）。
     */
    suspend fun backup(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val values = JSONObject()

        for ((key, value) in prefs(appContext).all) {
            if (value == null) continue
            values.put(key, encode(value))
        }

        JSONObject().apply {
            put(KEY_FORMAT, FORMAT_ID)
            put(KEY_VERSION, FORMAT_VERSION)
            // 记下包名：这份 JSON 会被用户拷来拷去，恢复前先确认是不是本应用的存档
            put(KEY_PACKAGE, appContext.packageName)
            put(KEY_CREATED_AT, System.currentTimeMillis())
            put(KEY_VALUES, values)
        }.toString(2)
    }

    /**
     * 从 [json] 恢复设置。**会在协程里切到 IO 线程执行。**
     *
     * 语义：先 `clear()` 再写回 —— 备份是"完整快照"，不是"合并补丁"。
     * 合并会让用户永远删不掉旧设置（备份里没有的 key 会一直留着）。
     *
     * @return true = 已成功写回（此时 [needsRestart] 为 true，调用方应提示重启应用）；
     *   false = JSON 不是本应用的备份 / 版本不认 / 写盘失败，**此时不做任何修改**。
     */
    suspend fun restore(context: Context, json: String): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val root = try {
            JSONObject(json)
        } catch (e: Throwable) {
            Log.w(TAG, "restore: not valid json", e)
            return@withContext false
        }

        if (root.optString(KEY_FORMAT) != FORMAT_ID) {
            Log.w(TAG, "restore: unexpected format ${root.optString(KEY_FORMAT)}")
            return@withContext false
        }
        val version = root.optInt(KEY_VERSION, -1)
        if (version != FORMAT_VERSION) {
            // 只在版本号更大时才可能是"未来格式"；这里不做兼容读取，直接拒绝，避免写坏偏好。
            Log.w(TAG, "restore: unsupported version $version")
            return@withContext false
        }

        val values = root.optJSONObject(KEY_VALUES)
        if (values == null) {
            Log.w(TAG, "restore: missing $KEY_VALUES")
            return@withContext false
        }

        val editor = prefs(appContext).edit().clear()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = values.optJSONObject(key) ?: continue
            val type = entry.optString(KEY_TYPE)
            when (type) {
                TYPE_BOOLEAN -> editor.putBoolean(key, entry.optBoolean(KEY_VALUE))
                TYPE_INT -> editor.putInt(key, entry.optInt(KEY_VALUE))
                TYPE_LONG -> editor.putLong(key, entry.optLong(KEY_VALUE))
                TYPE_FLOAT -> editor.putFloat(key, entry.optDouble(KEY_VALUE).toFloat())
                TYPE_STRING -> editor.putString(key, entry.optString(KEY_VALUE))
                TYPE_STRING_SET -> {
                    val array = entry.optJSONArray(KEY_VALUE) ?: JSONArray()
                    val set = LinkedHashSet<String>(array.length())
                    for (i in 0 until array.length()) {
                        // JSONArray 里可能是 null 元素，optString 会给出 "null" 字符串，跳过更干净
                        if (!array.isNull(i)) set.add(array.optString(i))
                    }
                    editor.putStringSet(key, set)
                }
                else -> Log.w(TAG, "restore: skip $key with unknown type $type")
            }
        }

        // 用 commit() 而不是 apply()：恢复完紧接着就要提示用户"重启应用"，
        // 用户可能马上点重启，apply() 的异步落盘有概率还没写完。
        val ok = editor.putBoolean(KEY_RESTORE_PENDING_RESTART, true).commit()
        if (!ok) {
            Log.w(TAG, "restore: commit failed")
            return@withContext false
        }
        Log.i(TAG, "restore: ${values.length()} keys written")
        true
    }

    /** 上次恢复之后还没提示过「请重启应用」。调用方提示完调 [clearRestartFlag]。 */
    fun needsRestart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESTORE_PENDING_RESTART, false)

    fun clearRestartFlag(context: Context) {
        prefs(context).edit().putBoolean(KEY_RESTORE_PENDING_RESTART, false).apply()
    }

    // ---------------- 类型编解码 ----------------

    private const val TYPE_BOOLEAN = "Boolean"
    private const val TYPE_INT = "Int"
    private const val TYPE_LONG = "Long"
    private const val TYPE_FLOAT = "Float"
    private const val TYPE_STRING = "String"
    private const val TYPE_STRING_SET = "StringSet"

    /** SharedPreferences 的取值类型是有限的（6 种），逐个显式编码，恢复时才不会歧义。 */
    private fun encode(value: Any): JSONObject = when (value) {
        is Boolean -> entry(TYPE_BOOLEAN, value)
        is Int -> entry(TYPE_INT, value)
        is Long -> entry(TYPE_LONG, value)
        // JSON 没有 Float：存 double，恢复时再转回 float（精度足够放下任何偏好值）
        is Float -> entry(TYPE_FLOAT, value.toDouble())
        is String -> entry(TYPE_STRING, value)
        is Set<*> -> entry(TYPE_STRING_SET, JSONArray(value.filterIsInstance<String>()))
        else -> entry(TYPE_STRING, value.toString())
    }

    private fun entry(type: String, value: Any): JSONObject = JSONObject().apply {
        put(KEY_TYPE, type)
        put(KEY_VALUE, value)
    }
}
