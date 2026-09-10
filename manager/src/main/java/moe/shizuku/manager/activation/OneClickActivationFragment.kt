package moe.shizuku.manager.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentActivationBinding
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.update.DownloadProgressDialog
import moe.shizuku.manager.update.UpdateChecker
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一键激活页（已迁入 Navigation，原 OneClickActivationActivity）。逻辑不变。
 */
class OneClickActivationFragment : Fragment() {

    private class TargetRow(
        val target: ActivationTarget,
        val view: View,
        val button: Button,
        val statusView: TextView
    ) {
        @Volatile
        var installed: Boolean = true

        @Volatile
        var activated: Boolean? = null
    }

    private enum class OutputKind { PLAIN, COMMAND, SUCCESS, ERROR }

    private val running = AtomicBoolean(false)
    private val statusChecking = AtomicBoolean(false)
    private val rows = mutableListOf<TargetRow>()

    /** The button that started the running command, or null. */
    private var activeButton: Button? = null

    private var shell: FragmentSubPageBinding? = null

    private lateinit var scrollView: NestedScrollView
    private lateinit var targetsContainer: LinearLayout
    private lateinit var outputView: TextView
    private lateinit var customInput: EditText
    private lateinit var customRun: Button
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var runningIndicator: View

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUi { onServiceStatusChanged(true) }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUi { onServiceStatusChanged(false) }
    }

    /** Activity.runOnUiThread 的 Fragment 等价物：view.post 可从任意线程安全调用。 */
    private fun runOnUi(action: () -> Unit) {
        val v = view
        if (v != null) {
            v.post(action)
        } else {
            activity?.runOnUiThread(action)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell
        val content = FragmentActivationBinding.inflate(inflater, shell.contentContainer, true)

        scrollView = content.scroll
        targetsContainer = content.targetsContainer
        outputView = content.outputView
        customInput = content.customCommand
        customRun = content.customRun
        statusDot = content.statusDot
        statusText = content.statusText
        runningIndicator = content.runningIndicator

        customRun.setOnClickListener {
            val command = customInput.text?.toString()?.trim().orEmpty()
            if (command.isEmpty()) {
                Toast.makeText(context, R.string.activation_custom_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runCommand(getString(R.string.activation_custom_title), command, null, customRun)
        }
        content.outputCopy.setOnClickListener { copyOutput() }

        buildRows()

        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.activation_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        // Live service status: sticky listener fires immediately when the
        // service is already running, then on every restart/stop.
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        refreshTargetStatuses()
    }

    override fun onResume() {
        super.onResume()
        if (!::outputView.isInitialized) return
        onServiceStatusChanged(Shizuku.pingBinder())
        // 安装完成后回来刷新 installed 状态
        refreshTargetStatuses()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        shell = null
    }

    private fun onServiceStatusChanged(serviceRunning: Boolean) {
        val context = context ?: return
        statusDot.background?.setTint(
            context.getColor(if (serviceRunning) R.color.activation_status_active else R.color.activation_status_inactive)
        )
        statusText.text = getString(
            if (serviceRunning) R.string.activation_service_running
            else R.string.activation_service_not_running
        )
        refreshButtonsEnabled()
        if (serviceRunning) {
            refreshTargetStatuses()
        }
    }

    private fun buildRows() {
        val inflater = LayoutInflater.from(requireContext())
        for (target in ActivationTargets.ALL) {
            val view = inflater.inflate(R.layout.item_activation_target, targetsContainer, false)
            view.findViewById<TextView>(R.id.target_label).text = target.label
            view.findViewById<TextView>(R.id.target_notes).text = when (target.notesRes) {
                ActivationTarget.NOTE_BREVENT -> getString(R.string.activation_note_brevent)
                else -> getString(R.string.activation_note_device_owner)
            }
            val button = view.findViewById<Button>(R.id.target_activate)
            button.setText(R.string.activation_activate)
            val row = TargetRow(
                target, view, button,
                view.findViewById(R.id.target_status)
            )
            button.setOnClickListener {
                if (row.installed) {
                    runCommand(target.label, target.command, target, button)
                } else {
                    download(target)
                }
            }
            rows.add(row)
            targetsContainer.addView(view)
        }
    }

    /**
     * Refreshes the per-target status (installed / activated). The local
     * package check is instant but can be filtered by package visibility on
     * Android 11+, so the server-side check refines it when possible.
     */
    private fun refreshTargetStatuses() {
        for (row in rows) {
            row.installed = isLocallyInstalled(row.target.packageName)
            updateRowStatus(row)
        }
        refreshButtonsEnabled()

        if (!Shizuku.pingBinder()) return
        if (!statusChecking.compareAndSet(false, true)) return

        Thread {
            for (row in rows) {
                val target = row.target
                try {
                    if (!row.installed) {
                        row.installed = ActivationRunner.isPackageInstalled(target.packageName)
                    }
                    if (row.installed && target.detection == ActivationTarget.Detection.DEVICE_OWNER) {
                        row.activated = ActivationRunner.isDeviceOwner(target.packageName)
                    }
                } catch (e: Throwable) {
                    // keep whatever was resolved so far
                }
            }
            runOnUi {
                statusChecking.set(false)
                for (row in rows) updateRowStatus(row)
                refreshButtonsEnabled()
            }
        }.start()
    }

    private fun isLocallyInstalled(packageName: String): Boolean = try {
        requireContext().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun updateRowStatus(row: TargetRow) {
        val context = context ?: return
        val res = when {
            !row.installed -> R.string.activation_status_not_installed
            row.activated == true -> R.string.activation_status_activated
            row.activated == false -> R.string.activation_status_not_activated
            else -> R.string.activation_status_installed
        }
        // The translated strings are "<app> · <status>"; strip the app name
        // prefix so only the status shows on the status line.
        val full = getString(res, row.target.label)
        val status = full.removePrefix(row.target.label)
            .trim(' ', '·', '・', '-', '—', ':')

        val text = SpannableString(status)
        val colorRes = when {
            !row.installed -> R.color.activation_status_inactive
            row.activated == true -> R.color.activation_status_active
            else -> 0
        }
        if (colorRes != 0) {
            text.setSpan(
                ForegroundColorSpan(context.getColor(colorRes)),
                0, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        row.statusView.text = text
    }

    private fun runCommand(label: String, command: String, target: ActivationTarget?, clickedButton: Button) {
        val context = context ?: return
        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, R.string.activation_service_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        if (!running.compareAndSet(false, true)) return

        activeButton = clickedButton
        refreshButtonsEnabled()
        runningIndicator.visibility = View.VISIBLE
        appendOutput(label, OutputKind.PLAIN)
        appendOutput("\$ $command", OutputKind.COMMAND)

        Thread {
            val result = ActivationRunner.run(command)
            runOnUi {
                running.set(false)
                appendOutput(result.output.ifEmpty { getString(R.string.activation_output_empty) })
                appendOutput("")

                when {
                    result.error != null -> {
                        appendOutput("[error] ${result.error.message}", OutputKind.ERROR)
                        Toast.makeText(context, R.string.activation_failed, Toast.LENGTH_SHORT).show()
                    }
                    result.timedOut -> {
                        Toast.makeText(context, R.string.activation_timeout, Toast.LENGTH_LONG).show()
                    }
                    result.success -> {
                        appendOutput("✓ ${getString(R.string.activation_success)}", OutputKind.SUCCESS)
                        Toast.makeText(context, R.string.activation_success, Toast.LENGTH_SHORT).show()
                        // The command may have just changed the device owner
                        // state, re-check the target statuses.
                        refreshTargetStatuses()
                    }
                    else -> {
                        appendOutput("[exit ${result.exitCode}]", OutputKind.ERROR)
                        // 常见失败原因给出友好提示，原文仍留在输出区
                        val hintRes = when {
                            result.output.contains("several users") ->
                                R.string.activation_error_multiple_users
                            result.output.contains("several accounts") ->
                                R.string.activation_error_multiple_accounts
                            else -> 0
                        }
                        if (hintRes != 0) {
                            Toast.makeText(context, hintRes, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                context,
                                getString(R.string.activation_failed_with_code, result.exitCode),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                activeButton = null
                runningIndicator.visibility = View.GONE
                refreshButtonsEnabled()
            }
        }.start()
    }

    private fun refreshButtonsEnabled() {
        val busy = running.get()
        val serviceRunning = Shizuku.pingBinder()
        for (row in rows) {
            row.button.setText(
                when {
                    busy && row.button === activeButton -> R.string.activation_running
                    !row.installed -> R.string.activation_download
                    else -> R.string.activation_activate
                }
            )
            row.button.isEnabled = when {
                busy -> false
                !row.installed -> row.target.downloadUrl != null
                else -> serviceRunning
            }
        }
        customRun.setText(
            if (busy && customRun === activeButton) R.string.activation_running
            else R.string.activation_run
        )
        customRun.isEnabled = !busy && serviceRunning
    }

    // ---- 未安装应用的应用内下载（更新器同款：进度条对话框） ----

    /** 未安装时点按钮：直链 APK 走更新器同款下载器（进度条对话框），网页地址走内置浏览器。 */
    private fun download(target: ActivationTarget) {
        val context = context ?: return
        val url = target.downloadUrl ?: return
        if (url.endsWith(".apk", ignoreCase = true)) {
            val fileName = "${target.packageName}.apk"
            val dialog = DownloadProgressDialog.show(
                context,
                getString(R.string.activation_downloading),
                target.label,
                DownloadProgressDialog.OnCancelListener { UpdateChecker.cancelDownload() }
            )
            UpdateChecker.downloadFromUrl(context, url, fileName, object : UpdateChecker.DownloadListener {
                override fun onProgress(downloaded: Long, total: Long, speedBps: Long) {
                    if (downloaded <= 0) {
                        dialog.setState(getString(R.string.activation_downloading))
                    } else {
                        dialog.update(downloaded, total, speedBps)
                    }
                }

                override fun onRetry(attempt: Int, max: Int) {
                    dialog.setState(getString(R.string.update_retrying, attempt, max))
                }

                override fun onComplete(apkFile: java.io.File) {
                    dialog.dismiss()
                    installApk(apkFile)
                }

                override fun onFailed(reason: String) {
                    dialog.dismiss()
                    Toast.makeText(
                        context,
                        getString(R.string.activation_download_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onCancelled() {
                    dialog.dismiss()
                }
            })
        } else {
            CustomTabsHelper.launchUrl(context, Uri.parse(url))
        }
    }

    /** 用系统安装器打开已下载的 APK（FileProvider 授权）。 */
    private fun installApk(file: java.io.File) {
        val context = context ?: return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update_file_provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun appendOutput(text: String, kind: OutputKind = OutputKind.PLAIN) {
        if (outputView.text.toString() == getString(R.string.activation_output_empty)) {
            outputView.text = ""
        }
        if (text.isEmpty()) {
            outputView.append("\n")
            return
        }

        val line = SpannableString(text + "\n")
        val color = when (kind) {
            OutputKind.COMMAND -> resolveThemeColor(android.R.attr.textColorSecondary)
            OutputKind.SUCCESS -> requireContext().getColor(R.color.activation_output_success)
            OutputKind.ERROR -> requireContext().getColor(R.color.activation_output_error)
            OutputKind.PLAIN -> 0
        }
        if (color != 0) {
            line.setSpan(
                ForegroundColorSpan(color),
                0, line.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        outputView.append(line)

        // Keep the newest output visible like a terminal would.
        scrollView.post {
            scrollView.smoothScrollTo(0, scrollView.getChildAt(0).bottom)
        }
    }

    private fun copyOutput() {
        val text = outputView.text.toString()
        if (text.isEmpty()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("activation", text))
        Toast.makeText(context, R.string.activation_copied, Toast.LENGTH_SHORT).show()
    }
}
