package moe.shizuku.manager.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSettingsBinding

/**
 * KernelSU 风格「设置」Tab：大标题折叠栏容器，
 * 内嵌现有 SettingsFragment（设置内容零改动）。
 */
class SettingsTabFragment : Fragment() {

    private var binding: FragmentSettingsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentSettingsBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.toolbar.title = getString(R.string.settings_title)

        // 默认是顶层 Tab（没有上一级）；从工具箱 push 进来时给返回箭头，返回回工具箱
        val nav = runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this) }
            .getOrNull()
        if (nav?.previousBackStackEntry?.destination?.id == R.id.toolbox_fragment) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            binding.toolbar.setNavigationOnClickListener { nav.navigateUp() }
        } else {
            binding.toolbar.setNavigationOnClickListener { /* 顶层 Tab，无返回 */ }
        }

        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    /** 大标题当前是不是展开的（去重用，见 [setAppBarExpanded]） */
    private var appBarExpanded = true

    /** 供内层 Compose 设置页联动大标题折叠（列表滚动时收起/展开）。 */
    fun setAppBarExpanded(expanded: Boolean) {
        val appBar = binding?.appBar ?: return
        // 状态没变就别再动画一次：滚动中反复 setExpanded 会让 AppBarLayout 的偏移
        // 与内容视图对不上，之后整页都滚不动（"返回设置有概率卡住"的来源之一）
        if (expanded == appBarExpanded) return
        appBarExpanded = expanded
        appBar.setExpanded(expanded, true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
