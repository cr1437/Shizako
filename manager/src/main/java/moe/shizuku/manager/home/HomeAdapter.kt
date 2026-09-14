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

    /** 上一次列表的"指纹"：内容没变就不重建列表（下面解释了为什么） */
    private var lastSignature: String? = null

    fun updateData() {
        val status = homeModel.serviceStatus.value?.data ?: return
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0

        // 【性能】首页会收到很多次状态更新（服务状态、授权数量……），
        // 以前每次都 clear() + notifyDataSetChanged()，等于把所有卡片重新绑定一遍
        // （含应用图标异步加载）。先比一下指纹：内容没变就直接返回。
        val signature = buildString {
            append(running).append('|').append(adbPermission).append('|')
            append(grantedCount).append('|').append(isPrimaryUser).append('|')
            append(status.uid).append('|').append(status.apiVersion).append('|').append(status.seContext)
        }
        if (signature == lastSignature) return
        lastSignature = signature

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

        // 「深入了解 Shizako酱」那张卡按主人要求从首页去掉。
        // 代码**没删**（LearnMoreViewHolder / home_learn_more.xml / 文案都还在），
        // 想恢复就把下面这行加回来：
        // addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE)

        notifyDataSetChanged()
    }
}
