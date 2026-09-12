package moe.shizuku.manager.home

import moe.shizuku.manager.R
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool

class HomeAdapter(private val homeModel: HomeViewModel, private val appsModel: AppsViewModel) :
    IdBasedRecyclerViewAdapter(ArrayList()) {

    init {
        updateData()
        setHasStableIds(true)
    }

    companion object {

        private const val ID_STATUS = 0L
        private const val ID_LEARN_MORE = 6L
        private const val ID_ADB_PERMISSION_LIMITED = 7L
        private const val ID_ACTIVATION = 8L
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

        // 注：「管理应用」「终端」两张卡片已移除 ——
        // 应用管理交给底栏的 Tab，终端入口挪进「一站式激活」页。

        // 一站式激活卡片：状态总览 + 四种激活方式（Root / 无线调试 / 电脑 ADB / Dhizuku）
        if (isPrimaryUser) {
            addItem(ActivationViewHolder.CREATOR, status, ID_ACTIVATION)
        }

        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
        }

        addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE)
        notifyDataSetChanged()
    }
}
