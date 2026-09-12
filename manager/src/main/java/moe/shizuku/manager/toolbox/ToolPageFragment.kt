package moe.shizuku.manager.toolbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.settings.LabFeaturesScreen
import moe.shizuku.manager.settings.ModulePolicyPage
import moe.shizuku.manager.settings.SettingsBackupHelper
import moe.shizuku.manager.settings.SettingsPage
import moe.shizuku.manager.settings.SettingsUiState
import moe.shizuku.manager.ui.hint.resolveHintPalette

/**
 * 工具页宿主：把原来只活在「设置二级页」里的那几个 Compose 页面，
 * 搬到**独立次级页**上（从工具箱点进来，返回就回工具箱，不会再落进设置里）。
 *
 * 目的地只有一个（`tool_page_fragment`），具体开哪一页由参数 [ARG_TOOL] 决定：
 * 模块 / 模块目录 / 模块策略 / 控制台 / 控制台设置 / 实验室功能。
 *
 * 这样「工具箱」和「底栏 Tab」两种入口共用同一份 UI（Composable 没动），
 * 也不会出现「从工具箱点进去却停在设置 Tab」的怪逻辑。
 */
class ToolPageFragment : Fragment() {

    companion object {
        const val ARG_TOOL = "tool"

        const val TOOL_MODULES = "modules"
        const val TOOL_MODULE_CATALOG = "module_catalog"
        const val TOOL_MODULE_POLICY = "module_policy"
        const val TOOL_COMPUT = "comput"
        const val TOOL_COMPUT_SETTINGS = "comput_settings"
        const val TOOL_LAB = "lab"
        const val TOOL_ACCESSIBILITY = "accessibility"

        @JvmStatic
        fun intent(context: Context, tool: String): Intent =
            MainActivity.destinationIntent(
                context,
                R.id.tool_page_fragment,
                bundleOf(ARG_TOOL to tool),
            )

        /** 从任意页面打开某个工具页（不经过设置） */
        @JvmStatic
        fun open(context: Context, tool: String) {
            context.startActivity(intent(context, tool))
        }

        private fun titleOf(tool: String): Int = when (tool) {
            TOOL_MODULES -> R.string.modules_title
            TOOL_MODULE_CATALOG -> R.string.settings_module_catalog
            TOOL_MODULE_POLICY -> R.string.settings_module_policy
            TOOL_COMPUT -> R.string.comput_title
            TOOL_COMPUT_SETTINGS -> R.string.comput_settings
            TOOL_LAB -> R.string.settings_lab
            TOOL_ACCESSIBILITY -> R.string.accessibility_manager
            else -> R.string.toolbox_title
        }
    }

    private var shell: FragmentSubPageBinding? = null

    private val listState = LazyListState()

    /** 模块 ZIP 选择器：必须在初始化阶段注册（Compose 里临时注册会崩） */
    private var pendingModuleZip by mutableStateOf<Uri?>(null)

    private val moduleZipLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingModuleZip = uri }

    /** 实验室功能里的备份 / 恢复（和设置里共用同一份注册逻辑） */
    private val backupLauncher = SettingsBackupHelper.registerBackup(this)
    private val restoreLauncher = SettingsBackupHelper.registerRestore(this)

    private val tool: String get() = arguments?.getString(ARG_TOOL) ?: TOOL_MODULES

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell
        shell.toolbar.title = getString(titleOf(tool))

        val composeView = ComposeView(inflater.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            val palette = remember(style) { resolveHintPalette(requireContext()) }
            val collapsed: (Boolean) -> Unit = { expanded -> shell.appBar.setExpanded(expanded, true) }
            val back: () -> Unit = { findNavController().navigateUp() }

            when (tool) {
                TOOL_MODULES -> moe.shizuku.manager.module.ModulesScreen(
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    onBack = back,
                    onOpenWebUi = { moduleId ->
                        startActivity(
                            moe.shizuku.manager.module.ModuleWebViewActivity.newIntent(
                                requireContext(),
                                moduleId,
                            ),
                        )
                    },
                    onOpenCatalog = { open(TOOL_MODULE_CATALOG) },
                    onOpenPolicy = { open(TOOL_MODULE_POLICY) },
                    showBack = true,
                    pendingZip = pendingModuleZip,
                    onZipConsumed = { pendingModuleZip = null },
                    onPickZip = { moduleZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )

                TOOL_MODULE_CATALOG -> moe.shizuku.manager.module.catalog.ModuleCatalogScreen(
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    onBack = back,
                )

                TOOL_MODULE_POLICY -> ModulePolicyPage(
                    state = remember { SettingsUiState() },
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    go = { back() },
                )

                TOOL_COMPUT -> moe.shizuku.manager.comput.ComputPage(
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    onBack = back,
                    showBack = true,
                    onOpenSettings = { open(TOOL_COMPUT_SETTINGS) },
                )

                TOOL_COMPUT_SETTINGS -> moe.shizuku.manager.comput.ComputSettingsPage(
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    onBack = back,
                )

                TOOL_LAB -> LabFeaturesScreen(
                    onBack = back,
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    onBackup = { backupLauncher.launch("shizako-settings.json") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                )

                // 无障碍管理器：和设置里那个**新版 Compose 页**同一份 UI
                // （以前工具箱这里开的是旧的 Activity 版本，两套界面观感不一致）
                TOOL_ACCESSIBILITY -> moe.shizuku.manager.accessibility.AccessibilityPage(
                    palette = palette,
                    listState = listState,
                    onCollapsedChange = collapsed,
                    onBack = back,
                )
            }
        }
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    /** 同级工具页之间切换：不再叠一层，退回来直接是工具箱 */
    private fun open(anotherTool: String) {
        val context = context ?: return
        startActivity(intent(context, anotherTool))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell?.contentContainer?.removeAllViews()
        shell = null
    }
}
