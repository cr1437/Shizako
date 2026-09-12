package moe.shizuku.manager.home

import moe.shizuku.manager.ui.glass.GlassWindow
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.*
import moe.shizuku.manager.databinding.AdbPairDialogBinding
import rikka.lifecycle.viewModels
import java.net.ConnectException

@RequiresApi(VERSION_CODES.R)
class AdbPairDialogFragment : DialogFragment() {

    private lateinit var binding: AdbPairDialogBinding

    private val viewModel by viewModels { ViewModel(requireContext()) }

    /** 已经自动提交过的配对码：避免输满 6 位后被重复提交 */
    private var lastSubmittedCode: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbPairDialogBinding.inflate(LayoutInflater.from(context))

        val builder = MaterialAlertDialogBuilder(context).apply {
            setTitle(R.string.dialog_adb_pairing_title)
            setView(binding.root)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok, null)
            setNeutralButton(R.string.development_settings, null)
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // 玻璃材质风格：对话框套 Android 12 窗口模糊（背景模糊 + 模糊后方屏幕）
        GlassWindow.applyIfGlass(dialog)
    }

    private fun onDialogShow(dialog: AlertDialog) {
        binding.pairingCode.editText!!.doAfterTextChanged {
            binding.pairingCode.error = null
            autoSubmitIfComplete()
        }

        binding.pairingCode.error = null

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = false

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            try {
                it.context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            submitPairing()
        }

        viewModel.port.observe(this) {
            if (it == -1) {
                dialog.setTitle(R.string.dialog_adb_pairing_discovery)
                binding.text1.isVisible = true
                binding.pairingCode.isVisible = false
                binding.port.editText!!.setText(it.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible = true
            } else {
                dialog.setTitle(R.string.dialog_adb_pairing_title)
                binding.text1.isVisible = false
                binding.pairingCode.isVisible = true
                binding.port.editText!!.setText(it.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = true
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible = false
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val context = requireContext()
        val inMultiScreenOrDisplay = (requireActivity().isInMultiWindowMode
                || (requireActivity().window?.decorView?.display?.displayId ?: -1) > 0)

        binding.text1.isVisible = inMultiScreenOrDisplay
        binding.text2.isVisible = !inMultiScreenOrDisplay

        if (inMultiScreenOrDisplay) {
            dialog?.setTitle(R.string.dialog_adb_pairing_discovery)
        } else {
            dialog?.setTitle(R.string.dialog_adb_pairing_title)
        }

        viewModel.result.observe(this) {
            if (it == null) {
                // 配对成功：先关对话框，再自动跳转「一站式激活」页
                val ctx = context
                dismissAllowingStateLoss()
                if (ctx != null) openActivationPage(ctx)
            } else {
                when (it) {
                    is ConnectException -> {
                        binding.port.error = context.getString(R.string.cannot_connect_port)
                    }
                    is AdbInvalidPairingCodeException -> {
                        binding.pairingCode.error = context.getString(R.string.paring_code_is_wrong)
                        // 允许改完重输后再自动提交
                        lastSubmittedCode = null
                    }
                    is AdbKeyException -> {
                        Toast.makeText(context, context.getString(R.string.adb_error_key_store), Toast.LENGTH_LONG)
                            .apply { setGravity(Gravity.CENTER, 0, 0) }.show()
                    }
                }
            }
        }
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }

    /** 点「确定」或自动提交：端口非法就提示，其他情况直接开始配对 */
    private fun submitPairing() {
        val port = try {
            binding.port.editText!!.text.toString().toInt()
        } catch (e: Exception) {
            -1
        }
        if (port > 65535 || port < 1) {
            binding.port.isVisible = true
            binding.port.error = requireContext().getString(R.string.dialog_adb_invalid_port)
            return
        }

        val password = binding.pairingCode.editText!!.text.toString()
        lastSubmittedCode = password
        viewModel.run(port, password)
    }

    /**
     * 配对码输满 6 位（且端口已被 mDNS 发现）就自动开始配对 —— 不用再点「确定」，
     * 成功后 [openActivationPage] 自动跳到「一站式激活」页。
     */
    private fun autoSubmitIfComplete() {
        if (isStateSaved) return
        val code = binding.pairingCode.editText!!.text.toString().trim()
        if (code.length < 6 || code == lastSubmittedCode) return

        val port = try {
            binding.port.editText!!.text.toString().toInt()
        } catch (e: Exception) {
            -1
        }
        if (port !in 1..65535) return

        submitPairing()
    }

    /** 配对成功 → 自动跳转「一站式激活」页（应用不在前台时顺便切到前台） */
    private fun openActivationPage(context: Context) {
        runCatching {
            context.startActivity(
                MainActivity.destinationIntent(context, R.id.activation_fragment)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { it.printStackTrace() }
    }

    override fun getDialog(): AlertDialog? {
        return super.getDialog() as AlertDialog?
    }
}

@SuppressLint("NewApi")
private class ViewModel(context: Context) : androidx.lifecycle.ViewModel() {

    private val _result = MutableLiveData<Throwable?>()
    val result = _result as LiveData<Throwable?>

    private val _port = MutableLiveData<Int>()
    val port = _port as LiveData<Int>

    private val adbMdns: AdbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING) {
        _port.postValue(it)
    }

    init {
        adbMdns.start()
    }

    fun run(port: Int, password: String) {
        GlobalScope.launch(Dispatchers.IO) {
            val host = "127.0.0.1"

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                _result.postValue(AdbKeyException(e))
                return@launch
            }

            AdbPairingClient(host, port, password, key).runCatching {
                start()
            }.onFailure {
                _result.postValue(it)
                it.printStackTrace()
            }.onSuccess {
                if (it) {
                    _result.postValue(null)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        adbMdns.stop()
    }
}
