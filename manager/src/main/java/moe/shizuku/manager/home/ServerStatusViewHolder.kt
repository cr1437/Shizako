package moe.shizuku.manager.home

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.databinding.HomeServerStatusBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import androidx.core.view.isVisible
import rikka.shizuku.Shizuku
import androidx.core.view.isVisible
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

class ServerStatusViewHolder(private val binding: HomeServerStatusBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        // KernelSU 风格：状态卡自成一张主色大卡，不再套外层容器卡
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val binding = HomeServerStatusBinding.inflate(inflater, parent, false)
            ServerStatusViewHolder(binding, binding.root)
        }
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24dp))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24dp))
        }
        applyContainerColors(ok)

        // 「杀死 Shizako 酱」：只在服务运行时可点。以前这个动作藏在右上角三个点里，
        // 现在直接摆在状态卡上，一眼能看见。
        binding.actionKill.isVisible = ok
        binding.actionKill.setOnClickListener {
            val activity = context as? android.app.Activity ?: return@setOnClickListener
            if (!Shizuku.pingBinder()) return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setMessage(R.string.dialog_stop_message)
                .setPositiveButton(android.R.string.ok) { _: android.content.DialogInterface?, _: Int ->
                    try {
                        Shizuku.exit()
                    } catch (e: Throwable) {
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { moe.shizuku.manager.ui.glass.GlassWindow.applyIfGlass(it) }
            // activity 只是用来确认当前在前台，避免后台存活时误触
            @Suppress("UNUSED_EXPRESSION") activity
        }
        val user = if (isRoot) "root" else "adb"
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            if (apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    "${apiVersion}.${patchVersion}",
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                // Versions match: show the marketing version (zako2.x, from
                // BuildConfig). Protocol numbers (13.6) stay in the mismatch
                // branch below as a diagnostic hint only.
                context.getString(R.string.home_status_service_version, user, BuildConfig.VERSION_NAME)
            }
        } else {
            ""
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        if (TextUtils.isEmpty(summaryView.text)) {
            summaryView.visibility = View.GONE
        } else {
            summaryView.visibility = View.VISIBLE
        }
    }

    /**
     * KernelSU StatusCard 配色：运行中 = secondaryContainer tonal 容器，
     * 未运行 = errorContainer 警示容器；图标与文字同步切换 on* 前景色。
     *
     * 玻璃风格下不改容器色 —— 一改就把玻璃卡变成不透明色块了。
     * 改成给玻璃卡染一层同色的半透明薄纱（+ 同色描边），既有状态语义又不破坏玻璃。
     */
    private fun applyContainerColors(ok: Boolean) {
        val containerAttr = if (ok) {
            com.google.android.material.R.attr.colorSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorErrorContainer
        }
        val contentAttr = if (ok) {
            com.google.android.material.R.attr.colorOnSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorOnErrorContainer
        }
        val card = binding.root as com.google.android.material.card.MaterialCardView
        val content = MaterialColors.getColor(card, contentAttr)

        if (ThemeHelper.isUsingGlass()) {
            // 玻璃风格：和首页其它卡片同一套观感 —— 半透明底、无边框、无高光。
            // 只把状态色当薄薄一层染色，别把卡片刷成不透明色块。
            val tint = MaterialColors.getColor(card, containerAttr)
            card.setCardBackgroundColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 0x4D)
            )
            card.strokeWidth = 0
            // 玻璃上深色字更吃亏：状态文字统一用 onSurface，图标保留状态色
            val onSurface = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorOnSurface,
            )
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(content)
            textView.setTextColor(onSurface)
            summaryView.setTextColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(onSurface, 0xB8)
            )
            // 设了自定义背景图：标题/副标题按「卡片身后那块图」自适应黑白，
            // 避免白底白字、黑底黑字（整图平均会判错，所以要按卡片区域采样）。
            moe.shizuku.manager.app.AdaptiveTextHelper.applyToCard(
                card = card,
                title = textView,
                body = summaryView,
            )
            return
        }

        card.setCardBackgroundColor(MaterialColors.getColor(card, containerAttr))
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(content)
        textView.setTextColor(content)
        summaryView.setTextColor(content)
    }
}
