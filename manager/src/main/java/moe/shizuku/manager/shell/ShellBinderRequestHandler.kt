package moe.shizuku.manager.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    private const val ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"

    private const val BINDER_WAIT_MS = 2500L
    private const val BINDER_POLL_MS = 100L

    /**
     * 终端应用（rish 的 `rikka.shizuku.shell.ShizukuShellLoader`）启动时会广播
     * [ACTION_REQUEST_BINDER] 向管理器要 Shizuku 的 binder，然后**只等 5 秒**：
     * 超时就打印 “Request timeout. The connection between the current app … may be blocked”。
     *
     * 这里的责任是「尽快给出答案」：
     * - binder 已经拿过 → 立刻回；
     * - 还没拿到（服务刚起 / 管理器刚被解冻，binder 回调还没回来）→ 用 `pingBinder()` 催，
     *   最多 [BINDER_WAIT_MS]（留在对方 5s 窗口内），拿到就回；
     * - 真拿不到就**不回** —— 回 null binder 会让对方打印 “Server is not running”，
     *   反而是误导；让它超时并给出「电池优化/被冻结」提示更贴近真实原因。
     *
     * @param pendingResult 广播的 [BroadcastReceiver.PendingResult]（可为 null，此时同步处理）
     * @return 是否处理了这次请求（true = 已经回复对方 binder）
     */
    fun handleRequest(
        context: Context,
        intent: Intent,
        pendingResult: BroadcastReceiver.PendingResult? = null,
    ): Boolean {
        if (intent.action != ACTION_REQUEST_BINDER) {
            pendingResult?.finish()
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder")
        if (binder == null) {
            pendingResult?.finish()
            return false
        }

        // binder 已在手上：直接回，别绕线程
        val ready = Shizuku.getBinder()
        if (ready != null) {
            reply(context.applicationInfo.sourceDir, binder, ready, intent.`package` ?: "?")
            pendingResult?.finish()
            return true
        }

        // 还没拿到：等 binder 不能占着广播的主线程，交给工作线程
        val sourceDir = context.applicationInfo.sourceDir
        val from = intent.`package` ?: "?"
        Thread {
            try {
                val shizukuBinder = awaitShizukuBinder()
                if (shizukuBinder == null) {
                    LOGGER.w("REQUEST_BINDER from $from: binder not available, not replying")
                } else {
                    reply(sourceDir, binder, shizukuBinder, from)
                }
            } catch (e: Throwable) {
                LOGGER.w(e, "REQUEST_BINDER from $from")
            } finally {
                pendingResult?.finish()
            }
        }.start()
        return true
    }

    private fun reply(sourceDir: String, binder: IBinder, shizukuBinder: IBinder, from: String) {
        val data = Parcel.obtain()
        try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            LOGGER.i("REQUEST_BINDER from $from: binder sent")
        } catch (e: Throwable) {
            LOGGER.w(e, "REQUEST_BINDER from $from: reply failed")
        } finally {
            data.recycle()
        }
    }

    /** 拿到（或等到）Shizuku 服务的 binder。 */
    private fun awaitShizukuBinder(): IBinder? {
        Shizuku.getBinder()?.let { return it }

        val deadline = System.currentTimeMillis() + BINDER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                Shizuku.getBinder()?.let { return it }
            }
            Thread.sleep(BINDER_POLL_MS)
        }
        return Shizuku.getBinder()
    }
}
