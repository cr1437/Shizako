package moe.shizuku.manager.home

import android.content.DialogInterface
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.ui.glass.GlassWindow
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.AboutDialogBinding
import moe.shizuku.manager.databinding.FragmentHomeBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.utils.AppIconCache
import rikka.core.ktx.unsafeLazy
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku

/**
 * KernelSU 风格首页：大标题折叠栏 + 服务状态大色卡 + 激活方式卡片。
 * 逻辑迁移自原 HomeActivity（卡片内容沿用 HomeAdapter）。
 */
class HomeFragment : Fragment() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus()
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkServerStatus()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel) }

    private var binding: FragmentHomeBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentHomeBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return

        binding.toolbar.title = getString(R.string.app_name)
        // 右上角三个点去掉了：停止服务挪到首页状态卡上的按钮，关于在设置里就有

        homeModel.serviceStatus.observe(viewLifecycleOwner) {
            if (it.status == Status.SUCCESS) {
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                adapter.updateData()
                ShizukuSettings.setLastLaunchMode(
                    if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT
                    else ShizukuSettings.LaunchMethod.ADB
                )
            }
        }
        appsModel.grantedCount.observe(viewLifecycleOwner) {
            if (it.status == Status.SUCCESS) {
                adapter.updateData()
            }
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        // 列表项增删/移动的动画时长收紧到 200ms，默认 250ms 在慢机上显得拖
        recyclerView.itemAnimator?.apply {
            addDuration = 200L
            removeDuration = 160L
            moveDuration = 200L
            changeDuration = 200L
        }
        recyclerView.addItemSpacing(top = 4f, bottom = 4f, unit = TypedValue.COMPLEX_UNIT_DIP)
        recyclerView.addEdgeSpacing(
            top = 4f, bottom = 4f, left = 16f, right = 16f,
            unit = TypedValue.COMPLEX_UNIT_DIP
        )
        // 亚克力悬浮导航遮挡避让：末项可完整滚到胶囊上方
        recyclerView.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.ksu_content_bottom_padding))

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        binding = null
    }

    private fun showAboutDialog() {
        val context = context ?: return
        val binding = AboutDialogBinding.inflate(LayoutInflater.from(context), null, false)
        binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()
        binding.sourceCode.text = getString(
            R.string.about_based_on_text,
            "<b><a href=\"https://github.com/RikkaApps/Shizuku\">Shizuku</a></b>"
        ).toHtml()
        binding.icon.setImageBitmap(
            AppIconCache.getOrLoadBitmap(
                context,
                context.applicationInfo,
                Process.myUid() / 100000,
                resources.getDimensionPixelOffset(R.dimen.default_app_icon_size)
            )
        )
        binding.appName.text = getString(R.string.app_name)
        binding.versionName.text =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .show()
                .also { GlassWindow.applyIfGlass(it) }
    }

    private fun showStopDialog() {
        val context = context ?: return
        if (!Shizuku.pingBinder()) {
            return
        }
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.dialog_stop_message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                try {
                    Shizuku.exit()
                } catch (e: Throwable) {
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
                .also { GlassWindow.applyIfGlass(it) }
    }
}