package moe.shizuku.manager.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import moe.shizuku.manager.BuildConfig
import java.io.File

/**
 * 启动耗时打点（只看 DEBUG 包）。
 *
 * 冷启动那几百毫秒到底花在哪，靠猜没用 —— 每个阶段前后各记一笔。
 *
 * 为什么还要写文件：部分国产 ROM（vivo / OPPO 等）会把应用日志从 logcat 里滤掉，
 * 只看得到系统日志。所以这里同时把结果**追加写进 `cacheDir/startup-trace.txt`**，
 * 用 `adb shell su -c cat /data/data/<包名>/cache/startup-trace.txt` 就能读出来。
 *
 * 用法：
 * ```
 * val t = StartupTrace.begin()
 * ... // 一段启动路径
 * t.end(context, "applyCustomBackground")
 * ```
 */
object StartupTrace {

    private const val TAG = "ShizakoPerf"
    private const val FILE = "startup-trace.txt"

    /** 每次冷启动只保留最近 3 轮的记录，避免文件越写越长 */
    private const val MAX_LINES = 120

    private var launchMark: Long = 0L

    fun now(): Long = SystemClock.uptimeMillis()

    fun begin(): Long {
        val t = now()
        launchMark = t
        return t
    }

    fun since(context: Context?, start: Long, phase: String) {
        if (!BuildConfig.DEBUG) return
        val cost = now() - start
        val fromLaunch = if (launchMark > 0) now() - launchMark else cost
        Log.i(TAG, "$phase = ${cost}ms")
        append(context, "$phase\t${cost}ms\t(from launch ${fromLaunch}ms)")
    }

    fun note(context: Context?, text: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, text)
        append(context, text)
    }

    private fun append(context: Context?, text: String) {
        val ctx = context?.applicationContext ?: return
        try {
            val f = File(ctx.cacheDir, FILE)
            val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            lines.add(text)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            f.writeText(lines.joinToString("\n"))
        } catch (e: Throwable) {
            // 打点失败不影响功能
        }
    }

    /** 新一轮冷启动：文件里插一条分隔线，方便读 */
    fun newLaunch(context: Context?) {
        append(context, "---- launch ${System.currentTimeMillis()} ----")
    }
}
