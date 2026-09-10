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
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentAdbPairingTutorialBinding
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import rikka.compatibility.DeviceCompatibility

/**
 * ADB 配对教程（已迁入 Navigation，原 AdbPairingTutorialActivity）。逻辑不变。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialFragment : Fragment() {

    private var shell: FragmentSubPageBinding? = null
    private lateinit var binding: FragmentAdbPairingTutorialBinding

    private var notificationEnabled: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell
        binding = FragmentAdbPairingTutorialBinding.inflate(inflater, shell.contentContainer, true)
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.adb_pairing)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val context = requireContext()

        notificationEnabled = isNotificationEnabled()

        if (notificationEnabled) {
            startPairingService()
        }

        binding.apply {
            syncNotificationEnabled()

            if (DeviceCompatibility.isMiui()) {
                miui.isVisible = true
            }

            developerOptions.setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Some vendor ROMs (ColorOS/MIUI/OriginOS) throw SecurityException
                    // because their settings components require vendor permissions.
                    e.printStackTrace()
                }
            }

            notificationOptions.setOnClickListener {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun syncNotificationEnabled() {
        binding.apply {
            step1.isVisible = notificationEnabled
            step2.isVisible = notificationEnabled
            step3.isVisible = notificationEnabled
            network.isVisible = notificationEnabled
            notification.isVisible = notificationEnabled
            notificationDisabled.isGone = notificationEnabled
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val context = requireContext()

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.notificationChannel)
        return nm.areNotificationsEnabled() &&
                (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    override fun onResume() {
        super.onResume()

        if (!::binding.isInitialized) return

        val newNotificationEnabled = isNotificationEnabled()
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled
            syncNotificationEnabled()

            if (newNotificationEnabled) {
                startPairingService()
            }
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
    }
}
