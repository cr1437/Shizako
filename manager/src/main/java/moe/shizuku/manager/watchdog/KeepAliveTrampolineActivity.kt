package moe.shizuku.manager.watchdog

import android.app.Activity
import android.os.Bundle

/**
 * 「用 ADB 保活」的拉起跳板：shell 小看门检测到 App 进程没了，
 * 就用 `am start` 启动这个**透明无界面 Activity**（唯一 exported 的入口），
 * 它把看门狗服务拉起来后立刻消失。
 *
 * - 启动 Activity 属于“用户式启动”：不受后台 FGS 启动限制；
 * - 顺带清掉系统的 stopped 状态（App 被“强停”后也能救回来）；
 * - 透明主题 + noHistory + excludeFromRecents：不打扰用户、不占最近任务。
 */
class KeepAliveTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kick()
        finish()
    }

    override fun onResume() {
        super.onResume()
        kick()
        finish()
    }

    private fun kick() {
        runCatching { WatchdogService.start(this) }
    }
}