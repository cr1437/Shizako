package moe.shizuku.manager.home

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.databinding.HomeActivationItemBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.ItemActivationMethodBinding
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.starter.StarterFragment
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * 首页「一站式激活」卡片：服务运行时显示，列出 4 种激活方式，
 * 每种一行（图标 + 名称/描述 + 按钮），就地激活或跳转对应流程。
 *
 * 替代旧的 Brevent/小黑屋/冰箱/Island 一键激活页。
 */
class ActivationViewHolder(private val binding: HomeActivationItemBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeActivationItemBinding.inflate(inflater, outer.root, true)
            ActivationViewHolder(inner, outer.root)
        }
    }

    private val methods = mutableListOf<ItemActivationMethodBinding>()
    private var activatingDhizuku = false

    init {
        buildMethods()
        // 详细的一站式激活页入口
        binding.activationDetailsButton.setOnClickListener { v ->
            v.context.startActivity(
                MainActivity.destinationIntent(v.context, R.id.activation_fragment)
            )
        }
    }

    private fun buildMethods() {
        // Dhizuku 设备所有者
        addMethod(
            icon = R.drawable.ic_dhizuku_24dp,
            titleRes = R.string.activation_method_dhizuku,
            descRes = R.string.activation_method_dhizuku_desc
        ) { activateDhizuku() }
        // Root
        addMethod(
            icon = R.drawable.ic_root_24dp,
            titleRes = R.string.activation_method_root,
            descRes = R.string.activation_method_root_desc
        ) { startRoot() }
        // 无线调试
        addMethod(
            icon = R.drawable.ic_wireless_adb_24dp,
            titleRes = R.string.activation_method_wireless_adb,
            descRes = R.string.activation_method_wireless_adb_desc
        ) { startWirelessAdb() }
        // 电脑 ADB
        addMethod(
            icon = R.drawable.ic_adb_24dp,
            titleRes = R.string.activation_method_adb,
            descRes = R.string.activation_method_adb_desc
        ) { startComputerAdb() }
    }

    private fun addMethod(icon: Int, titleRes: Int, descRes: Int, onClick: () -> Unit) {
        val inflater = LayoutInflater.from(binding.root.context)
        val row = ItemActivationMethodBinding.inflate(inflater, binding.methodsContainer, false)
        row.methodIcon.setImageResource(icon)
        row.methodTitle.setText(titleRes)
        row.methodDesc.setText(descRes)
        row.methodButton.setOnClickListener { onClick() }
        methods.add(row)
        binding.methodsContainer.addView(row.root)
    }

    override fun onBind() {
        val ctx = binding.root.context
        val running = data?.isRunning == true

        // 服务运行时才可用
        binding.activationHubDesc.setText(
            if (running) R.string.activation_hub_desc_running
            else R.string.activation_hub_desc_not_running
        )

        // Dhizuku 行：已激活 / 服务运行中可一键激活 / 未运行显示命令
        val dhizukuRow = methods[0]
        val dhizukuActive = DhizukuSettings.isDeviceOwner(ctx)
        dhizukuRow.methodButton.text = when {
            dhizukuActive -> ctx.getString(R.string.activation_method_status_active)
            running -> ctx.getString(R.string.activation_method_action_activate)
            else -> ctx.getString(R.string.activation_method_action_view_command)
        }
        dhizukuRow.methodButton.isEnabled = !dhizukuActive && !activatingDhizuku
        dhizukuRow.methodButton.visibility = if (dhizukuActive) View.GONE else View.VISIBLE

        // Root 行：仅在设备已 root 且未运行时可用
        val rootRow = methods[1]
        val rootAvailable = EnvironmentUtils.isRooted() && !running
        rootRow.methodButton.text = ctx.getString(R.string.activation_method_action_start)
        rootRow.methodButton.isEnabled = rootAvailable

        // 无线调试行：Android 11+ 且未运行时可用
        val wirelessRow = methods[2]
        val wirelessAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !running
        wirelessRow.methodButton.text = ctx.getString(R.string.activation_method_action_start)
        wirelessRow.methodButton.isEnabled = wirelessAvailable

        // 电脑 ADB 行：未运行时可用（显示命令）
        val adbRow = methods[3]
        adbRow.methodButton.text = ctx.getString(R.string.activation_method_action_view_command)
        adbRow.methodButton.isEnabled = !running
    }

    // ---- 各方式点击处理 ----

    private fun activateDhizuku() {
        val ctx = binding.root.context
        val running = data?.isRunning == true
        if (DhizukuSettings.isDeviceOwner(ctx)) return

        if (running && !activatingDhizuku) {
            // 一站式：服务运行中就地一键激活
            activatingDhizuku = true
            onBind()
            Thread {
                val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
                binding.root.post {
                    activatingDhizuku = false
                    if (result.success || DhizukuSettings.isDeviceOwner(ctx)) {
                        Toast.makeText(ctx, R.string.home_dhizuku_activate_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            ctx,
                            ctx.getString(
                                R.string.home_dhizuku_activate_failed,
                                result.output.ifEmpty { "unknown error" }.lineSequence().firstOrNull() ?: ""
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onBind()
                }
            }.start()
            return
        }

        // 未运行：显示命令复制对话框
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.home_dhizuku_button_view_command)
            .setMessage(
                rikka.html.text.HtmlCompat.fromHtml(
                    ctx.getString(
                        R.string.home_dhizuku_dialog_view_command_message,
                        DhizukuSettings.setDeviceOwnerCommand
                    )
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (rikka.core.util.ClipboardUtils.put(ctx, DhizukuSettings.setDeviceOwnerCommand)) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.toast_copied_to_clipboard, DhizukuSettings.setDeviceOwnerCommand),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startRoot() {
        val ctx = binding.root.context
        ctx.startActivity(
            MainActivity.destinationIntent(
                ctx,
                R.id.starter_fragment,
                Bundle().apply { putBoolean(StarterFragment.EXTRA_IS_ROOT, true) }
            )
        )
    }

    private fun startWirelessAdb() {
        // 无线调试的完整流程（悬浮窗配对 + 分步教程）在一站式激活详细页中
        val ctx = binding.root.context
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        ctx.startActivity(MainActivity.destinationIntent(ctx, R.id.activation_fragment))
    }

    private fun startComputerAdb() {
        val ctx = binding.root.context
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.home_adb_button_view_command)
            .setMessage(
                rikka.html.text.HtmlCompat.fromHtml(
                    ctx.getString(R.string.home_adb_dialog_view_command_message, moe.shizuku.manager.starter.Starter.adbCommand)
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (rikka.core.util.ClipboardUtils.put(ctx, moe.shizuku.manager.starter.Starter.adbCommand)) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.toast_copied_to_clipboard, moe.shizuku.manager.starter.Starter.adbCommand),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
