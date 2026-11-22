package moe.shizuku.manager.installer

import android.os.Bundle
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.databinding.FragmentSubPageBinding
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.ui.style.UiStyle
import moe.shizuku.manager.ui.theme.setShizakoContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 →「安装」→「安装历史」（规格 F5）。
 *
 * 筛选用三个维度（结果 / 来源 / 时间），筛选逻辑本身在
 * [filterInstallHistory]（纯函数，单测覆盖），这里只负责把选中项填进去。
 * 历史只留最近 [InstallHistoryStore.MAX_ENTRIES] 条，多了自动挤掉最旧的。
 */
class InstallHistoryActivity : AppActivity() {

    private lateinit var shell: FragmentSubPageBinding
    private val entries = mutableStateOf<List<InstallHistoryEntry>>(emptyList())
    private val listState = LazyListState()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shell = FragmentSubPageBinding.inflate(layoutInflater)
        setContentView(shell.root)
        shell.toolbar.title = getString(R.string.installer_history_title)
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
            val palette = remember(style) { resolveHintPalette(this@InstallHistoryActivity) }
            HistoryScreen(
                palette = palette,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                entries = entries.value,
                timeFormat = timeFormat,
                onClear = {
                    InstallHistoryStore.clear(this)
                    entries.value = InstallHistoryStore.all(this)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        entries.value = InstallHistoryStore.all(this)
    }
}

@Composable
private fun HistoryScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    entries: List<InstallHistoryEntry>,
    timeFormat: SimpleDateFormat,
    onClear: () -> Unit,
) {
    var result by remember { mutableStateOf<TaskStatus?>(null) }
    var source by remember { mutableStateOf<InstallSource?>(null) }
    // 存"最近多久"而不是绝对时间戳：筛选条件在页面停留期间保持稳定，不会因为时间流逝把
    // 刚过 24 小时的那条悄悄筛掉（重进页面时重新按当前时间算）
    var age by remember { mutableLongStateOf(0L) }
    var showClear by remember { mutableStateOf(false) }

    val sinceMs = age.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }
    val filtered = filterInstallHistory(entries, result, source, sinceMs)

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintNote(
                palette = palette,
                iconRes = R.drawable.ic_outline_assignment_24,
                text = stringResource(R.string.installer_history_note, InstallHistoryStore.MAX_ENTRIES),
                modifier = Modifier.itemEntrance(0),
            )
        }
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.installer_history_filter_result))
                FilterRow {
                    Chip(palette, stringResource(R.string.installer_filter_all), result == null) { result = null }
                    Chip(palette, stringResource(R.string.installer_status_success), result == TaskStatus.SUCCESS) {
                        result = TaskStatus.SUCCESS
                    }
                    Chip(palette, stringResource(R.string.installer_status_failed), result == TaskStatus.FAILED) {
                        result = TaskStatus.FAILED
                    }
                    Chip(palette, stringResource(R.string.installer_status_cancelled), result == TaskStatus.CANCELLED) {
                        result = TaskStatus.CANCELLED
                    }
                }
                HintSectionTitle(palette = palette, text = stringResource(R.string.installer_history_filter_source))
                FilterRow {
                    Chip(palette, stringResource(R.string.installer_filter_all), source == null) { source = null }
                    InstallSource.values().forEach { item ->
                        Chip(palette, sourceLabel(item), source == item) { source = item }
                    }
                }
                HintSectionTitle(palette = palette, text = stringResource(R.string.installer_history_filter_time))
                FilterRow {
                    Chip(palette, stringResource(R.string.installer_filter_all), age == 0L) { age = 0L }
                    Chip(palette, stringResource(R.string.installer_time_24h), age == DAY) { age = DAY }
                    Chip(palette, stringResource(R.string.installer_time_7d), age == WEEK) { age = WEEK }
                    Chip(palette, stringResource(R.string.installer_time_30d), age == MONTH) { age = MONTH }
                }
                if (filtered.isNotEmpty()) {
                    TextButton(onClick = { showClear = true }) {
                        Text(stringResource(R.string.installer_history_clear), color = palette.error)
                    }
                }
            }
        }
        item {
            HintSectionTitle(
                palette = palette,
                text = stringResource(R.string.installer_history_count, filtered.size),
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        if (filtered.isEmpty()) {
            item {
                HintCard(palette = palette) {
                    Text(
                        text = stringResource(R.string.installer_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                    )
                }
            }
        }
        items(filtered, key = { it.id }) { entry ->
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.onCard,
                        )
                        Text(
                            text = listOfNotNull(
                                entry.packageName,
                                entry.versionName?.let { "v$it" },
                            ).joinToString(" · ").ifBlank { "—" },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                    Text(
                        text = stringResource(statusResOf(entry.result)),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.result == TaskStatus.SUCCESS) palette.accent else palette.error,
                    )
                }
                HorizontalDivider(color = palette.variant.copy(alpha = 0.2f))
                Text(
                    text = stringResource(
                        R.string.installer_history_meta,
                        sourceLabel(entry.source),
                        entry.format.name,
                        timeFormat.format(Date(entry.timestamp)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Text(
                    text = stringResource(R.string.installer_history_duration, entry.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                if (!entry.sourcePackage.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.installer_history_from, entry.sourcePackage),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }
                if (!entry.reason.isNullOrBlank()) {
                    Text(
                        text = entry.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.result == TaskStatus.SUCCESS) palette.variant else palette.error,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.installer_history_clear)) },
            text = { Text(stringResource(R.string.installer_history_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClear = false
                        onClear()
                    },
                ) {
                    Text(stringResource(R.string.installer_history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text(stringResource(R.string.batch_action_cancel))
                }
            },
        )
    }
}

private const val HOUR = 60L * 60L * 1000L
private const val DAY = 24L * HOUR
private const val WEEK = 7L * DAY
private const val MONTH = 30L * DAY

/** FilterChip 需要一个横向滚动容器；这里的 Chip 只是把「选中态从当前筛选值推导」收敛到一处。 */
@Composable
private fun Chip(palette: HintPalette, text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
    )
}

@Composable
private fun FilterRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun sourceLabel(source: InstallSource): String = stringResource(
    when (source) {
        InstallSource.UI -> R.string.installer_source_ui
        InstallSource.TRUSTED_APP -> R.string.installer_source_trusted
        InstallSource.USER_APPROVED -> R.string.installer_source_user_approved
        InstallSource.SYSTEM -> R.string.installer_source_system
    },
)

@Composable
private fun statusResOf(status: TaskStatus): Int = when (status) {
    TaskStatus.WAITING -> R.string.installer_status_waiting
    TaskStatus.INSTALLING -> R.string.installer_status_installing
    TaskStatus.SUCCESS -> R.string.installer_status_success
    TaskStatus.FAILED -> R.string.installer_status_failed
    TaskStatus.CANCELLED -> R.string.installer_status_cancelled
}
