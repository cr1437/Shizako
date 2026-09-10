package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.databinding.HomeActivationItemBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class ActivationViewHolder(private val binding: HomeActivationItemBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeActivationItemBinding.inflate(inflater, outer.root, true)
            ActivationViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    private inline val title get() = binding.text1
    private inline val summary get() = binding.text2

    override fun onBind() {
        val context = itemView.context
        if (!data.isRunning) {
            itemView.isEnabled = false
            title.setText(R.string.home_activation_title)
            summary.setText(R.string.home_activation_summary_not_running)
        } else {
            itemView.isEnabled = true
            title.setText(R.string.home_activation_title)
            summary.setText(R.string.home_activation_summary)
        }
    }

    override fun onClick(v: View) {
        v.context.startActivity(MainActivity.destinationIntent(v.context, R.id.activation_fragment))
    }
}
