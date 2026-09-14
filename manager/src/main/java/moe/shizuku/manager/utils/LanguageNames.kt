package moe.shizuku.manager.utils

import android.content.Context
import moe.shizuku.manager.R
import java.util.Locale

/**
 * 语言列表里的显示名。
 *
 * 规矩：**中文只按文字分，写全** —— 「简体中文」/「繁體中文」。
 * 不写 `zh-CN` / `zh-TW`（带地区，不合适），也不写「简体」/「繁體」（ICU 会把脚本名缩成一个词）。
 * 资源目录同样按文字分：`values-b+zh+Hans` / `values-b+zh+Hant`，不带地区限定符。
 *
 * 其他语言仍然用**该语言自己的本土名**（Deutsch / 日本語 / Русский…）：
 * 看不懂当前界面语言的人，只有靠本土名才找得到自己的语言。
 */
object LanguageNames {

    /** 老版本存过的带地区标签 → 新标签（升级时迁移，别让主人的语言设置失效） */
    private val LEGACY_TAGS = mapOf(
        "zh-cn" to "zh-Hans",
        "zh-tw" to "zh-Hant",
        "zh-hk" to "zh-Hant",
        "zh-mo" to "zh-Hant",
        "zh-sg" to "zh-Hans",
    )

    /** 把偏好里存的语言标签规整成当前用的写法 */
    @JvmStatic
    fun normalize(tag: String?): String {
        val raw = tag?.trim().orEmpty()
        if (raw.isEmpty()) return "SYSTEM"
        return LEGACY_TAGS[raw.lowercase(Locale.ROOT)] ?: raw
    }

    @JvmStatic
    fun display(context: Context, tag: String?): String {
        val lower = normalize(tag).lowercase(Locale.ROOT)
        return when (lower) {
            "zh", "zh-cn", "zh-hans", "zh-sg" -> context.getString(R.string.language_zh_hans)
            "zh-tw", "zh-hk", "zh-mo", "zh-hant" -> context.getString(R.string.language_zh_hant)
            "system", "" -> context.getString(R.string.follow_system)
            else -> Locale.forLanguageTag(lower).let { it.getDisplayName(it) }
        }
    }
}
