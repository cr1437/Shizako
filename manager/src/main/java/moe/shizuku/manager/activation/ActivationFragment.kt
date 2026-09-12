package moe.shizuku.manager.activation

import moe.shizuku.manager.ui.glass.GlassWindow
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterFragment
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.compatibility.DeviceCompatibility
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku

/**
 * 一站式激活详细页：状态总览 + 四种激活方式（Root / 无线调试 / 电脑 ADB / Dhizuku），
 * 每种方式附详细步骤与就地操作。无线调试走 Shizuku 同款流程：配对码在通知栏输入
 * （AdbPairingService + RemoteInput）。
 *
 * UI 为 Jetpack Compose（[ActivationScreen]），卡片/按钮走提示页公共组件，
 * 支持 **MD3 / 玻璃材质** 双风格（设置 → 用户界面 → 界面风格）。
 * Fragment 只负责取状态、解析主题配色、承接操作。
 */
class ActivationFragment : Fragment() {

    private var shell: FragmentSubPageBinding? = null

    /** Compose 页面状态：Fragment 单向下发，Compose 只读 */
    private var uiState by mutableStateOf(ActivationUiState())
    private val listState = LazyListState()

    /** 正在用已配对信息启动服务（按钮转「正在启动…」） */
    private var adbStarting = false

    /** 服务起来/挂掉时自动刷新状态：配对成功后自动启动也靠它把页面刷新出来 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        adbStarting = false
        view?.post { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        view?.post { refreshStatus() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell

        val composeView = ComposeView(inflater.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            // 风格切换直接按当前风格组合（不做交叉淡入 —— 动画统一交给官方 MD3 那套）
            val pal = androidx.compose.runtime.remember(style) { resolveHintPalette(requireContext()) }
            ActivationScreen(
                state = uiState,
                palette = pal,
                listState = listState,
                onStartRoot = ::startWithRoot,
                onStartPairing = ::startWirelessPairing,
                onStartAdbPaired = ::startAdbWithPairing,
                onForgetPairing = ::forgetPairing,
                onOpenDevelopmentSettings = ::openDevelopmentSettings,
                onCopyAdbCommand = ::copyAdbCommand,
                onSendAdbCommand = ::sendAdbCommand,
                onActivateDhizuku = ::activateDhizuku,
                onViewDhizukuCommand = ::showDhizukuCommand,
                onOpenTerminal = ::openTerminal,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
            )
        }
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.activation_page_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        uiState = uiState.copy(
            supportsWirelessAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            isMiui = DeviceCompatibility.isMiui(),
            rooted = EnvironmentUtils.isRooted(),
            adbCommand = Starter.adbCommand,
        )

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val context = context ?: return
        val running = Shizuku.pingBinder()
        uiState = uiState.copy(
            serviceMode = when {
                !running -> ServiceMode.NOT_RUNNING
                Shizuku.getUid() == 0 -> ServiceMode.ROOT
                else -> ServiceMode.ADB
            },
            dhizukuActive = DhizukuSettings.isDeviceOwner(context),
            rooted = EnvironmentUtils.isRooted(),
            paired = hasPairingKey(),
            canAutoAdbStart = ServiceStartHelper.canAdbAutoStart(context),
            adbStarting = adbStarting,
        )
    }

    /** 本地是否已经存了配对用的 adb 私钥（配对过一次就一直有） */
    private fun hasPairingKey(): Boolean =
        PreferenceAdbKeyStore(ShizukuSettings.getPreferences()).get() != null

    // ---------- 已配对：直接用无线调试启动 ----------

    /**
     * 「激活」= 跑启动脚本：用已经配对好的 adb 私钥连一次无线调试，
     * 把 [Starter.internalCommand] 跑起来（不用再配对，重启后也能直接用）。
     */
    private fun startAdbWithPairing() {
        val context = context ?: return
        if (Shizuku.pingBinder()) {
            Toast.makeText(context, R.string.activation_status_running_adb, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasPairingKey()) {
            Toast.makeText(context, R.string.activation_wadb_action_pair, Toast.LENGTH_SHORT).show()
            startWirelessPairing()
            return
        }

        adbStarting = true
        refreshStatus()
        Toast.makeText(context, R.string.activation_toast_starting, Toast.LENGTH_SHORT).show()

        ServiceStartHelper.startAdb(context) {
            view?.post {
                adbStarting = false
                refreshStatus()
            }
        }
    }

    /** 忘记配对信息：把本地 adb 私钥清掉，下次要重新配对 */
    private fun forgetPairing() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.activation_quick_start_action_forget)
            .setMessage(R.string.activation_quick_start_forget_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ShizukuSettings.getPreferences().edit().remove("adbkey").apply()
                Toast.makeText(context, R.string.activation_toast_pairing_forgotten, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    // ---------- Root ----------

    private fun startWithRoot() {
        findNavController().navigate(
            R.id.starter_fragment,
            bundleOf(StarterFragment.EXTRA_IS_ROOT to true),
            moe.shizuku.manager.app.NavMotion.pageOptions(),
        )
    }

    // ---------- 无线调试（Shizuku 同款：通知栏输入配对码） ----------

    /**
     * 进入 ADB 配对教程页 —— 与 Shizuku 一致的做法：
     * 教程页检查通知权限 → 启动 AdbPairingService（前台服务 + 带 RemoteInput 的通知）
     * → 用户在系统「使用配对码配对设备」对话框看到配对码后，下拉通知栏直接输入，
     * 配对成功后自动连接并启动服务。
     */
    private fun startWirelessPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        findNavController().navigate(R.id.adb_pairing_fragment, null, moe.shizuku.manager.app.NavMotion.pageOptions())
    }

    // ---------- 终端 ----------

    /** 终端入口：原来挂在首页的「终端」卡片上，现挪到本页。 */
    private fun openTerminal() {
        if (!uiState.serviceRunning) return
        findNavController().navigate(R.id.terminal_fragment, null, moe.shizuku.manager.app.NavMotion.pageOptions())
    }

    private fun openDevelopmentSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 部分厂商 ROM（ColorOS/MIUI/OriginOS）会抛 SecurityException
            e.printStackTrace()
        }
    }

    // ---------- 电脑 ADB ----------

    private fun copyAdbCommand() {
        val context = context ?: return
        if (ClipboardUtils.put(context, Starter.adbCommand)) {
            Toast.makeText(
                context,
                getString(R.string.toast_copied_to_clipboard, Starter.adbCommand),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun sendAdbCommand() {
        val context = context ?: return
        var intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
        intent = Intent.createChooser(intent, getString(R.string.activation_adb_action_send))
        context.startActivity(intent)
    }

    // ---------- Dhizuku ----------

    private fun activateDhizuku() {
        val context = context ?: return
        if (uiState.activatingDhizuku || !Shizuku.pingBinder()) return
        uiState = uiState.copy(activatingDhizuku = true)
        Thread {
            val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
            view?.post {
                uiState = uiState.copy(activatingDhizuku = false)
                if (result.success || DhizukuSettings.isDeviceOwner(context)) {
                    Toast.makeText(context, R.string.home_dhizuku_activate_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        getString(
                            R.string.home_dhizuku_activate_failed,
                            result.output.ifEmpty { "unknown error" }.lineSequence().firstOrNull() ?: ""
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshStatus()
            }
        }.start()
    }

    private fun showDhizukuCommand() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.activation_method_action_view_command)
            .setMessage(
                HtmlCompat.fromHtml(
                    getString(
                        R.string.home_dhizuku_dialog_view_command_message,
                        DhizukuSettings.setDeviceOwnerCommand
                    )
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (ClipboardUtils.put(context, DhizukuSettings.setDeviceOwnerCommand)) {
                    Toast.makeText(
                        context,
                        getString(R.string.toast_copied_to_clipboard, DhizukuSettings.setDeviceOwnerCommand),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        shell = null
    }
}
