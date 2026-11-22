package moe.shizuku.manager.batch

import android.content.Context
import android.os.Process
import android.util.Log
import moe.shizuku.manager.activation.ActivationRunner
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 把批量操作的**每一项**写进既有的 API 审计日志。
 *
 * 日志文件与格式沿用 server 的 `auditCall`（`/data/local/tmp/shizako-api.log`）：
 *
 * ```
 * MM-dd HH:mm:ss,uid,pid,func,allow[,detail]
 * ```
 *
 * 前 5 列与 server 完全一致，「API 调用日志」页面（LogsFragment）原样能读；
 * 第 6 列是本功能追加的明细（`分号分隔`，避免和逗号列冲突）：
 *
 * ```
 * src=ui;batch=<uuid>;type=GRANT;target=SHIZUKU;pkg=com.foo;uid=10123;result=SUCCESS;reason=
 * ```
 *
 * 写文件的身份要求：App 是普通应用 uid，写不了 `/data/local/tmp`，所以走
 * [ActivationRunner]（服务端 `sh -c`，shell/root 身份）追加。服务没在跑时
 * 先把行攒在本地（最多 [MAX_PENDING] 条），下一次有服务时补写 —— 否则
 * Dhizuku 页（不依赖 Shizuku 服务）的审计会直接丢掉。
 */
object ApiAuditLog {

    private const val TAG = "BatchAudit"
    private const val LOG_PATH = "/data/local/tmp/shizako-api.log"

    private const val PREFS_NAME = "batch_audit"
    private const val KEY_PENDING = "pending_lines"
    private const val MAX_PENDING = 200

    /** 单线程串行：批量可能几百项，不能一项一个线程。 */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shizako-batch-audit").apply { isDaemon = true }
    }

    /**
     * 记录一条批量操作审计。
     *
     * @param func 操作种类，例如 `batch.grant.shizuku` / `batch.revoke.dhizuku` / `batch.undo`
     * @param allowed 结果是否成功（写进第 5 列的 allow/deny，和 server 同义）
     * @param detail 第 6 列明细（不要含换行；逗号请用分号替代）
     */
    fun record(context: Context, func: String, allowed: Boolean, detail: String) {
        val line = format(func, allowed, detail)
        val appContext = context.applicationContext
        executor.execute { appendOrQueue(appContext, line) }
    }

    /** 有服务时把攒下的行补写掉（页面回到前台时也可以主动调一次）。 */
    fun flushPending(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            val pending = readPending(appContext)
            if (pending.isEmpty()) return@execute
            if (tryAppend(pending)) writePending(appContext, emptyList())
        }
    }

    private fun format(func: String, allowed: Boolean, detail: String): String {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeDetail = detail.replace('\n', ' ').replace('\r', ' ')
        return "$stamp,${Process.myUid()},${Process.myPid()},$func," +
            (if (allowed) "allow" else "deny") + ",$safeDetail"
    }

    private fun appendOrQueue(context: Context, line: String) {
        val pending = readPending(context) + line
        if (tryAppend(pending)) {
            writePending(context, emptyList())
        } else {
            writePending(context, pending.takeLast(MAX_PENDING))
        }
    }

    /** 尝试把若干行一次性追加进服务端日志；失败返回 false（由调用方保留待补写队列）。 */
    private fun tryAppend(lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        if (!serviceAvailable()) return false
        val quoted = lines.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val command = "printf '%s\\n' $quoted >> $LOG_PATH"
        return try {
            val result = ActivationRunner.run(command, timeoutSeconds = 10)
            if (!result.success) {
                Log.w(TAG, "append failed: ${result.error?.message ?: result.output}")
            }
            result.success
        } catch (t: Throwable) {
            Log.w(TAG, "append threw", t)
            false
        }
    }

    private fun serviceAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun readPending(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it, null) }
    }

    private fun writePending(context: Context, lines: List<String>) {
        val array = JSONArray()
        lines.forEach { array.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING, array.toString())
            .apply()
    }
}
