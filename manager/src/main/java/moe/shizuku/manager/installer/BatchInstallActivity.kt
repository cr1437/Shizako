package moe.shizuku.manager.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.ui.style.UiStyle
import moe.shizuku.manager.ui.theme.setShizakoContent
import java.util.Locale

/**
 * 设置 →「安装」→「批量安装」（规格 F3/F4）。
 *
 * 界面只做三件事：加文件、看进度、控制队列；真正的顺序执行在 [InstallQueue]。
 * **开始按钮是唯一的启动入口** —— 外部应用批量投递过来的文件只会被预置进列表
 * （规格安全要求：批量队列只能由本应用 UI 手动触发）。
 */
class BatchInstallActivity : AppActivity(), InstallQueue.Listener {

    private lateinit var shell: FragmentSubPageBinding

    private val snapshot = mutableStateOf(InstallQueue.snapshot())
    private val busy = mutableStateOf(false)
    private val listState = LazyListState()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        enqueue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shell = FragmentSubPageBinding.inflate(layoutInflater)
        setContentView(shell.root)
        shell.toolbar.title = getString(R.string.installer_batch_title)
        shell.toolbar.setNavigationOnClickListener { finish() }

        InstallQueue.restore(this)
        InstallQueue.addListener(this)
        preloadIfNeeded(intent)

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setShizakoContent {
            val style = UiStyle.current
            val palette = remember(style) { resolveHintPalette(this@BatchInstallActivity) }
            BatchScreen(
                palette = palette,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                snapshot = snapshot.value,
                busy = busy.value,
                onAdd = { picker.launch(arrayOf(MIME_APK, MIME_OCTET, "*/*")) },
                onStart = { InstallQueue.start(this) },
                onPause = { InstallQueue.pause(this) },
                onCancel = { InstallQueue.cancel(this) },
                onClearFinished = { InstallQueue.clearFinished(this) },
                onDiscard = { InstallQueue.discard(this) },
                onMove = { id, delta -> InstallQueue.move(this, id, delta) },
                onRemove = { id -> InstallQueue.remove(this, id) },
                onRetry = { id -> InstallQueue.retry(this, id) },
            )
        }
    }

    override fun onDestroy() {
        InstallQueue.removeListener(this)
        super.onDestroy()
    }

    override fun onQueueChanged(snapshot: InstallQueue.Snapshot) {
        runOnUiThread { this.snapshot.value = snapshot }
    }

    // ---------------- 加文件 ----------------

    /** 外部应用（`ACTION_SEND_MULTIPLE`）带进来的文件：只预置，不自动开始。 */
    private fun preloadIfNeeded(intent: Intent?) {
        val uris = intent?.getStringArrayListExtra(EXTRA_URIS) ?: return
        val names = intent.getStringArrayListExtra(EXTRA_NAMES)
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES)
        val source = runCatching {
            InstallSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: InstallSource.UI.name)
        }.getOrDefault(InstallSource.UI)
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        if (uris.isEmpty()) return

        busy.value = true
        Thread({
            val requests = uris.mapIndexedNotNull { index, value ->
                val uri = Uri.parse(value)
                val name = names?.getOrNull(index) ?: queryName(uri) ?: value.substringAfterLast('/')
                InstallRequest(
                    fileUri = value,
                    fileName = name,
                    format = PackageFormat.fromFileName(name),
                    source = source,
                    sourcePackage = sourcePackage,
                    sizeBytes = sizes?.getOrNull(index) ?: 0L,
                )
            }
            InstallQueue.add(this, requests)
            runOnUiThread {
                busy.value = false
                Toast.makeText(
                    this,
                    getString(R.string.installer_batch_preloaded, requests.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "batch-preload").start()
    }

    private fun enqueue(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy.value = true
        Thread({
            val requests = uris.mapNotNull { uri ->
                val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: return@mapNotNull null
                // 队列可能在进程重启后继续用这些 URI：能拿持久化授权就拿，拿不到也不阻塞
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                InstallRequest(
                    fileUri = uri.toString(),
                    fileName = name,
                    format = PackageFormat.fromFileName(name),
                    source = InstallSource.UI,
                    sourcePackage = null,
                    sizeBytes = querySize(uri),
                )
            }
            val added = InstallQueue.add(this, requests)
            runOnUiThread {
                busy.value = false
                if (added <= 0) {
                    Toast.makeText(this, R.string.installer_queue_nothing_added, Toast.LENGTH_SHORT).show()
                }
            }
        }, "batch-enqueue").start()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    companion object {
        private const val MIME_APK = "application/vnd.android.package-archive"
        private const val MIME_OCTET = "application/octet-stream"

        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_SIZES = "sizes"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SOURCE_PACKAGE = "source_package"

        fun intent(
            context: Context,
            uris: List<String>,
            names: List<String>,
            sizes: List<Long>,
            source: InstallSource,
            sourcePackage: String?,
        ): Intent = Intent(context, BatchInstallActivity::class.java)
            .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
            .putStringArrayListExtra(EXTRA_NAMES, ArrayList(names))
            .putExtra(EXTRA_SIZES, sizes.toLongArray())
            .putExtra(EXTRA_SOURCE, source.name)
            .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
    }
}

@Composable
private fun BatchScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    snapshot: InstallQueue.Snapshot,
    busy: Boolean,
    onAdd: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onClearFinished: () -> Unit,
    onDiscard: () -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintNote(
                palette = palette,
                iconRes = R.drawable.ic_warning_24,
                text = stringResource(R.string.installer_batch_note),
                secondary = stringResource(R.string.installer_batch_note_secondary),
                modifier = Modifier.itemEntrance(0),
            )
        }
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.installer_batch_progress))
                Text(
                    text = stringResource(
                        R.string.installer_batch_progress_detail,
                        snapshot.finished,
                        snapshot.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onCard,
                )
                ProgressBar(
                    palette = palette,
                    fraction = if (snapshot.total == 0) 0f else snapshot.finished.toFloat() / snapshot.total,
                )
                val current = snapshot.tasks.firstOrNull { it.id == snapshot.currentId }
                if (current != null) {
                    val stage = snapshot.stage
                    Text(
                        text = if (stage != null) {
                            stringResource(
                                R.string.installer_batch_current_stage,
                                current.fileName,
                                stage.first,
                                stage.second,
                            )
                        } else {
                            stringResource(R.string.installer_batch_current, current.fileName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintPrimaryButton(
                        palette = palette,
                        text = if (snapshot.running) {
                            stringResource(R.string.installer_batch_pause)
                        } else {
                            stringResource(R.string.installer_batch_start)
                        },
                        enabled = snapshot.running || snapshot.tasks.any { it.status == TaskStatus.WAITING },
                        onClick = { if (snapshot.running) onPause() else onStart() },
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.installer_batch_add),
                        enabled = !busy,
                        onClick = onAdd,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.installer_batch_cancel),
                        enabled = snapshot.running || snapshot.tasks.any { it.status == TaskStatus.WAITING },
                        onClick = onCancel,
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.installer_batch_clear_finished),
                        enabled = !snapshot.running && snapshot.tasks.any { it.status.isFinished },
                        onClick = onClearFinished,
                    )
                }
                if (!snapshot.running && snapshot.tasks.isNotEmpty()) {
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(R.string.installer_batch_discard), color = palette.error)
                    }
                }
            }
        }
        item {
            HintSectionTitle(
                palette = palette,
                text = stringResource(R.string.installer_batch_list, snapshot.total),
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        if (snapshot.tasks.isEmpty()) {
            item {
                HintCard(palette = palette) {
                    Text(
                        text = stringResource(R.string.installer_batch_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                    )
                }
            }
        }
        items(snapshot.tasks, key = { it.id }) { task ->
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = task.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.onCard,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${task.format.name} · ${formatSize(task.sizeBytes)} · " +
                                stringResource(statusRes(task.status)),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                    TextButton(
                        onClick = { onRetry(task.id) },
                        enabled = task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED,
                    ) {
                        Text(stringResource(R.string.installer_batch_retry))
                    }
                }
                if (!task.reason.isNullOrBlank()) {
                    Text(
                        text = task.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == TaskStatus.SUCCESS) palette.variant else palette.error,
                    )
                }
                if (!snapshot.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onMove(task.id, -1) }) {
                            Text(stringResource(R.string.installer_batch_move_up))
                        }
                        TextButton(onClick = { onMove(task.id, 1) }) {
                            Text(stringResource(R.string.installer_batch_move_down))
                        }
                        TextButton(onClick = { onRemove(task.id) }) {
                            Text(stringResource(R.string.installer_batch_remove))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun ProgressBar(palette: HintPalette, fraction: Float) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.variant.copy(alpha = 0.25f)),
    ) {
        if (safe > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safe)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.accent),
            )
        }
    }
}

@Composable
private fun statusRes(status: TaskStatus): Int = when (status) {
    TaskStatus.WAITING -> R.string.installer_status_waiting
    TaskStatus.INSTALLING -> R.string.installer_status_installing
    TaskStatus.SUCCESS -> R.string.installer_status_success
    TaskStatus.FAILED -> R.string.installer_status_failed
    TaskStatus.CANCELLED -> R.string.installer_status_cancelled
}

/** 大小展示在 UI 层统一口径（历史页也用它）。 */
fun formatSizeForUi(bytes: Long): String = formatSize(bytes)
