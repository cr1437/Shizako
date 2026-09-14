package moe.shizuku.manager.model

data class ServiceStatus(
        val uid: Int = -1,
        val apiVersion: Int = -1,
        val patchVersion: Int = -1,
        val seContext: String? = null,
        val permission: Boolean = false
) {
    /**
     * 服务在不在。
     *
     * 【性能】以前这里直接 `Shizuku.pingBinder()` —— 那是一次 binder 事务，
     * 而首页列表每绑一行状态卡都会读这个属性，滚动/刷新时就是成串 IPC。
     * 现在走进程内缓存（由 binder received/dead 监听维护）：语义不变，开销降到读一次内存。
     * 真正要"现在就问一次"的地方（执行动作前）仍旧直接 `Shizuku.pingBinder()`。
     */
    val isRunning: Boolean
        get() = uid != -1 && moe.shizuku.manager.utils.ShizukuState.isRunning()
}