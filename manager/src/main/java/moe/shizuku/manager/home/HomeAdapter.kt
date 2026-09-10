package moe.shizuku.manager.home

import android.os.Build
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool
import rikka.shizuku.Shizuku

class HomeAdapter(private val homeModel: HomeViewModel, private val appsModel: AppsViewModel) :
    IdBasedRecyclerViewAdapter(ArrayList()) {

    init {
        updateData()
        setHasStableIds(true)
    }

    companion object {

        private const val ID_STATUS = 0L
        private const val ID_APPS = 1L
        private const val ID_TERMINAL = 2L
        private const val ID_START_ROOT = 3L
        private const val ID_START_WADB = 4L
        private const val ID_START_ADB = 5L
        private const val ID_LEARN_MORE = 6L
        private const val ID_ADB_PERMISSION_LIMITED = 7L
        private const val ID_ACTIVATION = 8L
        private const val ID_DHIZUKU = 9L
        private const val ID_INFO = 10L
    }

    override fun onCreateCreatorPool(): IndexCreatorPool {
        return IndexCreatorPool()
    }

    fun updateData() {
        val status = homeModel.serviceStatus.value?.data ?: return
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0

        clear()
        addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)
        addItem(InfoCardViewHolder.CREATOR, status to grantedCount, ID_INFO)

        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)
            addItem(TerminalViewHolder.CREATOR, status, ID_TERMINAL)
        }

        if (running) {
            addItem(ActivationViewHolder.CREATOR, status, ID_ACTIVATION)
        }

        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
        }

        if (isPrimaryUser) {
            val root = EnvironmentUtils.isRooted()
            val rootRestart = running && status.uid == 0

            // Available start methods. The one picked in the setup wizard
            // (ShizukuSettings.PREFERRED_START_METHOD) is shown first; the
            // rest keep the default order below it.
            val preferred = ShizukuSettings.getPreferredStartMethod()

            data class MethodEntry(val method: Int, val add: () -> Unit)

            var dhizukuAdded = false
            val methods = buildList {
                // 服务已激活时隐藏 Root / 无线调试卡片，未激活时再显示
                if (root && !running) {
                    add(MethodEntry(ShizukuSettings.StartMethod.ROOT) {
                        addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)
                    })
                }
                if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0) && !running) {
                    add(MethodEntry(ShizukuSettings.StartMethod.WIRELESS_ADB) {
                        addItem(StartWirelessAdbViewHolder.CREATOR, null, ID_START_WADB)
                    })
                }
                add(MethodEntry(ShizukuSettings.StartMethod.COMPUTER_ADB) {
                    addItem(StartAdbViewHolder.CREATOR, null, ID_START_ADB)
                })
            }

            methods.sortedBy { if (it.method == preferred) 0 else 1 }
                .forEach {
                    it.add()
                    // Dhizuku（设备所有者）激活卡片：固定在无线调试卡片下方。
                // Shizuku 模式（server 运行）时卡片内提供一键激活。
                    if (it.method == ShizukuSettings.StartMethod.WIRELESS_ADB) {
                        addItem(StartDhizukuViewHolder.CREATOR, status, ID_DHIZUKU)
                        dhizukuAdded = true
                    }
                }

            // 无无线调试卡片（Android 10 及以下）时，Dhizuku 卡片排在激活方式末尾
            if (!dhizukuAdded) {
                addItem(StartDhizukuViewHolder.CREATOR, status, ID_DHIZUKU)
            }

            if (!root && !running) {
                addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)
            }
        }
        addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE)
        notifyDataSetChanged()
    }
}
