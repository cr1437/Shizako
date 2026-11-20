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
import moe.shizuku.manager.adb.AdbPairingState
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterFragment
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.ui.theme.setShizakoContent
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
        composeView.setShizakoContent {
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
                onManualConnect = ::connectManualPort,
                onForgetPairing = ::forgetPairing,
                onOpenDevelopmentSettings = ::openDevelopmentSettings,
                onCopyAdbCommand = ::copyAdbCommand,
                onSendAdbCommand = ::sendAdbCommand,
                onActivateDhizuku = ::activateDhizuku,
                onViewDhizukuCommand = ::showDhizukuCommand,
                onClearDhizukuOwner = ::clearDhizukuOwner,
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
        val preferences = ShizukuSettings.getPreferences()
        val hasKey = hasPairingKey()
        // 服务跑起来了，说明这把密钥确实被设备信任 —— 顺手补一笔配对记录。
        // 旧版本没有这份记录，升级上来的用户靠这一步自动恢复正常显示。
        if (running && hasKey && !AdbPairingState.isPaired(preferences)) {
            AdbPairingState.markPaired(preferences)
        }
        uiState = uiState.copy(
            serviceMode = when {
                !running -> ServiceMode.NOT_RUNNING
                Shizuku.getUid() == 0 -> ServiceMode.ROOT
                else -> ServiceMode.ADB
            },
            dhizukuActive = DhizukuSettings.isActive(context),
            dhizukuDeviceOwner = DhizukuSettings.isDeviceOwner(context),
            dhizukuModeEnabled = DhizukuSettings.isModeEnabled(),
            rooted = EnvironmentUtils.isRooted(),
            paired = hasKey,
            pairingConfirmed = AdbPairingState.isPaired(preferences),
            canAutoAdbStart = ServiceStartHelper.canAdbAutoStart(context),
            adbStarting = adbStarting,
        )
    }

    /**
     * 本地是否已经存了配对用的 adb 私钥。
     *
     * 注意：**有私钥不等于设备认识这把它**。密钥在用户点下配对按钮的那一刻就会生成，
     * 配对失败同样会留下 —— 所以「已配对」的状态显示要用 [AdbPairingState] 的
     * 成功记录来判断，这里只表示「本地有可用于连接的密钥」。
     */
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
                val preferences = ShizukuSettings.getPreferences()
                preferences.edit().remove("adbkey").apply()
                // 配对记录也要清：留着它会让界面继续显示「已配对」，而密钥已经不在了
                AdbPairingState.clear(preferences)
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

    /**
     * 手动指定端口直连。
     *
     * 绕开配对与 mDNS：只要本机某个 ADB TCP 端口上有 adbd 在听，直接连它把服务拉起来。
     * mDNS 被系统或 VPN 拦掉的机器（GrapheneOS 开 VPN、华为/鸿蒙）靠这条兜底 ——
     * 配对的问题解决的是「拿到密钥」，解决不了「发现不了端口」。
     */
    private fun connectManualPort(port: Int) {
        if (port !in 1..65535) return
        findNavController().navigate(
            R.id.starter_fragment,
            bundleOf(
                StarterFragment.EXTRA_IS_ROOT to false,
                StarterFragment.EXTRA_HOST to "127.0.0.1",
                StarterFragment.EXTRA_PORT to port,
            ),
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
        // 设置里把「Dhizuku 模式」关掉之后不允许再激活，否则那个开关只是 UI 假象
        if (!DhizukuSettings.isModeEnabled()) {
            Toast.makeText(context, R.string.dhizuku_mode_off_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (uiState.activatingDhizuku || !Shizuku.pingBinder()) return
        uiState = uiState.copy(activatingDhizuku = true)
        Thread {
            val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
            view?.post {
                uiState = uiState.copy(activatingDhizuku = false)
                if (result.success || DhizukuSettings.isActive(context)) {
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
                        // 展示给用户的是**电脑端**命令（带 adb shell 前缀），
                        // 不能用设备内那条（设备上没有 adb）
                        DhizukuSettings.pcSetDeviceOwnerCommand
                    )
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (ClipboardUtils.put(context, DhizukuSettings.pcSetDeviceOwnerCommand)) {
                    Toast.makeText(
                        context,
                        getString(R.string.toast_copied_to_clipboard, DhizukuSettings.pcSetDeviceOwnerCommand),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    /**
     * 撤销 Device Owner（issue #12）。
     *
     * 只有 Device Owner 自己能清掉自己，走 [DhizukuSettings.clearDeviceOwner]；
     * adb 的 `dpm remove-active-admin` 对正式包（非 testOnly admin）无效。
     */
    private fun clearDhizukuOwner() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dhizuku_clear_owner_title)
            .setMessage(R.string.dhizuku_clear_owner_message)
            .setPositiveButton(R.string.dhizuku_clear_owner_action) { _, _ ->
                doClearDhizukuOwner(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { GlassWindow.applyIfGlass(it) }
    }

    private fun doClearDhizukuOwner(context: android.content.Context) {
        if (uiState.clearingDhizuku) return
        uiState = uiState.copy(clearingDhizuku = true)
        Thread {
            val error = DhizukuSettings.clearDeviceOwner(context)
            view?.post {
                uiState = uiState.copy(clearingDhizuku = false)
                if (error == null) {
                    Toast.makeText(context, R.string.dhizuku_clear_owner_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        getString(R.string.dhizuku_clear_owner_failed, error),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshStatus()
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        shell = null
    }
}
