package moe.shizuku.manager.toolbox

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.settings.LabFeaturesScreen
import moe.shizuku.manager.settings.ModulePolicyPage
import moe.shizuku.manager.settings.SettingsBackupHelper
import moe.shizuku.manager.settings.SettingsUiState
import moe.shizuku.manager.ui.hint.resolveHintPalette

/** 工具箱里的一页：入口列表 + 各个工具。 */
enum class ToolboxPage(val titleRes: Int) {
    MAIN(R.string.toolbox_title),
    MODULES(R.string.modules_title),
    MODULE_CATALOG(R.string.settings_module_catalog),
    MODULE_POLICY(R.string.settings_module_policy),
    COMPUT(R.string.comput_title),
    COMPUT_SETTINGS(R.string.comput_settings),
    ACCESSIBILITY(R.string.accessibility_manager),
    LAB(R.string.settings_lab),
}

/**
 * 工具箱（**底栏 Tab 版**）：一页入口 + 工具页，**两级菜单** —— 和「设置」同一套结构。
 *
 * 关键点：工具页不是 push 出去的独立目的地，而是**同一页里的第二级**，
 * 所以转场用的是设置页那份 `AnimatedContent` 参数（横向 1/4 滑入 + 淡入淡出，240/200ms），
 * 观感和设置里点进二级页完全一致；返回也只回工具箱列表，不会落进设置里。
 *
 * 每个页面各自记滚动位置（[pageListStates]），从工具页返回时列表还停在原处。
 */
class ToolboxFragment : Fragment() {

    private var shell: FragmentSubPageBinding? = null

    private var page by mutableStateOf(ToolboxPage.MAIN)

    /** 大标题当前是不是展开的（去重：状态没变就不再动画，防止滚动中反复 setExpanded） */
    private var appBarExpanded = true

    /** 每页各记滚动位置：进工具页从顶部开始，返回入口列表还在原来的位置 */
    private val pageListStates = mutableMapOf<ToolboxPage, LazyListState>()

    /** 模块 ZIP 选择器（必须在初始化阶段注册） */
    private var pendingModuleZip by mutableStateOf<android.net.Uri?>(null)

    private val moduleZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingModuleZip = uri }

    /** 实验室功能里的备份 / 恢复 */
    private val backupLauncher = SettingsBackupHelper.registerBackup(this)
    private val restoreLauncher = SettingsBackupHelper.registerRestore(this)

    /** 二级页时接管系统返回键：回入口列表，而不是退出 / 退到别处 */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            page = ToolboxPage.MAIN
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = binding
        binding.toolbar.title = getString(page.titleRes)

        val composeView = ComposeView(inflater.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        binding.contentContainer.addView(composeView)
        composeView.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            val palette = remember(style) { resolveHintPalette(requireContext()) }
            val back: () -> Unit = { page = ToolboxPage.MAIN }

            // 和设置页（SettingsScreen）完全一致的转场参数：横向滑入 1/4 + 淡入淡出
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    // 转场参数全应用统一（PageMotion）：设置二级页 / 底栏 Tab / 次级页同一组数字
                    moe.shizuku.manager.ui.motion.PageMotion.slideSpec(
                        targetState.ordinal > initialState.ordinal,
                    )
                },
                label = "toolboxPage",
            ) { target ->
                // 重点（照搬设置页那套）：每个页面用**自己的** LazyListState，而且按 targetState 取。
                // 过渡期间新旧两页同时存在，共用一个 state 会让两个 LazyColumn 抢同一个滚动位置 ——
                // 从工具页退回入口列表时，工具页滚过的 index 落到列表的条目数之外，
                // 列表就「卡住、滑不动」（之前的版本就是这么写错的）。
                val pageListState = pageListStates.getOrPut(target) { LazyListState() }
                // 同一时刻只让当前页驱动大标题折叠，避免两页同时 setExpanded 把 AppBarLayout 顶错位；
                // 【性能】同时做「状态没变就不动画」的去重（和设置页同一处修复）——滚动中反复
                // setExpanded 会让 AppBar 偏移与内容对不上，滚动发涩就来自这类来回拉扯。
                val pageCollapsedChange: (Boolean) -> Unit = { expanded ->
                    if (target == page && expanded != appBarExpanded) {
                        appBarExpanded = expanded
                        shell?.appBar?.setExpanded(expanded, true)
                    }
                }
                // 标题跟着页面走（进入工具页 = 工具名，返回 = 工具箱）
                if (sheetReady) {
                    shell?.toolbar?.title = getString(target.titleRes)
                }
                // 二级页才接管系统返回键：回入口列表，而不是退出
                androidx.compose.runtime.LaunchedEffect(target) {
                    backCallback.isEnabled = target != ToolboxPage.MAIN
                }
                when (target) {
                    ToolboxPage.MAIN -> ToolboxScreen(
                        palette = palette,
                        listState = pageListState,
                        groups = remember(style) { buildGroups() },
                        onCollapsedChange = pageCollapsedChange,
                    )

                    ToolboxPage.MODULES -> moe.shizuku.manager.module.ModulesScreen(
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        onBack = back,
                        onOpenWebUi = { moduleId ->
                            startActivity(
                                moe.shizuku.manager.module.ModuleWebViewActivity.newIntent(
                                    requireContext(),
                                    moduleId,
                                ),
                            )
                        },
                        onOpenCatalog = { page = ToolboxPage.MODULE_CATALOG },
                        onOpenPolicy = { page = ToolboxPage.MODULE_POLICY },
                        showBack = true,
                        pendingZip = pendingModuleZip,
                        onZipConsumed = { pendingModuleZip = null },
                        onPickZip = {
                            moduleZipLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*"),
                            )
                        },
                    )

                    ToolboxPage.MODULE_CATALOG -> moe.shizuku.manager.module.catalog.ModuleCatalogScreen(
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        onBack = back,
                    )

                    ToolboxPage.MODULE_POLICY -> ModulePolicyPage(
                        state = remember { SettingsUiState() },
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        go = { back() },
                    )

                    ToolboxPage.COMPUT -> moe.shizuku.manager.comput.ComputPage(
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        onBack = back,
                        showBack = true,
                        onOpenSettings = { page = ToolboxPage.COMPUT_SETTINGS },
                    )

                    ToolboxPage.COMPUT_SETTINGS -> moe.shizuku.manager.comput.ComputSettingsPage(
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        onBack = back,
                    )

                    ToolboxPage.ACCESSIBILITY -> moe.shizuku.manager.accessibility.AccessibilityPage(
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        onBack = back,
                    )

                    ToolboxPage.LAB -> LabFeaturesScreen(
                        onBack = back,
                        palette = palette,
                        listState = pageListState,
                        onCollapsedChange = pageCollapsedChange,
                        onBackup = { backupLauncher.launch("shizako-settings.json") },
                        onRestore = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                    )
                }
            }
        }
        return binding.root
    }

    /** 首帧之后再改标题：避免 Compose 在第一帧就回调（还没 attach 完） */
    private var sheetReady = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = shell ?: return
        binding.toolbar.setNavigationOnClickListener {
            if (page == ToolboxPage.MAIN) {
                // 顶层 Tab：没有上一级，返回键交给系统
            } else {
                page = ToolboxPage.MAIN
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        sheetReady = true
    }

    // ---------------- 入口表 ----------------

    private fun buildGroups(): List<ToolboxGroup> = listOf(
        ToolboxGroup(
            titleRes = R.string.toolbox_group_modules,
            items = listOf(
                entry(R.drawable.ic_outline_extension_24, R.string.modules_title,
                    R.string.settings_open_modules_summary) { page = ToolboxPage.MODULES },
                entry(R.drawable.ic_outline_apps_24, R.string.settings_module_catalog,
                    R.string.settings_module_catalog_summary) { page = ToolboxPage.MODULE_CATALOG },
                entry(R.drawable.ic_code_24dp, R.string.settings_module_policy,
                    R.string.settings_module_policy_summary) { page = ToolboxPage.MODULE_POLICY },
            ),
        ),
        ToolboxGroup(
            titleRes = R.string.toolbox_group_console,
            items = listOf(
                entry(R.drawable.ic_terminal_24, R.string.comput_title,
                    R.string.settings_open_comput_summary) { page = ToolboxPage.COMPUT },
                entry(R.drawable.ic_outline_settings_24, R.string.comput_settings,
                    R.string.toolbox_summary_comput_settings) { page = ToolboxPage.COMPUT_SETTINGS },
                entry(R.drawable.ic_help_outline_24dp, R.string.home_terminal_title,
                    R.string.toolbox_summary_terminal) { openDestination(R.id.terminal_fragment) },
                entry(R.drawable.ic_outline_assignment_24, R.string.api_log_title,
                    R.string.settings_api_log_summary) { openDestination(R.id.logs_fragment) },
            ),
        ),
        ToolboxGroup(
            titleRes = R.string.toolbox_group_automation,
            items = listOf(
                entry(R.drawable.ic_outline_notifications_active_24, R.string.accessibility_manager,
                    R.string.settings_open_accessibility_summary) { page = ToolboxPage.ACCESSIBILITY },
                entry(R.drawable.ic_outline_info_24, R.string.settings_lab,
                    R.string.settings_lab_summary) { page = ToolboxPage.LAB },
            ),
        ),
        ToolboxGroup(
            titleRes = R.string.toolbox_group_device,
            items = listOf(
                entry(R.drawable.ic_bolt_24dp, R.string.activation_page_title,
                    R.string.toolbox_summary_activation) { openDestination(R.id.activation_fragment) },
                entry(R.drawable.ic_wireless_adb_24dp, R.string.download_page_title,
                    R.string.settings_recommended_apps_summary) { openDestination(R.id.apps_download_fragment) },
                entry(R.drawable.ic_dhizuku_24dp, R.string.activation_method_dhizuku,
                    R.string.toolbox_summary_dhizuku) {
                    // Dhizuku 的授权管理在「被调教的小可爱们」的第二页 —— 同样按次级页 push
                    openDestination(R.id.apps_fragment)
                },
            ),
        ),
        ToolboxGroup(
            titleRes = R.string.toolbox_group_more,
            items = listOf(
                // 这两个本身也是底栏 Tab：从工具箱进来按**次级页 push**（转场和其他入口一致、
                // 返回回工具箱），而不是切 Tab（切 Tab 是 popUpTo+restoreState，没有转场动画，
                // 返回也不会回到工具箱 —— 主人反馈"打开/返回动画和其他不同"就是这个原因）
                entry(R.drawable.ic_outline_apps_24, R.string.home_app_management_title,
                    R.string.toolbox_summary_apps) { openDestination(R.id.apps_fragment) },
                entry(R.drawable.ic_outline_settings_24, R.string.settings_title,
                    R.string.toolbox_summary_settings) { openDestination(R.id.settings_fragment) },
            ),
        ),
    )

    private fun entry(
        iconRes: Int,
        titleRes: Int,
        summaryRes: Int,
        open: () -> Unit,
    ) = ToolboxItem(iconRes, titleRes, summaryRes, open)

    private fun openTab(tab: String) {
        val context = context ?: return
        startActivity(MainActivity.tabIntent(context, tab))
    }

    private fun openDestination(destinationId: Int) {
        val context = context ?: return
        startActivity(MainActivity.destinationIntent(context, destinationId))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell?.contentContainer?.removeAllViews()
        shell = null
        sheetReady = false
    }
}