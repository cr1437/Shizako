package moe.shizuku.manager.adb

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.platform.ComposeView

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.resolveHintPalette

import rikka.compatibility.DeviceCompatibility


/**
 * ADB 配对教程（Shizuku 同款流程：通知栏输入配对码）。
 *
 * UI 已重构为 Jetpack Compose（[AdbPairingTutorialScreen]），支持
 * **MD3 / 玻璃材质** 两套风格（设置 → 用户界面 → 界面风格切换）。
 * 逻辑保持不变：判断通知是否可用 → 启动 [AdbPairingService]（前台服务 + RemoteInput 通知），
 * 并提供「通知设置」「开发者选项」两个跳转。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialFragment : Fragment() {

    private var shell: FragmentSubPageBinding? = null

    /** Compose 页面状态 */
    private var uiState by mutableStateOf(PairingTutorialState())
    private val listState = LazyListState()

    private var notificationEnabled: Boolean = false

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
            AdbPairingTutorialScreen(
                state = uiState,
                palette = pal,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                onOpenNotificationSettings = ::openNotificationSettings,
                onOpenDeveloperOptions = ::openDeveloperOptions,
            )
        }
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.adb_pairing)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        notificationEnabled = isNotificationEnabled()
        uiState = PairingTutorialState(
            notificationEnabled = notificationEnabled,
            isMiui = DeviceCompatibility.isMiui(),
        )

        if (notificationEnabled) {
            startPairingService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (shell == null) return

        // 从系统通知设置返回后，若已开启通知则自动开始配对
        val newNotificationEnabled = isNotificationEnabled()
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled
            uiState = uiState.copy(notificationEnabled = newNotificationEnabled)
            if (newNotificationEnabled) {
                startPairingService()
            }
        }
    }


    // ---------- 逻辑（与重构前一致） ----------

    private fun isNotificationEnabled(): Boolean {
        val context = requireContext()
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    private fun startPairingService() {
        val context = requireContext()
        val intent = AdbPairingService.startIntent(context)
        try {
            context.startForegroundService(intent)
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = context.getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow(
                        "android:start_foreground",
                        android.os.Process.myUid(),
                        context.packageName,
                        null,
                        null
                    )
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(
                        context,
                        "OP_START_FOREGROUND is denied. What are you doing?",
                        Toast.LENGTH_LONG
                    ).show()
                }
                context.startService(intent)
            }
        }
    }

    private fun openDeveloperOptions() {
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

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
    }
}
