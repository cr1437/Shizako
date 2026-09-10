package moe.shizuku.manager.home

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeInfoCardBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * KernelSU 风格信息卡（参照 KernelSU Home.kt InfoCard）：
 * 单张卡片内纵向展示 Shizako 版本 / Android / ABI / 服务状态 / Dhizuku / 已授权应用数，
 * 右下角「复制」按钮把全部信息写入剪贴板。
 * data = ServiceStatus to grantedCount。
 */
class InfoCardViewHolder(private val binding: HomeInfoCardBinding, root: View) :
    BaseViewHolder<Pair<ServiceStatus, Int>>(root) {

    companion object {
        val CREATOR = Creator<Pair<ServiceStatus, Int>> { inflater: LayoutInflater, parent: ViewGroup? ->
            val binding = HomeInfoCardBinding.inflate(inflater, parent, false)
            InfoCardViewHolder(binding, binding.root)
        }
    }

    init {
        binding.infoCopy.setOnClickListener { copyAll() }
    }

    override fun onBind() {
        val context = itemView.context
        val (status, grantedCount) = data

        binding.valueShizako.text = BuildConfig.VERSION_NAME
        binding.valueAndroid.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.valueAbi.text = Build.SUPPORTED_ABIS?.firstOrNull() ?: "-"

        val running = status.isRunning
        val user = if (status.uid == 0) "root" else "adb"
        binding.valueService.text =
            if (running) context.getString(R.string.home_info_service_running, user)
            else context.getString(R.string.home_info_service_not_running)

        binding.valueDhizuku.text = context.getString(
            if (isDhizukuActive(context)) R.string.home_info_dhizuku_active
            else R.string.home_info_dhizuku_inactive
        )

        binding.valueApps.text = context.getString(R.string.home_info_apps_count, grantedCount)
    }

    /** KernelSU InfoCard 同款：把 "label: value" 逐行复制到剪贴板 */
    private fun copyAll() {
        val context = itemView.context
        val rows = listOf(
            context.getString(R.string.app_name) to binding.valueShizako.text,
            context.getString(R.string.home_info_android) to binding.valueAndroid.text,
            context.getString(R.string.home_info_abi) to binding.valueAbi.text,
            context.getString(R.string.home_info_service) to binding.valueService.text,
            context.getString(R.string.home_info_dhizuku) to binding.valueDhizuku.text,
            context.getString(R.string.home_info_apps) to binding.valueApps.text,
        )
        val text = rows.joinToString("\n") { (label, value) -> "$label: $value" }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        Toast.makeText(context, R.string.home_info_copied, Toast.LENGTH_SHORT).show()
    }

    private fun isDhizukuActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }
}
