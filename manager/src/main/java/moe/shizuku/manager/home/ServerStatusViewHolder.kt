package moe.shizuku.manager.home

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeServerStatusBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
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
        card.setCardBackgroundColor(MaterialColors.getColor(card, containerAttr))
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(content)
        textView.setTextColor(content)
        summaryView.setTextColor(content)
    }
}
