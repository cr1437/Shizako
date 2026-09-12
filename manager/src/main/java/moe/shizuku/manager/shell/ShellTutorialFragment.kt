package moe.shizuku.manager.shell

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.utils.CustomTabsHelper
import java.util.concurrent.Executors

/**
 * 终端（rish）教程页。
 *
 * UI 为 Jetpack Compose（[TerminalTutorialScreen]），支持 MD3 / 玻璃双风格。
 * 相比旧版多了「一键部署」：通过运行中的 Shizaku 服务，把 APK 里的
 * `rish` + `rish_shizuku.dex` 直接放进 Termux 的 `$PREFIX/bin`（root 身份）
 * 或 `/data/local/tmp/rish`（adb 身份），并处理好 Android 14+ 要求的 dex 只读权限，
 * 用户打开终端敲 `rish` 就能用；原有的「导出文件」手动流程保留为兜底。
 */
class ShellTutorialFragment : Fragment() {

    companion object {
        private const val SH_NAME = "rish"
        private const val DEX_NAME = "rish_shizuku.dex"
    }

    private var shell: FragmentSubPageBinding? = null

    private var uiState by mutableStateOf(TerminalTutorialState())
    private val listState = LazyListState()

    private val deployExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rish-deploy").apply { isDaemon = true }
    }

    /** 手动导出（兜底）：把 assets 写到用户选的目录。 */
    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null || context == null) return@registerForActivityResult
            val context = requireContext()
            val cr = context.contentResolver
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )

            // 同名文件先删掉，避免 createDocument 自动改名
            cr.query(
                child,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name == SH_NAME || name == DEX_NAME) {
                        DocumentsContract.deleteDocument(
                            cr,
                            DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        )
                    }
                }
            }

            fun writeToDocument(name: String) {
                DocumentsContract.createDocument(cr, doc, "application/octet-stream", name)?.runCatching {
                    cr.openOutputStream(this)?.let { context.assets.open(name).copyTo(it) }
                }
            }
            writeToDocument(SH_NAME)
            writeToDocument(DEX_NAME)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = FragmentSubPageBinding.inflate(inflater, container, false)
        this.shell = shell

        val composeView = ComposeView(inflater.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setContent {
            val style = moe.shizuku.manager.ui.style.UiStyle.current
            // 风格切换直接按当前风格组合（不做交叉淡入 —— 动画统一交给官方 MD3 那套）
            val pal = androidx.compose.runtime.remember(style) { resolveHintPalette(requireContext()) }
            TerminalTutorialScreen(
                state = uiState,
                palette = pal,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                onDeploy = ::startDeploy,
                onExportFiles = { openDocumentsTree.launch(null) },
                onOpenDocs = { CustomTabsHelper.launchUrlOrCopy(requireContext(), Helps.RISH.get()) },
            )
        }
        return shell.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shell = shell ?: return
        shell.toolbar.title = getString(R.string.home_terminal_title)
        shell.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        refreshEnvironment()
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironment()
    }

    /** 环境探测（服务身份 / Termux 是否存在）放到后台线程，避免阻塞首帧。 */
    private fun refreshEnvironment() {
        if (context == null) return
        deployExecutor.execute {
            val running = RishInstaller.isServiceRunning()
            val root = running && RishInstaller.isRoot()
            val termux = running && RishInstaller.hasTermux()
            view?.post {
                uiState = uiState.copy(
                    serviceRunning = running,
                    isRoot = root,
                    hasTermux = termux,
                )
            }
        }
    }

    private fun startDeploy() {
        val context = context ?: return
        if (uiState.deploy is DeployState.Running) return
        uiState = uiState.copy(deploy = DeployState.Running)

        deployExecutor.execute {
            // 只有「装了 Termux + 服务是 root」才能写进 Termux 的 $PREFIX/bin（chown 需要 root）
            val installIntoTermux = RishInstaller.hasTermux() && RishInstaller.isRoot()
            val result = RishInstaller.deploy(context, installIntoTermux)
            Log.i(AppConstants.TAG, "rish deploy: $result")
            view?.post {
                uiState = uiState.copy(
                    deploy = when (result) {
                        is RishInstaller.Result.Success -> DeployState.Success(
                            targetDir = result.targetDir,
                            inTermuxPath = result.inTermuxPath,
                        )
                        is RishInstaller.Result.Failure -> DeployState.Failure(result.message)
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shell = null
    }

    override fun onDestroy() {
        deployExecutor.shutdownNow()
        super.onDestroy()
    }
}
