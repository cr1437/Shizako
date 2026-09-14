package moe.shizuku.manager.setup

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import moe.shizuku.manager.app.AppActivity

/**
 * 「更新欢迎页」宿主：**老用户升级后弹一次**（由 MainActivity 判断并拉起）。
 *
 * 新装用户看不到这一页 —— 他们走的是首次引导（SetupActivity）。
 * 只做两件事：拿版本名、挂上 [UpdateWelcomeScreen]（Compose，和首次引导同一套双风格组件）。
 */
class UpdateWelcomeActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(composeView)

        composeView.setContent {
            UpdateWelcomeScreen(
                versionName = versionName,
                onStart = { finish() },
            )
        }
    }
}