package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeActivationItemBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * 首页「一站式激活」入口卡。
 *
 * 首页只保留**一个入口**（对齐 Shizuku 的做法）：显示服务当前状态，
 * 点「开始激活」进入一站式激活页 —— Root / 无线调试 / 电脑 ADB / Dhizuku
 * 四种方式、分步教程、通知栏配对都在那一页里，首页不再铺开一堆按钮。
 */
class ActivationViewHolder(private val binding: HomeActivationItemBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeActivationItemBinding.inflate(inflater, outer.root, true)
            ActivationViewHolder(inner, outer.root)
        }
    }

    init {
        binding.activationActionButton.setOnClickListener { v ->
            v.context.startActivity(
                MainActivity.destinationIntent(v.context, R.id.activation_fragment)
            )
        }
    }

    override fun onBind() {
        binding.activationHubDesc.setText(
            if (data?.isRunning == true) {
                R.string.activation_hub_desc_running
            } else {
                R.string.activation_hub_desc_not_running
            }
        )
    }
}
