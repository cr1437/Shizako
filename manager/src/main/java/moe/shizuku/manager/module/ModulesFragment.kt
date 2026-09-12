package moe.shizuku.manager.module

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentModulesBinding
import moe.shizuku.manager.ui.hint.resolveHintPalette

/**
 * ADB 模块页（**底栏 Tab 版**）。
 *
 * 壳用 `fragment_modules.xml` —— 和日志/设置页同一个骨架（大标题折叠栏 +
 * `appbar_scrolling_view_behavior` 的内容区 + 系统栏内边距）。之前这里直接返回一个
 * ComposeView，等于没有壳：内容从状态栏下沿一直画到屏幕底部、又拿不到内容区的高度约束，
 * 才会出现"整体纵向拉伸、顶部按钮过高、底部被底栏盖住"。**和 DPI / 屏幕密度无关。**
 *
 * 界面本体是 [ModulesScreen]（和设置里那个二级页共用一份），这里是顶层页所以 `showBack = false`。
 */
class ModulesFragment : Fragment() {

    private var binding: FragmentModulesBinding? = null

    /** 列表状态：切 Tab 回来时滚动位置不丢 */
    private val listState = androidx.compose.foundation.lazy.LazyListState()

    /** 选中的模块 ZIP：选择器必须在 Fragment 初始化阶段注册（Compose 里临时注册会抛 IllegalStateException） */
    private var pendingModuleZip by mutableStateOf<Uri?>(null)

    private val moduleZipLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingModuleZip = uri
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentModulesBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.toolbar.title = getString(R.string.adb_modules_title)

        binding.modulesContainer.addView(
            ComposeView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                setContent {
                    val palette = remember { resolveHintPalette(requireContext()) }
                    ModulesScreen(
                        palette = palette,
                        listState = listState,
                        // 滚到底就把大标题收起来 —— 和日志/设置页一个行为
                        onCollapsedChange = { expanded -> binding.appBar.setExpanded(expanded, true) },
                        onBack = {},
                        showBack = false,
                        pendingZip = pendingModuleZip,
                        onZipConsumed = { pendingModuleZip = null },
                        onPickZip = {
                            moduleZipLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*"),
                            )
                        },
                        onOpenWebUi = { moduleId ->
                            startActivity(
                                ModuleWebViewActivity.newIntent(requireContext(), moduleId),
                            )
                        },
                    )
                }
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding?.modulesContainer?.removeAllViews()
        binding = null
    }
}
