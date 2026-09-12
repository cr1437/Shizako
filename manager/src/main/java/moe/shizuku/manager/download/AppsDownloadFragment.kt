package moe.shizuku.manager.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.update.DownloadProgressDialog
import moe.shizuku.manager.update.UpdateChecker

/**
 * 推荐应用下载页的宿主（次级页：带返回键，底栏自动隐藏）。
 *
 * 只负责三件事：套 `fragment_sub_page.xml` 外壳、把「哪些已经装了」查出来、
 * 承接「下载 / 打开」两个动作。UI 全在 [AppsDownloadScreen] 里。
 */
class AppsDownloadFragment : Fragment() {

    private var shell: FragmentSubPageBinding? = null

    private val listState = LazyListState()

    /** 已安装的包名：onResume 重新查 —— 从浏览器装完回来状态就变了 */
    private var installedPackages by mutableStateOf<Set<String>>(emptySet())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell

        val composeView = ComposeView(inflater.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            val palette = androidx.compose.runtime.remember(style) { resolveHintPalette(requireContext()) }
            AppsDownloadScreen(
                palette = palette,
                listState = listState,
                installedPackages = installedPackages,
                onDownload = ::downloadApp,
                onOpenApp = { pkg -> openInstalledApp(requireContext(), pkg) },
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
            )
        }
        return shell.root
    }

    /**
     * 点「下载」：走**应用内下载器**，和「更新 Shizako」同一套 ——
     * [DownloadProgressDialog] 进度弹窗 + 通知栏进度 + 完成后拉起系统安装器。
     */
    private fun downloadApp(app: RecommendedApp) {
        val context = context ?: return
        val appName = getString(app.nameRes)

        val progress = DownloadProgressDialog.show(
            context = context,
            title = context.getString(R.string.update_downloading),
            version = appName,
            onCancel = { UpdateChecker.cancelDownload() },
        )
        progress.setState(context.getString(R.string.update_connecting))

        UpdateChecker.downloadFromUrl(
            context,
            app.apkUrl,
            app.fileName,
            object : UpdateChecker.DownloadListener {
                override fun onProgress(downloaded: Long, total: Long, speedBps: Long) {
                    if (progress.isShowing) progress.update(downloaded, total, speedBps)
                }

                override fun onRetry(attempt: Int, max: Int) {
                    if (!progress.isShowing) return
                    progress.setState(
                        if (max > 0) {
                            context.getString(R.string.update_retrying, attempt, max)
                        } else {
                            context.getString(R.string.update_connecting)
                        },
                    )
                }

                override fun onComplete(apkFile: java.io.File) {
                    progress.dismiss()
                    Toast.makeText(context, R.string.update_download_complete, Toast.LENGTH_SHORT).show()
                    // 装完回到本页：onResume 会把「已安装」刷新出来
                    UpdateChecker.installApk(context, apkFile)
                }

                override fun onFailed(reason: String) {
                    progress.dismiss()
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_download_failed, reason),
                        Toast.LENGTH_LONG,
                    ).show()
                }

                override fun onCancelled() {
                    progress.dismiss()
                    Toast.makeText(context, R.string.update_download_cancelled, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.download_page_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        refreshInstalled()
    }

    override fun onResume() {
        super.onResume()
        refreshInstalled()
    }

    private fun refreshInstalled() {
        val context = context ?: return
        val pm = context.packageManager
        installedPackages = RecommendedApps.ALL
            .map { it.packageName }
            .filter { pkg -> runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess }
            .toSet()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
    }
}
