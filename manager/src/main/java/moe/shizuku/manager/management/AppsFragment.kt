package moe.shizuku.manager.management

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.databinding.FragmentAppsBinding
import moe.shizuku.manager.dhizuku.DhizukuApp
import moe.shizuku.manager.dhizuku.DhizukuAppsScreen
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.dhizuku.loadDhizukuApps
import rikka.lifecycle.Status
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import java.util.Objects

/**
 * KernelSU 风格「应用管理」Tab：大标题折叠栏 + 搜索 + 下拉刷新 + 授权应用列表。
 * 逻辑迁移自原 ApplicationManagementActivity。
 *
 * 服务没在跑（还没激活）时整个列表换成「请先激活」提示 —— 列表里的每一项都要问服务
 * 「这个应用授权了吗」，没有 binder 时问不了，也不该让主人看到一堆空开关。
 */
class AppsFragment : Fragment() {

    private val viewModel by appsViewModel()
    private lateinit var adapter: AppsAdapter

    private var binding: FragmentAppsBinding? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // 服务起来了：自动加载列表
        view?.post {
            updateActivationState()
            viewModel.load()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        view?.post {
            adapter.updateData(emptyList())
            updateActivationState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentAppsBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        adapter = AppsAdapter(requireContext())

        binding.toolbar.title = getString(R.string.home_app_management_title)
        binding.toolbar.inflateMenu(R.menu.menu_apps)
        val searchItem = binding.toolbar.menu.findItem(R.id.menu_search)
        (searchItem?.actionView as? SearchView)?.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    adapter.setQuery(newText)
                    return true
                }
            }
        )

        viewModel.packages.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    binding.swipeRefresh.isRefreshing = false
                    adapter.updateData(it.data)
                }
                Status.ERROR -> {
                    binding.swipeRefresh.isRefreshing = false
                    val tr = it.error
                    Toast.makeText(context, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                    tr?.printStackTrace()
                }
                Status.LOADING -> {
                }
            }
        }
        if (viewModel.packages.value == null && isServiceAvailable()) {
            binding.swipeRefresh.isRefreshing = true
            viewModel.load()
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        // 亚克力悬浮导航遮挡避让：末项可完整滚到胶囊上方
        recyclerView.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.ksu_content_bottom_padding))

        binding.swipeRefresh.setOnRefreshListener {
            if (isServiceAvailable()) {
                viewModel.load()
            } else {
                binding.swipeRefresh.isRefreshing = false
                updateActivationState()
            }
        }

        // 未激活提示里的「去激活」按钮
        binding.activationPromptAction.setOnClickListener {
            it.context.startActivity(
                MainActivity.destinationIntent(it.context, R.id.activation_fragment)
            )
        }

        // 右上角菜单：推荐应用（下载引导页）
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_recommended_apps) {
                findNavController().navigate(
                    R.id.apps_download_fragment,
                    null,
                    moe.shizuku.manager.app.NavMotion.pageOptions(),
                )
                true
            } else {
                false
            }
        }

        // 两页：Shizako 授权应用 / Dhizuku 授权应用
        binding.appsTabs.addTab(
            binding.appsTabs.newTab().setText(R.string.apps_tab_shizako),
        )
        binding.appsTabs.addTab(
            binding.appsTabs.newTab().setText(R.string.apps_tab_dhizuku),
        )
        binding.appsTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                showPage(tab.position == 1)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })

        setupDhizukuPage()

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                if (isServiceAvailable()) viewModel.load(true)
            }
        })

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        updateActivationState()
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        updateActivationState()
        adapter.notifyDataSetChanged()
        // Dhizuku 页：回到前台时刷新（授权可能在别处被改过）
        if (binding?.appsTabs?.selectedTabPosition == 1) refreshDhizuku()
    }

    /** 服务是否可用（未激活时不能问服务要列表，也不该绑定开关状态） */
    private fun isServiceAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 未激活：只显示「请先激活」提示；已激活：显示列表（Dhizuku 页不受影响） */
    private fun updateActivationState() {
        val binding = binding ?: return
        val available = isServiceAvailable()
        val onFirstPage = binding.appsTabs.selectedTabPosition <= 0

        binding.activationPrompt.isVisible = !available && onFirstPage
        binding.swipeRefresh.isVisible = available && onFirstPage
        if (!available || !onFirstPage) {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    // ---------------- 第二页：Dhizuku 授权管理 ----------------

    private var dhizukuApps by mutableStateOf<List<DhizukuApp>>(emptyList())
    private var dhizukuDeviceOwner by mutableStateOf(false)
    private var dhizukuActivating by mutableStateOf(false)
    private val dhizukuListState = LazyListState()

    private fun showPage(dhizuku: Boolean) {
        val binding = binding ?: return
        binding.dhizukuContainer.isVisible = dhizuku
        updateActivationState()
        if (dhizuku) refreshDhizuku()
    }

    private fun setupDhizukuPage() {
        val binding = binding ?: return
        val composeView = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        binding.dhizukuContainer.addView(composeView)
        composeView.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            val palette = androidx.compose.runtime.remember(style) {
                moe.shizuku.manager.ui.hint.resolveHintPalette(requireContext())
            }
            DhizukuAppsScreen(
                palette = palette,
                listState = dhizukuListState,
                deviceOwner = dhizukuDeviceOwner,
                apps = dhizukuApps,
                activating = dhizukuActivating,
                onRevoke = ::revokeDhizuku,
                onRefresh = ::refreshDhizuku,
                onActivate = ::activateDhizuku,
                onViewCommand = ::showDhizukuCommand,
                onCollapsedChange = { expanded -> binding.appBar.setExpanded(expanded, true) },
            )
        }
        refreshDhizuku()
    }

    private fun refreshDhizuku() {
        val context = context ?: return
        dhizukuDeviceOwner = DhizukuSettings.isDeviceOwner(context)
        dhizukuApps = loadDhizukuApps(context.packageManager)
    }

    private fun revokeDhizuku(app: DhizukuApp) {
        val context = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dhizuku_revoke_title)
            .setMessage(getString(R.string.dhizuku_revoke_message, app.label))
            .setPositiveButton(R.string.dhizuku_revoke_confirm) { _, _ ->
                DhizukuSettings.revoke(app.uid)
                refreshDhizuku()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { moe.shizuku.manager.ui.glass.GlassWindow.applyIfGlass(it) }
    }

    /** 激活设备所有者：和激活页同一个命令、同一套反馈 */
    private fun activateDhizuku() {
        val context = context ?: return
        if (dhizukuActivating) return
        dhizukuActivating = true
        Thread {
            val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
            activity?.runOnUiThread {
                dhizukuActivating = false
                refreshDhizuku()
                if (result.success || DhizukuSettings.isDeviceOwner(context)) {
                    Toast.makeText(context, R.string.home_dhizuku_activate_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        getString(
                            R.string.home_dhizuku_activate_failed,
                            result.output.ifEmpty { "unknown error" }.lineSequence().firstOrNull() ?: "",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun showDhizukuCommand() {
        val context = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.activation_method_action_view_command)
            .setMessage(
                rikka.html.text.HtmlCompat.fromHtml(
                    getString(
                        R.string.home_dhizuku_dialog_view_command_message,
                        DhizukuSettings.setDeviceOwnerCommand,
                    ),
                ),
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (rikka.core.util.ClipboardUtils.put(context, DhizukuSettings.setDeviceOwnerCommand)) {
                    Toast.makeText(
                        context,
                        getString(R.string.toast_copied_to_clipboard, DhizukuSettings.setDeviceOwnerCommand),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { moe.shizuku.manager.ui.glass.GlassWindow.applyIfGlass(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        binding = null
    }
}
