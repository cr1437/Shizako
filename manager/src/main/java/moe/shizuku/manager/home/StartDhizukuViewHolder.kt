package moe.shizuku.manager.home

import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.activation.ActivationRunner
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStartDhizukuBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.model.ServiceStatus
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import moe.shizuku.manager.MainActivity

/**
 * 首页「设备所有者激活」卡片，与其他启动方式（Root / 无线调试 / 电脑）并列。
 *
 * Dhizuku 是 Shizako 的一种工作模式（与 Shizuku 模式并列）：
 * - Shizuku 模式：server 经 ADB / Root / 无线调试运行
 * - Dhizuku 模式：Shizako 自己是设备所有者，Dhizuku-API 应用直连
 *
 * 卡片三态：
 * 1. Dhizuku 已激活：状态描述 + 授权管理入口
 * 2. Shizuku 模式运行中：一键激活（server 替执行 dpm set-device-owner）
 * 3. 都未激活：查看命令（电脑上执行一次）
 */
class StartDhizukuViewHolder(private val binding: HomeStartDhizukuBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartDhizukuBinding.inflate(inflater, outer.root, true)
            StartDhizukuViewHolder(inner, outer.root)
        }
    }

    private var activating = false

    init {
        binding.button1.setOnClickListener { v -> onButton1(v) }
        binding.button2.setOnClickListener {
            binding.root.context.startActivity(
                MainActivity.destinationIntent(binding.root.context, R.id.dhizuku_fragment)
            )
        }
    }

    private fun onButton1(v: View) {
        val ctx = v.context
        val running = data?.isRunning == true
        if (!activating && running) {
            // Shizuku 模式运行中：一键激活 Dhizuku 模式
            activating = true
            binding.button1.setText(R.string.home_dhizuku_button_activating)
            binding.button1.isEnabled = false
            Thread {
                val result = ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)
                binding.root.post {
                    activating = false
                    if (result.success || DhizukuSettings.isDeviceOwner(ctx)) {
                        Toast.makeText(ctx, R.string.home_dhizuku_activate_success, Toast.LENGTH_SHORT).show()
                        onBind()
                    } else {
                        Toast.makeText(
                            ctx,
                            ctx.getString(
                                R.string.home_dhizuku_activate_failed,
                                result.output.ifEmpty { "unknown error" }.lineSequence().firstOrNull() ?: ""
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        binding.button1.isEnabled = true
                        binding.button1.setText(R.string.home_dhizuku_button_activate)
                    }
                }
            }.start()
            return
        }
        if (activating) return

        // 未运行：显示命令复制对话框
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.home_dhizuku_button_view_command)
            .setMessage(
                HtmlCompat.fromHtml(
                    ctx.getString(
                        R.string.home_dhizuku_dialog_view_command_message,
                        DhizukuSettings.setDeviceOwnerCommand
                    )
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (ClipboardUtils.put(ctx, DhizukuSettings.setDeviceOwnerCommand)) {
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

    override fun onBind() {
        val ctx = binding.root.context
        val active = DhizukuSettings.isDeviceOwner(ctx)
        val running = data?.isRunning == true
        if (active) {
            binding.text1.movementMethod = null
            binding.text1.text = ctx.getString(R.string.home_dhizuku_description_active)
            binding.button1.visibility = View.GONE
            binding.button2.visibility = View.VISIBLE
        } else if (running) {
            // Shizuku 模式运行中：一键激活
            binding.text1.movementMethod = null
            binding.text1.text = ctx.getString(R.string.home_dhizuku_description_ready)
            binding.button1.visibility = View.VISIBLE
            binding.button1.isEnabled = true
            binding.button1.setText(R.string.home_dhizuku_button_activate)
            binding.button2.visibility = View.GONE
        } else {
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text = ctx.getString(R.string.home_dhizuku_description)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            binding.button1.visibility = View.VISIBLE
            binding.button1.isEnabled = true
            binding.button1.setText(R.string.home_dhizuku_button_view_command)
            binding.button2.visibility = View.GONE
        }
    }
}
