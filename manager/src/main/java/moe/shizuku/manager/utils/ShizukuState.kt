package moe.shizuku.manager.utils

import rikka.shizuku.Shizuku

/**
 * binder 状态的**进程内缓存**。
 *
 * `Shizuku.pingBinder()` 是一次 binder 事务（IPC）。可它在 UI 绑定路径里被反复调用 ——
 * 首页列表每绑一行状态卡都要问一次，滚动/刷新时就是成串的 IPC。
 * 真正需要"绝对实时"的地方（点按钮执行动作前）继续直接 ping；
 * 只是**显示用**的判断走这里：状态由 Shizuku 的 received/dead 监听维护，变化即更新。
 *
 * [install] 在主线程调一次（AppActivity.onCreate）。装之前 [isRunning] 直接问 binder，
 * 所以任何调用顺序下行为都和以前一致。
 */
object ShizukuState {

    @Volatile
    private var cachedRunning = false

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        cachedRunning = safePing()
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { cachedRunning = true }
            Shizuku.addBinderDeadListener { cachedRunning = false }
        }
    }

    /** 显示用：服务在不在 */
    fun isRunning(): Boolean = if (installed) cachedRunning else safePing()

    fun safePing(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }
}
