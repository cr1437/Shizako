package moe.shizuku.manager.management

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import moe.shizuku.manager.ui.glass.GlassOptionSlider
import moe.shizuku.manager.ui.hint.HintStyle
import moe.shizuku.manager.ui.hint.resolveHintPalette
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

    /** 顶部两页：0 = Shizako 应用 / 1 = Dhizuku 应用（Compose 滑块驱动） */
    private var selectedPage by mutableStateOf(0)

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

        // 从工具箱 push 进来时（上一级就是工具箱）当次级页用：给返回箭头，
        // 从底栏 Tab 进来时不加（那是顶层页，右上角也没有"上一级"）
        val nav = runCatching { findNavController() }.getOrNull()
        if (nav?.previousBackStackEntry?.destination?.id == R.id.toolbox_fragment) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            binding.toolbar.setNavigationOnClickListener { nav.navigateUp() }
        }
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

        // 两页：Shizako 授权应用 / Dhizuku 授权应用（与设置里「界面风格」同款 Compose 滑块）
        setupSegmentedHeader()

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
        // Dhizuku 页：回到前台时刷新（授权可能在别处被改过）——30 秒内已有数据就不重复查
        if (selectedPage == 1) refreshDhizuku(skipIfFresh = true)
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
        val onFirstPage = selectedPage <= 0

        binding.activationPrompt.isVisible = !available && onFirstPage
        binding.swipeRefresh.isVisible = available && onFirstPage
        if (!available || !onFirstPage) {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /**
     * 顶部两页滑块（Shizako 应用 / Dhizuku 应用）：
     * 照搬设置里「界面风格」那一项 —— 玻璃风格用 GlassOptionSlider（可拖动 + 弹簧吸附），
     * MD3 用 M3 分段按钮；配色走同一份 HintPalette（强调色 + onAccent + variant）。
     */
    private fun setupSegmentedHeader() {
        val binding = binding ?: return

        binding.appsSegmented.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            val palette = remember(style) { resolveHintPalette(requireContext()) }
            val labels = listOf(
                getString(R.string.apps_tab_shizako),
                getString(R.string.apps_tab_dhizuku),
            )
            val onSelected: (Int) -> Unit = { index ->
                if (index != selectedPage) {
                    selectedPage = index
                    showPage(index == 1)
                }
            }

            if (palette.style == HintStyle.GLASS) {
                // 和设置里「界面风格」同款：玻璃滑块，拖动 + 点击 + 弹簧吸附
                GlassOptionSlider(
                    options = labels,
                    selectedIndex = selectedPage.coerceIn(0, labels.lastIndex),
                    onSelected = onSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    thumbColor = palette.accent,
                    thumbContentColor = palette.onAccent,
                    unselectedContentColor = palette.variant,
                    trackColor = Color.White.copy(alpha = 0.10f),
                    edgeColor = Color.White.copy(alpha = 0.38f),
                )
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = index == selectedPage,
                            onClick = { onSelected(index) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = labels.size,
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = palette.accent,
                                activeContentColor = palette.onAccent,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = palette.variant,
                                activeBorderColor = Color.Transparent,
                                inactiveBorderColor = palette.variant.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 第二页：Dhizuku 授权管理 ----------------

    private var dhizukuApps by mutableStateOf<List<DhizukuApp>>(emptyList())
    private var dhizukuDeviceOwner by mutableStateOf(false)
    private var dhizukuActivating by mutableStateOf(false)
    private val dhizukuListState = LazyListState()

    /** 刷新序号：后台加载完成后只有"最新一次"的结果会被采用 */
    private var dhizukuRefreshSeq = 0

    /** 数据缓存时间戳（uptimeMillis）：用于「回前台 30 秒内不重复查询」的缓存新鲜度 */
    private var dhizukuLoadedAt = 0L

    private fun showPage(dhizuku: Boolean) {
        val binding = binding ?: return
        // 和页面转场同一组曲线：新页淡入 + 从右侧轻滑 12dp，旧页反向淡出
        val offset = 12f * resources.displayMetrics.density
        val interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        val inView = if (dhizuku) binding.dhizukuContainer else binding.pageShizako
        val outView = if (dhizuku) binding.pageShizako else binding.dhizukuContainer

        outView.animate().cancel()
        inView.animate().cancel()
        outView.animate()
            .alpha(0f)
            .translationX(-offset)
            .setDuration(160)
            .setInterpolator(interpolator)
            .withEndAction { if (outView.alpha == 0f) outView.visibility = View.GONE }
            .start()
        inView.visibility = View.VISIBLE
        inView.alpha = 0f
        inView.translationX = offset
        inView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(200)
            .setInterpolator(interpolator)
            .start()

        updateActivationState()
        // 【性能】切页不再重复加载：Dhizuku 列表走缓存 ——
        // 打开页时加载一次；只有授权变更 / 回前台（数据过期）才刷新；切页瞬间零查询。
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
                onRefresh = { refreshDhizuku() },
                onActivate = ::activateDhizuku,
                onViewCommand = ::showDhizukuCommand,
                onCollapsedChange = { expanded -> binding.appBar.setExpanded(expanded, true) },
            )
        }
        refreshDhizuku()
    }

    /**
     * 刷新 Dhizuku 授权列表（带缓存）。
     *
     * 【性能】① 查询走 PackageManager 的 IPC，以前在 UI 线程上跑 —— 正好落在切页那一瞬间，
     * 会卡一下；现在整段挪到后台线程，回主线程只做状态赋值（序号防串场）。
     * ② 切页不再重复加载（showPage 里已不调用）；回前台 30 秒内也不重复查，
     * 只有首次打开 / 授权变更 / 用户点「重新加载」时才真正查询。
     * 列表保持"旧数据直到新数据就绪"，过程里不会闪空态。
     */
    private fun refreshDhizuku(skipIfFresh: Boolean = false) {
        val context = context ?: return
        if (skipIfFresh && android.os.SystemClock.uptimeMillis() - dhizukuLoadedAt < 30_000L) return
        val seq = ++dhizukuRefreshSeq
        val appContext = context.applicationContext
        Thread {
            val owner = DhizukuSettings.isDeviceOwner(appContext)
            val apps = loadDhizukuApps(appContext.packageManager)
            activity?.runOnUiThread {
                // 期间又刷新过 / 页面已销毁：丢弃这次结果
                if (seq == dhizukuRefreshSeq && isAdded) {
                    dhizukuLoadedAt = android.os.SystemClock.uptimeMillis()
                    dhizukuDeviceOwner = owner
                    dhizukuApps = apps
                }
            }
        }.start()
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