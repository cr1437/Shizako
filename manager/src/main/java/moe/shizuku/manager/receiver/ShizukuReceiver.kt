package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.shell.ShellBinderRequestHandler

class ShizukuReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ("rikka.shizuku.intent.action.REQUEST_BINDER" == intent.action) {
            // 把 PendingResult 交给处理器：等 binder 可能要一小会儿，不能在主线程阻塞
            // （终端应用只等 5 秒，广播也不能被拖到 ANR）
            ShellBinderRequestHandler.handleRequest(context, intent, goAsync())
        }
    }
}
