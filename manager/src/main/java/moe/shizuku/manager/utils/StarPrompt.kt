package moe.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

/**
 * 「给项目点个 Star」提醒。
 *
 * 规矩（别做成人人烦的弹窗）：
 * - **冷启动满 [MIN_LAUNCHES] 次**才开始提 —— 主人先用得顺手，再谈 Star；
 * - 每次弹完记一个「以后再说」的时间戳，[LATER_DAYS] 天内不再提；
 * - 点了「不再提醒」就永久闭嘴；点了「去点 Star」也当已支持，不再打扰。
 *
 * 链接用**系统浏览器**打开（和关于页一致，不用内置 Custom Tabs）。
 */
object StarPrompt {

    /** 项目主页（Fork 自 Shizuku 的 Shizako） */
    const val REPO_URL = "https://github.com/cr1437/Shizako"

    private const val KEY_LAUNCHES = "star_prompt_launches"
    private const val KEY_STATE = "star_prompt_state"

    private const val MIN_LAUNCHES = 4
    private const val LATER_DAYS = 7L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun prefs() = ShizukuSettings.getPreferences()

    /** 冷启动计数：早晚各开一次 App 也算两次，够 4 次才提示 */
    fun countLaunch() {
        val prefs = prefs()
        prefs.edit().putInt(KEY_LAUNCHES, prefs.getInt(KEY_LAUNCHES, 0) + 1).apply()
    }

    /** 现在该不该弹 */
    fun shouldPrompt(): Boolean {
        val prefs = prefs()
        if (prefs.getInt(KEY_LAUNCHES, 0) < MIN_LAUNCHES) return false
        return when (val state = prefs.getString(KEY_STATE, "").orEmpty()) {
            "", "later" -> true
            "never", "done" -> false
            else -> {
                // "later:<时间戳>"：过了冷静期才再提
                val at = state.removePrefix("later:").toLongOrNull() ?: 0L
                System.currentTimeMillis() >= at
            }
        }
    }

    /** 弹过了（不管主人点了什么，先记一次，避免每次启动都弹） */
    fun markShown() {
        prefs().edit()
            .putString(KEY_STATE, "later:${System.currentTimeMillis() + LATER_DAYS * DAY_MS}")
            .apply()
    }

    fun markNever() {
        prefs().edit().putString(KEY_STATE, "never").apply()
    }

    fun markDone() {
        prefs().edit().putString(KEY_STATE, "done").apply()
    }

    /** 用系统浏览器打开项目主页 */
    fun openRepo(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) {
            rikka.core.util.ClipboardUtils.put(context, REPO_URL)
            Toast.makeText(context, R.string.star_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
