package moe.shizuku.manager.comput

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.hint.resolveHintPalette

/**
 * Comput 控制台（**底栏 Tab 版**）。
 *
 * 只做三件事：套 `fragment_sub_page.xml` 外壳（大标题折叠栏 + `appbar_scrolling_view_behavior`
 * 内容区 + 系统栏内边距）、挂 ComposeView、把页面状态交给 [ComputPage]。
 *
 * 逻辑全部在 [ComputPage] 里 —— 设置里的「Comput 控制台」二级页用的是同一个 Composable，
 * 所以两个入口不会出现行为/观感不一致（以前这里自己维护了一套状态，和设置页会漂移）。
 */
class ComputFragment : Fragment() {

    private var binding: FragmentSubPageBinding? = null

    private val listState = androidx.compose.foundation.lazy.LazyListState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentSubPageBinding.inflate(inflater, container, false)
        this.binding = binding
        binding.toolbar.title = getString(R.string.comput_title)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.contentContainer.addView(
            ComposeView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                setContent {
                    val style = moe.shizuku.manager.ui.style.UiStyle.current
                    val palette = remember(style) { resolveHintPalette(requireContext()) }
                    ComputPage(
                        palette = palette,
                        listState = listState,
                        // 滚到底把大标题收起来，和日志/设置/模块页一个行为
                        onCollapsedChange = { expanded -> binding.appBar.setExpanded(expanded, true) },
                        showBack = false,
                        // 底栏版本也要能进「控制台设置」：开独立的工具页（返回回控制台，
                        // 不会像以前那样把人丢进设置 Tab 里）
                        onOpenSettings = {
                            moe.shizuku.manager.toolbox.ToolPageFragment.open(
                                requireContext(),
                                moe.shizuku.manager.toolbox.ToolPageFragment.TOOL_COMPUT_SETTINGS,
                            )
                        },
                    )
                }
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding?.contentContainer?.removeAllViews()
        binding = null
    }
}
