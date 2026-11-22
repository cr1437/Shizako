package moe.shizuku.manager.storage

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.installer.formatSize
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「应用数据备份 / 恢复」页（规格 F3/F4/F5）。
 *
 * 三个页签：
 * - 备份：勾应用（单个或多个）→ 选 SAF 目录 → 入队 → 后台逐个打包（失败不中断，可取消）；
 * - 恢复：选 SAF 目录 → 列出 `<包名>.meta.json` 对应的备份 → 覆盖确认 → 解压回填；
 * - 第三方：给持有 Shizuku 授权的应用单独打开「存储代理」能力位（规格 F5）。
 *
 * 界面只做选择和展示，真正的执行在 [BackupEngine] / [BackupQueue]。
 */
class DataBackupActivity : AppActivity(), BackupQueue.Listener {

    

    private lateinit var shell: FragmentSubPageBinding

    private val mode = mutableStateOf(StorageMode.BACKUP)
    private val snapshot = mutableStateOf(BackupQueue.snapshot())
    private val apps = mutableStateOf<List<BackupTarget>>(emptyList())
    private val selected = mutableStateOf<Set<String>>(emptySet())
    private val includeObb = mutableStateOf(true)
    private val includeApk = mutableStateOf(false)
    private val loading = mutableStateOf(false)
    private val treeLabel = mutableStateOf<String?>(null)
    private val backups = mutableStateOf<List<BackupFiles>>(emptyList())
    private val restoreBusy = mutableStateOf(false)
    private val restoreMessage = mutableStateOf<String?>(null)
    private val grants = mutableStateOf<List<StorageProxyGrant.Entry>>(emptyList())
    private val listState = LazyListState()

    private var treeUri: Uri? = null
    private var appsLoaded = false

    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            treeUri = uri
            val dir = SafDirectory(contentResolver, uri)
            treeLabel.value = dir.displayName()
            if (mode.value == StorageMode.RESTORE) loadBackups(dir)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shell = FragmentSubPageBinding.inflate(layoutInflater)
        setContentView(shell.root)
        shell.toolbar.title = getString(R.string.storage_title)
        shell.toolbar.setNavigationOnClickListener { finish() }

        BackupQueue.addListener(this)
        loadApps()

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setShizakoContent {
            val style = UiStyle.current
            val palette = remember(style) { resolveHintPalette(this@DataBackupActivity) }
            DataBackupScreen(
                palette = palette,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                mode = mode.value,
                onModeChange = ::setMode,
                snapshot = snapshot.value,
                apps = apps.value,
                selected = selected.value,
                loading = loading.value,
                includeObb = includeObb.value,
                includeApk = includeApk.value,
                treeLabel = treeLabel.value,
                backups = backups.value,
                restoreBusy = restoreBusy.value,
                restoreMessage = restoreMessage.value,
                grants = grants.value,
                onToggleApp = ::toggleApp,
                onSelectAll = { selected.value = apps.value.map { it.packageName }.toSet() },
                onClearSelection = { selected.value = emptySet() },
                onToggleObb = { includeObb.value = it },
                onToggleApk = { includeApk.value = it },
                onPickDirectory = { directoryPicker.launch(null) },
                onStart = ::startBackup,
                onCancel = { BackupQueue.cancel() },
                onClearFinished = { BackupQueue.clearFinished() },
                onResetQueue = {
                    BackupQueue.reset()
                    treeUri = null
                    treeLabel.value = null
                },
                onPickBackupDirectory = { directoryPicker.launch(null) },
                onRefreshBackups = { treeUri?.let { loadBackups(SafDirectory(contentResolver, it)) } },
                onRestore = ::confirmRestore,
                onToggleGrant = { entry, enabled -> setGrant(entry, enabled) },
                onRefreshGrants = { loadGrants() },
                onShowProxyInfo = ::showProxyInfo,
            )
        }
    }

    override fun onDestroy() {
        BackupQueue.removeListener(this)
        super.onDestroy()
    }

    override fun onQueueChanged(snapshot: BackupQueue.Snapshot) {
        runOnUiThread { this.snapshot.value = snapshot }
    }

    // ---------------- 模式切换 ----------------

    private fun setMode(target: StorageMode) {
        mode.value = target
        when (target) {
            StorageMode.BACKUP -> if (!appsLoaded) loadApps()
            StorageMode.RESTORE -> treeUri?.let { loadBackups(SafDirectory(contentResolver, it)) }
            StorageMode.GRANT -> loadGrants()
        }
    }

    // ---------------- 备份 ----------------

    private fun loadApps() {
        if (loading.value) return
        loading.value = true
        Thread({
            val pm = packageManager
            val list = try {
                pm.getInstalledPackages(0).asSequence()
                    .filter { info ->
                        val ai = info.applicationInfo
                        ai != null && ai.uid >= 10000 && info.packageName != packageName
                    }
                    .map { info ->
                        val ai = info.applicationInfo!!
                        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                            info.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            info.versionCode.toLong()
                        }
                        BackupTarget(
                            packageName = info.packageName,
                            label = runCatching { ai.loadLabel(pm).toString() }
                                .getOrDefault(info.packageName),
                            versionName = info.versionName ?: "",
                            versionCode = versionCode,
                            debuggable = (ai.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                apps.value = list
                appsLoaded = true
                loading.value = false
            }
        }, "storage-load-apps").start()
    }

    private fun toggleApp(packageName: String) {
        val current = selected.value
        selected.value = if (packageName in current) current - packageName else current + packageName
    }

    private fun startBackup() {
        val uri = treeUri
        if (uri == null) {
            toast(getString(R.string.storage_need_directory))
            return
        }
        if (!StorageProxyClient.available()) {
            toast(getString(R.string.storage_no_service))
            return
        }
        val targets = apps.value.filter { it.packageName in selected.value }
        if (targets.isEmpty()) {
            toast(getString(R.string.storage_need_apps))
            return
        }
        val count = BackupQueue.prepare(
            context = this,
            selected = targets,
            treeUri = uri,
            includeObb = includeObb.value,
            includeApk = includeApk.value,
        )
        if (count == 0) {
            toast(getString(R.string.storage_queue_busy))
            return
        }
        BackupQueue.start(this)
    }

    // ---------------- 恢复 ----------------

    private fun loadBackups(dir: SafDirectory) {
        restoreMessage.value = null
        Thread({
            val result = runCatching { BackupEngine.scanBackups(dir) }
            runOnUiThread {
                backups.value = result.getOrDefault(emptyList())
                val error = result.exceptionOrNull()
                if (error != null) {
                    // 授权被回收时以前会静默显示「没有备份」，现在明确说「没权限，重新选」
                    restoreMessage.value = if (error is SafAccessException || error.cause is SafAccessException) {
                        getString(R.string.storage_saf_permission_lost)
                    } else {
                        getString(R.string.storage_toast_error, error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }, "storage-scan").start()
    }

    private fun confirmRestore(files: BackupFiles) {
        val meta = files.meta
        val installedVersion = BackupEngine.installedVersionCode(this, meta.packageName)

        if (installedVersion == null) {
            showNotInstalledDialog(files)
            return
        }

        val versionNote = if (installedVersion != meta.versionCode) {
            getString(
                R.string.storage_restore_version_warning,
                meta.versionName,
                installedVersion.toString(),
            )
        } else {
            ""
        }

        // 规格 §四：恢复前必须二次确认覆盖
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_restore_confirm_title)
            .setMessage(
                versionNote + getString(R.string.storage_restore_confirm_message, meta.packageName)
            )
            .setPositiveButton(R.string.storage_restore_action) { _, _ -> runRestore(files) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNotInstalledDialog(files: BackupFiles) {
        val meta = files.meta
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_restore_not_installed_title)
            .setMessage(
                getString(R.string.storage_restore_not_installed_message, meta.packageName)
                    + "\n\n" + getString(R.string.storage_restore_not_installed_no_download)
            )
            .setNegativeButton(android.R.string.cancel, null)

        if (files.apkUri != null) {
            builder.setPositiveButton(R.string.storage_restore_install_action) { _, _ ->
                launchInstaller(files.apkUri)
            }
        }
        builder.show()
    }

    /**
     * 安装联动（规格 F4）：把备份里的 APK 交给本应用已有的安装入口
     * （ApkInstallActivity 的 VIEW content://...apk 过滤器），复用信任列表 + 确认框，
     * 这里不自己直调 pm install，避免绕过安装器那一套判定。
     */
    private fun launchInstaller(apkUri: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(apkUri), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure {
            toast(getString(R.string.storage_toast_error, it.message ?: ""))
        }
    }

    private fun runRestore(files: BackupFiles) {
        if (restoreBusy.value) return
        if (!StorageProxyClient.available()) {
            toast(getString(R.string.storage_no_service))
            return
        }
        restoreBusy.value = true
        restoreMessage.value = getString(R.string.storage_restore_running)
        Thread({
            val outcome = BackupEngine.restore(this, files) { }
            runOnUiThread {
                restoreBusy.value = false
                restoreMessage.value = buildRestoreMessage(files, outcome)
            }
        }, "storage-restore").start()
    }

    private fun buildRestoreMessage(files: BackupFiles, outcome: BackupEngine.RestoreOutcome): String {
        val problem = outcome.reason
        if (!outcome.success && problem != null) {
            return when {
                problem == BackupEngine.NOTE_SAF_PERMISSION ->
                    getString(R.string.storage_saf_permission_lost)
                problem.startsWith("NO_SERVICE") -> getString(R.string.storage_no_service)
                problem.startsWith("EMPTY") -> getString(R.string.storage_restore_no_data)
                else -> getString(R.string.storage_restore_failed, problem)
            }
        }
        val base = getString(R.string.storage_restore_success, files.meta.packageName)
        val obbError = outcome.obbError
        return if (obbError != null) {
            val text = if (obbError == BackupEngine.NOTE_SAF_PERMISSION) {
                getString(R.string.storage_saf_permission_lost)
            } else {
                obbError
            }
            base + "\n" + getString(R.string.storage_restore_obb_failed, text)
        } else {
            base
        }
    }

    // ---------------- 第三方授权 ----------------

    private fun loadGrants() {
        Thread({
            val list = StorageProxyGrant.load(this)
            runOnUiThread { grants.value = list }
        }, "storage-grants").start()
    }

    private fun setGrant(entry: StorageProxyGrant.Entry, enabled: Boolean) {
        if (!StorageProxyGrant.set(entry.uid, enabled)) {
            toast(getString(R.string.storage_no_service))
            return
        }
        loadGrants()
    }

    /**
     * 服务端自检（规格 F2 的「环境摘要」入口）：把 IStorageProxy.info() 的 JSON 原样
     * 弹出来。里面有 uid / root 模式 / SDK / 四层各自的使用计数 / 上一次走的层，
     * 排查「为什么这个路径读不到」时先看这里，不用每次挂 adb。
     */
    private fun showProxyInfo() {
        Thread({
            val info = StorageProxyClient.info() ?: getString(R.string.storage_no_service)
            val layer = StorageProxyClient.lastLayer() ?: "-"
            val text = info + "\n\nlastLayer=" + layer
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.storage_proxy_info)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, "storage-info").start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private fun stageRes(stage: String?): Int? = when (stage) {
    "external" -> R.string.storage_stage_external
    "obb" -> R.string.storage_stage_obb
    "apk" -> R.string.storage_stage_apk
    "meta" -> R.string.storage_stage_meta
    else -> null
}

@Composable
private fun DataBackupScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    mode: StorageMode,
    onModeChange: (StorageMode) -> Unit,
    snapshot: BackupQueue.Snapshot,
    apps: List<BackupTarget>,
    selected: Set<String>,
    loading: Boolean,
    includeObb: Boolean,
    includeApk: Boolean,
    treeLabel: String?,
    backups: List<BackupFiles>,
    restoreBusy: Boolean,
    restoreMessage: String?,
    grants: List<StorageProxyGrant.Entry>,
    onToggleApp: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleObb: (Boolean) -> Unit,
    onToggleApk: (Boolean) -> Unit,
    onPickDirectory: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClearFinished: () -> Unit,
    onResetQueue: () -> Unit,
    onPickBackupDirectory: () -> Unit,
    onRefreshBackups: () -> Unit,
    onRestore: (BackupFiles) -> Unit,
    onToggleGrant: (StorageProxyGrant.Entry, Boolean) -> Unit,
    onRefreshGrants: () -> Unit,
    onShowProxyInfo: () -> Unit,
) {
    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.itemEntrance(0),
            ) {
                ModeButton(mode == StorageMode.BACKUP, { onModeChange(StorageMode.BACKUP) }, palette, stringResource(R.string.storage_mode_backup))
                ModeButton(mode == StorageMode.RESTORE, { onModeChange(StorageMode.RESTORE) }, palette, stringResource(R.string.storage_mode_restore))
                ModeButton(mode == StorageMode.GRANT, { onModeChange(StorageMode.GRANT) }, palette, stringResource(R.string.storage_mode_grant))
            }
        }

        when (mode) {
            StorageMode.BACKUP -> backupItems(
                palette, snapshot, apps, selected, loading, includeObb, includeApk, treeLabel,
                onToggleApp, onSelectAll, onClearSelection, onToggleObb, onToggleApk,
                onPickDirectory, onStart, onCancel, onClearFinished, onResetQueue,
                onShowProxyInfo,
            )
            StorageMode.RESTORE -> restoreItems(
                palette, backups, treeLabel, restoreBusy, restoreMessage,
                onPickBackupDirectory, onRefreshBackups, onRestore,
            )
            StorageMode.GRANT -> grantItems(palette, grants, onToggleGrant, onRefreshGrants)
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun ModeButton(active: Boolean, onClick: () -> Unit, palette: HintPalette, text: String) {
    if (active) {
        HintPrimaryButton(palette = palette, text = text, onClick = onClick)
    } else {
        HintSecondaryButton(palette = palette, text = text, onClick = onClick)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.backupItems(
    palette: HintPalette,
    snapshot: BackupQueue.Snapshot,
    apps: List<BackupTarget>,
    selected: Set<String>,
    loading: Boolean,
    includeObb: Boolean,
    includeApk: Boolean,
    treeLabel: String?,
    onToggleApp: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleObb: (Boolean) -> Unit,
    onToggleApk: (Boolean) -> Unit,
    onPickDirectory: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClearFinished: () -> Unit,
    onResetQueue: () -> Unit,
    onShowProxyInfo: () -> Unit,
) {
    item {
        HintNote(
            palette = palette,
            iconRes = R.drawable.ic_warning_24,
            text = stringResource(R.string.storage_backup_note),
            secondary = stringResource(R.string.storage_backup_note_secondary),
            modifier = Modifier.itemEntrance(1),
        )
    }
    item {
        HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
            Text(
                text = treeLabel?.let { stringResource(R.string.storage_directory_selected, it) }
                    ?: stringResource(R.string.storage_directory_none),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onCard,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_action_pick_directory),
                    onClick = onPickDirectory,
                )
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_action_start),
                    enabled = !snapshot.running && selected.isNotEmpty(),
                    onClick = onStart,
                )
            }
            Spacer(Modifier.height(8.dp))
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.storage_proxy_info),
                onClick = onShowProxyInfo,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(stringResource(R.string.storage_include_obb), includeObb, palette, onToggleObb)
            ToggleRow(stringResource(R.string.storage_include_apk), includeApk, palette, onToggleApk)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.storage_progress, snapshot.finished, snapshot.total),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
            val current = snapshot.current
            if (current != null) {
                Text(
                    text = stringResource(R.string.storage_current, current.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                val stage = stageRes(snapshot.stage)
                if (stage != null) {
                    Text(
                        text = stringResource(R.string.storage_stage_label, stringResource(stage)),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_action_cancel),
                    enabled = snapshot.running,
                    onClick = onCancel,
                )
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_action_clear),
                    enabled = !snapshot.running && snapshot.finished > 0,
                    onClick = onClearFinished,
                )
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_action_reset),
                    enabled = !snapshot.running,
                    onClick = onResetQueue,
                )
            }
        }
    }

    if (snapshot.tasks.isNotEmpty()) {
        item {
            HintSectionTitle(
                palette = palette,
                text = stringResource(R.string.storage_tasks_title),
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        items(snapshot.tasks, key = { it.id }) { task ->
            HintCard(palette = palette, modifier = Modifier.itemEntrance(3)) {
                Text(
                    text = task.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${task.packageName} · " + stringResource(statusRes(task.status)) +
                        " · " + formatSize(task.bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                if (!task.reason.isNullOrBlank()) {
                    // reason 里可能是服务端/引擎给的机器码（如 APK 跳过），在 UI 层本地化
                    val reasonText = when (task.reason) {
                        BackupEngine.NOTE_APK_SKIPPED ->
                            stringResource(R.string.storage_backup_apk_skipped)
                        BackupEngine.NOTE_SAF_PERMISSION ->
                            stringResource(R.string.storage_saf_permission_lost)
                        else -> task.reason
                    }
                    Text(
                        text = reasonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == BackupStatus.SUCCESS) palette.variant else palette.error,
                    )
                }
            }
        }
    }

    item {
        HintSectionTitle(
            palette = palette,
            text = stringResource(R.string.storage_apps_title, selected.size),
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.storage_action_select_all),
                enabled = apps.isNotEmpty(),
                onClick = onSelectAll,
            )
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.storage_action_clear_selection),
                enabled = selected.isNotEmpty(),
                onClick = onClearSelection,
            )
        }
    }
    if (loading && apps.isEmpty()) {
        item {
            HintCard(palette = palette) {
                Text(
                    text = stringResource(R.string.storage_apps_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
            }
        }
    } else if (apps.isEmpty()) {
        item {
            HintCard(palette = palette) {
                Text(
                    text = stringResource(R.string.storage_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
            }
        }
    }
    items(apps, key = { it.packageName }) { app ->
        HintCard(palette = palette, modifier = Modifier.itemEntrance(4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = app.packageName in selected,
                    onCheckedChange = { onToggleApp(app.packageName) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.onCard,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = app.packageName + (if (app.versionName.isNotEmpty()) " · ${app.versionName}" else "") +
                            (if (app.debuggable) " · debuggable" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.restoreItems(
    palette: HintPalette,
    backups: List<BackupFiles>,
    treeLabel: String?,
    restoreBusy: Boolean,
    restoreMessage: String?,
    onPickBackupDirectory: () -> Unit,
    onRefreshBackups: () -> Unit,
    onRestore: (BackupFiles) -> Unit,
) {
    item {
        HintNote(
            palette = palette,
            iconRes = R.drawable.ic_warning_24,
            text = stringResource(R.string.storage_restore_note),
            secondary = stringResource(R.string.storage_restore_note_secondary),
            modifier = Modifier.itemEntrance(1),
        )
    }
    item {
        HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
            Text(
                text = treeLabel?.let { stringResource(R.string.storage_directory_selected, it) }
                    ?: stringResource(R.string.storage_directory_none),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onCard,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_restore_pick),
                    onClick = onPickBackupDirectory,
                )
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.storage_action_refresh),
                    onClick = onRefreshBackups,
                )
            }
            if (restoreBusy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.storage_restore_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
            }
            if (!restoreMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = restoreMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
            }
        }
    }
    if (backups.isEmpty()) {
        item {
            HintCard(palette = palette) {
                Text(
                    text = stringResource(R.string.storage_restore_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
            }
        }
    }
    items(backups, key = { it.meta.packageName + it.meta.backupTime }) { files ->
        val meta = files.meta
        HintCard(palette = palette, modifier = Modifier.itemEntrance(3)) {
            Text(
                text = meta.label.ifBlank { meta.packageName },
                style = MaterialTheme.typography.titleSmall,
                color = palette.onCard,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta.packageName + " · " + meta.versionName + " (" + meta.versionCode + ")",
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.storage_restore_item_detail,
                    formatTime(meta.backupTime),
                    formatSize(meta.externalBytes),
                    if (meta.includesObb) formatSize(meta.obbBytes) else "—",
                    if (meta.includesApk) "✓" else "—",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
            Spacer(Modifier.height(4.dp))
            HintPrimaryButton(
                palette = palette,
                text = stringResource(R.string.storage_restore_action),
                enabled = !restoreBusy,
                onClick = { onRestore(files) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.grantItems(
    palette: HintPalette,
    grants: List<StorageProxyGrant.Entry>,
    onToggleGrant: (StorageProxyGrant.Entry, Boolean) -> Unit,
    onRefreshGrants: () -> Unit,
) {
    item {
        HintNote(
            palette = palette,
            iconRes = R.drawable.ic_warning_24,
            text = stringResource(R.string.storage_grant_note),
            secondary = stringResource(R.string.storage_grant_note_secondary),
            modifier = Modifier.itemEntrance(1),
        )
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HintSecondaryButton(
                palette = palette,
                text = stringResource(R.string.storage_action_refresh),
                onClick = onRefreshGrants,
            )
        }
    }
    if (grants.isEmpty()) {
        item {
            HintCard(palette = palette) {
                Text(
                    text = stringResource(R.string.storage_grant_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
            }
        }
    }
    items(grants, key = { it.packageName }) { entry ->
        HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.onCard,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = entry.granted,
                    onCheckedChange = { onToggleGrant(entry, it) },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    text: String,
    checked: Boolean,
    palette: HintPalette,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onCard,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun statusRes(status: BackupStatus): Int = when (status) {
    BackupStatus.WAITING -> R.string.storage_status_waiting
    BackupStatus.RUNNING -> R.string.storage_status_running
    BackupStatus.SUCCESS -> R.string.storage_status_success
    BackupStatus.FAILED -> R.string.storage_status_failed
    BackupStatus.CANCELLED -> R.string.storage_status_cancelled
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

/** 页签：备份 / 恢复 / 第三方授权。文件级 private 让同文件的 @Composable 也能用。 */
private enum class StorageMode { BACKUP, RESTORE, GRANT }
