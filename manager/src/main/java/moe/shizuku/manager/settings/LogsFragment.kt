package moe.shizuku.manager.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.databinding.FragmentLogsBinding
import rikka.recyclerview.fixEdgeEffect

/**
 * KernelSU 风格「日志」Tab：大标题折叠栏 + API 调用审计日志列表。
 * 逻辑迁移自原 ApiLogActivity（数据由 server 端 auditCall 写入，
 * 通过 server 执行 tail 读取）。
 */
class LogsFragment : Fragment() {

    private val adapter = LogAdapter()
    private var binding: FragmentLogsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentLogsBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return

        binding.toolbar.title = getString(R.string.api_log_title)
        binding.toolbar.inflateMenu(R.menu.api_log)

        // 给返回箭头的情形：从工具箱 push 进来（上一级是工具箱），或者日志没放进底栏
        // （那时它是普通次级页）。从底栏 Tab 进来时不加箭头，当顶层页用。
        val nav = runCatching {
            androidx.navigation.fragment.NavHostFragment.findNavController(this)
        }.getOrNull()
        val fromToolbox = nav?.previousBackStackEntry?.destination?.id ==
            moe.shizuku.manager.R.id.toolbox_fragment
        if (fromToolbox || !moe.shizuku.manager.ui.nav.NavTabs.isEnabled(moe.shizuku.manager.ui.nav.NavTabs.KEY_LOGS)) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            binding.toolbar.setNavigationOnClickListener { nav?.navigateUp() }
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    reload()
                    true
                }
                else -> false
            }
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        // 亚克力悬浮导航遮挡避让：末项可完整滚到胶囊上方
        recyclerView.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.ksu_content_bottom_padding))

        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun reload() {
        Thread {
            val result = ActivationRunner.run(
                "tail -n 200 /data/local/tmp/shizako-api.log",
                timeoutSeconds = 10
            )
            activity?.runOnUiThread {
                val text = when {
                    result.error != null -> getString(R.string.api_log_unavailable)
                    result.output.isBlank() ||
                        result.output.contains("No such file") -> getString(R.string.api_log_empty)
                    else -> result.output
                }
                adapter.submit(text.lineSequence().toList())
            }
        }.start()
    }

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

        private var lines: List<String> = emptyList()

        fun submit(list: List<String>) {
            lines = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                val h = resources.getDimensionPixelSize(R.dimen.activity_vertical_margin)
                val v = resources.getDimensionPixelSize(R.dimen.home_margin)
                setPadding(h, v, h, v)
            }
            return VH(tv)
        }

        override fun getItemCount(): Int = lines.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.textView.text = lines[position]
        }

        inner class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }
}