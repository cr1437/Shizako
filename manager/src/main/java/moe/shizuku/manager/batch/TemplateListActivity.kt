package moe.shizuku.manager.batch

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.glass.GlassWindow
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
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * F6/F8 的模板管理页：列表、新建/编辑/删除、导出/导入、批次历史。
 *
 * 外观和其它二级页（工具页 / 模块目录 / Dhizuku 授权页）完全一致：
 * 复用 `fragment_sub_page.xml` 的大标题折叠栏 + `HintPage` / `HintCard` /
 * `HintPrimaryButton` / `HintSecondaryButton`，玻璃与 MD3 两套风格自动跟随。
 */
class TemplateListActivity : AppActivity() {

    private val templates = mutableStateOf<List<PermissionTemplate>>(emptyList())
    private val history = mutableStateOf<List<BatchOperationRecord>>(emptyList())

    private val listState = LazyListState()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var shell: FragmentSubPageBinding

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = TemplateStore.exportJson(this)
        Thread {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.template_export_done, TemplateStore.all(this).size)
                    else getString(R.string.template_export_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (text.isNullOrBlank()) {
                    Toast.makeText(this, R.string.template_import_failed, Toast.LENGTH_SHORT).show()
                } else {
                    startImport(text)
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shell = FragmentSubPageBinding.inflate(layoutInflater)
        setContentView(shell.root)
        shell.toolbar.title = getString(R.string.template_title)
        shell.toolbar.setNavigationOnClickListener { finish() }

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        shell.contentContainer.addView(composeView)
        composeView.setShizakoContent {
            val style = UiStyle.current
            val palette = remember(style) { resolveHintPalette(this@TemplateListActivity) }
            TemplateListScreen(
                palette = palette,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                templates = templates.value,
                history = history.value,
                timeFormat = timeFormat,
                onCreate = {
                    startActivity(Intent(this@TemplateListActivity, TemplateEditActivity::class.java))
                },
                onEdit = { template ->
                    startActivity(
                        Intent(this@TemplateListActivity, TemplateEditActivity::class.java)
                            .putExtra(TemplateEditActivity.EXTRA_TEMPLATE_ID, template.id),
                    )
                },
                onDelete = ::confirmDelete,
                onExport = { exportLauncher.launch("shizako-templates.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onHistoryClick = ::showHistoryDetail,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        templates.value = TemplateStore.all(this)
        history.value = BatchStore.all(this)
    }

    private fun confirmDelete(template: PermissionTemplate) {
        if (template.builtIn) {
            Toast.makeText(this, R.string.template_builtin_readonly, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.template_delete_title)
            .setMessage(getString(R.string.template_delete_message, template.name))
            .setNegativeButton(R.string.batch_action_cancel, null)
            .setPositiveButton(R.string.template_delete) { _, _ ->
                TemplateStore.delete(this, template.id)
                reload()
            }
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    private fun showHistoryDetail(record: BatchOperationRecord) {
        val content = buildString {
            append(
                getString(
                    R.string.batch_result_summary,
                    record.successCount,
                    record.failedCount,
                    record.skippedCount,
                ),
            )
            if (record.templateName != null) {
                append("\n").append(getString(R.string.template_title)).append(": ").append(record.templateName)
            }
            append("\n").append(timeFormat.format(Date(record.timestamp)))
            val failed = record.items.filter { it.status == ItemStatus.FAILED }
            if (failed.isNotEmpty()) {
                append("\n\n").append(getString(R.string.batch_result_failed_section)).append(":")
                failed.forEach {
                    append("\n· ").append(BatchFlow.appLabel(this@TemplateListActivity, it.packageName))
                        .append(" (").append(it.packageName).append(") · ")
                        .append(it.reason ?: "")
                }
            }
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_history_detail_title)
            .setMessage(content)
            .setPositiveButton(R.string.batch_result_done, null)
        if (record.canUndo) {
            builder.setNeutralButton(R.string.batch_result_undo) { _, _ ->
                BatchFlow.undo(this, listOf(record)) { reload() }
            }
        }
        val dialog = builder.create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    /**
     * F8 导入：逐条校验，名称冲突时逐条询问「覆盖 / 跳过 / 重命名」。
     *
     * 冲突判定用 [TemplateStore.isNameTaken]（含内置模板）；内置模板不允许覆盖，
     * 这时对话框里干脆不提供「覆盖」按钮。
     */
    private fun startImport(json: String) {
        val parsed = TemplateStore.parseImport(json)
        if (parsed == null) {
            Toast.makeText(this, R.string.template_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (parsed.templates.isEmpty()) {
            Toast.makeText(this, R.string.template_import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (parsed.invalidCount > 0) {
            Toast.makeText(
                this,
                getString(R.string.template_import_invalid, parsed.invalidCount),
                Toast.LENGTH_SHORT,
            ).show()
        }

        val queue = ArrayDeque(parsed.templates)
        var imported = 0

        fun finishImport() {
            reload()
            Toast.makeText(
                this,
                getString(R.string.template_import_done, imported),
                Toast.LENGTH_SHORT,
            ).show()
        }

        fun next() {
            val item = queue.pollFirst() ?: run {
                finishImport()
                return
            }
            if (!TemplateStore.isNameTaken(this, item.name)) {
                TemplateStore.addImported(this, item.name, item.shizukuPermissions, item.dhizukuScopes)
                imported++
                next()
                return
            }

            val builtInConflict = TemplateStore.all(this).any {
                it.builtIn && it.name.trim().equals(item.name.trim(), ignoreCase = true)
            }
            val builder = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.template_import_conflict_title, item.name))
                .setMessage(R.string.template_import_conflict_message)
                .setCancelable(false)
                .setNegativeButton(R.string.template_import_skip) { _, _ -> next() }
                .setNeutralButton(R.string.template_import_rename) { _, _ ->
                    val newName = TemplateStore.suggestUniqueName(this, item.name)
                    TemplateStore.addImported(this, newName, item.shizukuPermissions, item.dhizukuScopes)
                    imported++
                    Toast.makeText(
                        this,
                        getString(R.string.template_import_renamed, newName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    next()
                }
            if (!builtInConflict) {
                builder.setPositiveButton(R.string.template_import_overwrite) { _, _ ->
                    TemplateStore.addImported(
                        this,
                        item.name,
                        item.shizukuPermissions,
                        item.dhizukuScopes,
                        overwriteExistingUserTemplate = true,
                    )
                    imported++
                    next()
                }
            }
            val dialog = builder.create()
            dialog.show()
            GlassWindow.applyIfGlass(dialog)
        }

        next()
    }
}

/** 模板列表页（F6/F8）。 */
@Composable
private fun TemplateListScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    templates: List<PermissionTemplate>,
    history: List<BatchOperationRecord>,
    timeFormat: SimpleDateFormat,
    onCreate: () -> Unit,
    onEdit: (PermissionTemplate) -> Unit,
    onDelete: (PermissionTemplate) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onHistoryClick: (BatchOperationRecord) -> Unit,
) {
    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        // 独立 Activity 没有悬浮底栏，但要给系统手势条 / 三键导航留白
        modifier = Modifier.navigationBarsPadding(),
        reserveNavSpace = false,
    ) {
        item {
            Box(modifier = Modifier.itemEntrance(0)) {
                HintCard(palette = palette) {
                    HintPrimaryButton(
                        palette = palette,
                        text = stringResource(R.string.template_new),
                        onClick = onCreate,
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.template_export),
                        onClick = onExport,
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.template_import),
                        onClick = onImport,
                    )
                }
            }
        }

        if (templates.isEmpty()) {
            item {
                Box(modifier = Modifier.itemEntrance(1)) {
                    HintNote(
                        palette = palette,
                        iconRes = R.drawable.ic_system_icon,
                        text = stringResource(R.string.template_none),
                    )
                }
            }
        } else {
            items(templates, key = { it.id }) { template ->
                Box(modifier = Modifier.itemEntrance(2)) {
                    HintCard(palette = palette) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = template.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.onCard,
                                modifier = Modifier.weight(1f),
                            )
                            if (template.builtIn) {
                                HintTag(palette = palette, text = stringResource(R.string.template_builtin))
                            }
                        }
                        Text(
                            text = templateSummary(template, timeFormat),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.template_edit),
                            onClick = { onEdit(template) },
                        )
                        if (!template.builtIn) {
                            HintSecondaryButton(
                                palette = palette,
                                text = stringResource(R.string.template_delete),
                                onClick = { onDelete(template) },
                            )
                        }
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.itemEntrance(3)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.batch_history_title))
            }
        }

        if (history.isEmpty()) {
            item {
                Box(modifier = Modifier.itemEntrance(3)) {
                    HintCard(palette = palette) {
                        Text(
                            text = stringResource(R.string.batch_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onCard,
                        )
                    }
                }
            }
        } else {
            items(history, key = { it.id }) { record ->
                Box(modifier = Modifier.itemEntrance(4)) {
                    HintCard(palette = palette) {
                        Text(
                            text = historyTitle(record),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.onCard,
                        )
                        Text(
                            text = stringResource(R.string.batch_history_row)
                                .format(record.successCount, record.failedCount, record.skippedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                        Text(
                            text = formatTime(record.timestamp, timeFormat),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.variant,
                        )
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.batch_history_detail_title),
                            onClick = { onHistoryClick(record) },
                        )
                    }
                }
            }
        }
    }
}

/** 小胶囊标签（「内置」），配色跟 HintPalette 的 tag 一套。 */
@Composable
private fun HintTag(palette: HintPalette, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = palette.onTagBackground,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(palette.tagBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun templateSummary(template: PermissionTemplate, timeFormat: SimpleDateFormat): String =
    if (template.createdAt <= 0L) {
        stringResource(R.string.template_summary_builtin).format(template.itemCount)
    } else {
        stringResource(R.string.template_summary)
            .format(template.itemCount, formatTime(template.createdAt, timeFormat))
    }

@Composable
private fun historyTitle(record: BatchOperationRecord): String {
    val type = stringResource(
        if (record.type == BatchType.GRANT) R.string.batch_type_grant_short
        else R.string.batch_type_revoke_short,
    )
    val target = stringResource(
        if (record.target == BatchTarget.SHIZUKU) R.string.batch_confirm_target_shizuku
        else R.string.batch_confirm_target_dhizuku,
    )
    return "$type · $target"
}

private fun formatTime(epochMillis: Long, format: SimpleDateFormat): String =
    if (epochMillis <= 0L) "—" else format.format(Date(epochMillis))
