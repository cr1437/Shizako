package moe.shizuku.manager.utils

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.glass.GlassWindow

/**
 * Star 提醒的弹窗本体（单独一份，MainActivity 只在冷启动时调一次）。
 *
 * 三个按钮：去点 Star / 以后再说 / 不再提醒 —— 文案都在 strings 里，双风格都走
 * [GlassWindow.applyIfGlass]（玻璃风格下弹窗也透明 + 模糊）。
 */
object StarPromptDialog {

    /**
     * @param onStarred 点了「去点 Star」之后（用来把状态记成 done）
     */
    fun show(activity: Activity, onStarred: () -> Unit = { StarPrompt.markDone() }) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.star_prompt_title)
            .setMessage(R.string.star_prompt_message)
            .setPositiveButton(R.string.star_prompt_go) { _, _ ->
                StarPrompt.openRepo(activity)
                onStarred()
            }
            .setNeutralButton(R.string.star_prompt_later) { _, _ ->
                // markShown() 已经记过时间戳，这里什么都不用做
            }
            .setNegativeButton(R.string.star_prompt_never) { _, _ ->
                StarPrompt.markNever()
            }
            .setCancelable(true)
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }
}
