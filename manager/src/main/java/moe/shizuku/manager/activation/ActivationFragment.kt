package moe.shizuku.manager.activation

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentActivationBinding
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.pairing.FloatingPairService
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterFragment
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.compatibility.DeviceCompatibility
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku

/**
 * 一站式激活详细页：状态总览 + 四种激活方式（Root / 无线调试 / 电脑 ADB / Dhizuku），
 * 每种方式附详细步骤与就地操作。无线调试使用悬浮窗配对（FloatingPairService），
 * 替代旧的通知栏输入方式。
 */
class ActivationFragment : Fragment() {

    private var shell: FragmentSubPageBinding? = null
    private var binding: FragmentActivationBinding? = null

    /** 用户去授予悬浮窗权限后置位，回到本页时自动继续悬浮窗配对 */
    private var pendingFloatingPair = false
    private var activatingDhizuku = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell
        binding = FragmentActivationBinding.inflate(inflater, shell.contentContainer, true)
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        val binding = binding ?: return
        shell.toolbar.title = getString(R.string.activation_page_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.adbCommand.text = Starter.adbCommand

        // Android 11 以下没有无线调试，整张卡片隐藏
        binding.methodWadb.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        binding.wadbMiuiNote.isVisible =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && DeviceCompatibility.isMiui()

        binding.rootStartButton.setOnClickListener { startWithRoot() }
        binding.wadbPairButton.setOnClickListener { launchFloatingPair() }
        binding.wadbSettingsButton.setOnClickListener { openDevelopmentSettings() }
        binding.adbCopyButton.setOnClickListener { copyAdbCommand() }
        binding.adbSendButton.setOnClickListener { sendAdbCommand() }
        binding.dhizukuActivateButton.setOnClickListener { activateDhizuku() }
        binding.dhizukuCommandButton.setOnClickListener { showDhizukuCommand() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 授权悬浮窗权限后返回，自动继续
        if (pendingFloatingPair && FloatingPairService.canShow(requireContext())) {
            pendingFloatingPair = false
            launchFloatingPair()
        }
    }

    private fun refreshStatus() {
        val binding = binding ?: return
        val context = requireContext()
        val running = Shizuku.pingBinder()

        binding.statusServiceValue.text = when {
            !running -> getString(R.string.activation_status_not_running)
            Shizuku.getUid() == 0 -> getString(R.string.activation_status_running_root)
            else -> getString(R.string.activation_status_running_adb)
        }

        val dhizukuActive = DhizukuSettings.isDeviceOwner(context)
        binding.statusDhizukuValue.setText(
            if (dhizukuActive) R.string.activation_status_activated
            else R.string.activation_status_not_activated
        )

        // 已在运行时，所有「启动」类按钮都没有意义
        binding.rootStartButton.isEnabled = !running && EnvironmentUtils.isRooted()
        binding.wadbPairButton.isEnabled = !running
        binding.wadbSettingsButton.isEnabled = !running

        // Dhizuku：已激活隐藏按钮；运行中可一键激活；未运行只能去电脑执行命令
        binding.dhizukuActivateButton.isVisible = !dhizukuActive
        binding.dhizukuActivateButton.isEnabled = running && !activatingDhizuku
        binding.dhizukuActivateButton.setText(
            if (activatingDhizuku) R.string.activation_dhizuku_activating
            else R.string.activation_method_action_activate
        )
    }

    // ---------- Root ----------

    private fun startWithRoot() {
        findNavController().navigate(
            R.id.starter_fragment,
            bundleOf(StarterFragment.EXTRA_IS_ROOT to true)
        )
    }

    // ---------- 无线调试（悬浮窗配对） ----------

    private fun launchFloatingPair() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        if (!FloatingPairService.canShow(context)) {
            // 先引导授予「显示在其他应用上层」权限
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.floating_pair_overlay_dialog_title)
                .setMessage(R.string.floating_pair_overlay_dialog_message)
                .setPositiveButton(R.string.floating_pair_overlay_dialog_grant) { _, _ ->
                    pendingFloatingPair = true
                    startActivity(FloatingPairService.overlayPermissionIntent(context))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        context.startService(FloatingPairService.startIntent(context))
        openDevelopmentSettings()
        Toast.makeText(context, R.string.floating_pair_toast_started, Toast.LENGTH_SHORT).show()
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
        val context = requireContext()
        if (ClipboardUtils.put(context, Starter.adbCommand)) {
            Toast.makeText(
                context,
                getString(R.string.toast_copied_to_clipboard, Starter.adbCommand),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun sendAdbCommand() {
        val context = requireContext()
        var intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
        intent = Intent.createChooser(intent, getString(R.string.activation_adb_action_send))
        context.startActivity(intent)
    }

    // ---------- Dhizuku ----------

    private fun activateDhizuku() {
        val context = requireContext()
        if (activatingDhizuku || !Shizuku.pingBinder()) return
        activatingDhizuku = true
        refreshStatus()
        Thread {
            val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
            view?.post {
                activatingDhizuku = false
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
        val context = requireContext()
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
        binding = null
    }
}
