@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package moe.shizuku.manager.comput

import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.res.painterResource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.HintStyle
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.utils.AiExplainUtil
import moe.shizuku.server.IShizukuService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
// 数据
// ============================================================================

/** 一个宏：名字 + 它要跑的命令（照搬 Shevery 的「名字 → 命令列表」模型，UI 上先发一条）。 */
data class ComputMacro(val name: String, val commands: List<String>)

/** 一次执行的结局，决定输出卡底下那行状态怎么写。 */
enum class ComputOutcome { NONE, OK, FAILED, TIMEOUT, CANCELLED }

/** [ComputScreen] 要显示的全部状态。Fragment 单向下发，屏幕只读。 */
data class ComputUiState(
    val isRunning: Boolean = false,
    /** 输出被 64KB 上限截断过（末尾补了一行提示，这里只用来记状态） */
    val outputTruncated: Boolean = false,
    val lastOutcome: ComputOutcome = ComputOutcome.NONE,
    val lastExitCode: Int = 0,
    val lastCommand: String = "",
    /** 上次执行耗时（毫秒），没跑过是 0 */
    val lastElapsedMs: Long = 0L,
    val aiExplanation: String = "",
    val isExplaining: Boolean = false,
    /** 已经配置过 Gemini key（决定「AI 解释」按钮是否可点） */
    val aiKeyConfigured: Boolean = false,
)

/** 屏幕往外抛的动作。全部由宿主 Fragment 实现（它持有 runner / 偏好读写）。 */
class ComputCallbacks(
    val onRun: (String) -> Unit,
    val onRunMacro: (ComputMacro) -> Unit,
    val onSaveMacro: (String, String) -> Unit,
    val onRenameMacro: (String, String) -> Unit,
    val onDeleteMacro: (String) -> Unit,
    val onClear: () -> Unit,
    val onStop: () -> Unit,
    val onCopy: (String, String) -> Unit,
    val onExplain: () -> Unit,
    val onSaveApiKey: (String) -> Unit,
)

// ============================================================================
// 宏的持久化
// ============================================================================

/**
 * 宏的存储：`SharedPreferences("settings")` 里一个 JSON 字符串。
 *
 * 为什么用 JSON 而不是 `StringSet`：宏是「名字 → 命令列表」的有序映射，
 * StringSet 既丢顺序又得自己拼分隔符（命令里本来就可能出现任何字符）。
 * 形状（`{"名字":["cmd1","cmd2"]}`）和上游一致，方便以后互通。
 */
object ComputMacroStore {

    private const val KEY_MACROS = "comput_macros"

    private fun prefs() = moe.shizuku.manager.ShizukuSettings.getPreferences()

    fun load(): List<ComputMacro> {
        val raw = prefs().getString(KEY_MACROS, "{}").orEmpty()
        return try {
            val json = JSONObject(raw)
            val keys = json.keys()
            val out = ArrayList<ComputMacro>(json.length())
            while (keys.hasNext()) {
                val name = keys.next()
                val array = json.optJSONArray(name) ?: continue
                val commands = ArrayList<String>(array.length())
                for (i in 0 until array.length()) {
                    commands.add(array.optString(i))
                }
                out.add(ComputMacro(name, commands))
            }
            out
        } catch (e: Exception) {
            // 数据坏了就当没有宏，别让控制台打不开
            emptyList()
        }
    }

    fun save(macros: List<ComputMacro>) {
        val json = JSONObject()
        macros.forEach { macro ->
            json.put(macro.name, JSONArray(macro.commands))
        }
        prefs().edit().putString(KEY_MACROS, json.toString()).apply()
    }
}

// ============================================================================
// 执行器
// ============================================================================

/**
 * 在 Shizuku 服务里跑一条 shell 命令。
 *
 * 和 `AdbModuleManager.runModuleScript` 同一套路子（`IShizukuService.newProcess` +
 * 双线程读流 + `waitForTimeout`，超时退出码 124），区别是要**流式**回报输出：
 * 每读到一个块就回调出去，UI 才能看到 `logcat` 这种长命令的中间结果。
 *
 * 限额：输出上限 64KB（只留尾巴，前面的内容用户也读不过来）、超时 120 秒。
 */
object ComputRunner {

    /** 单条命令最长跑 120 秒 */
    private const val TIMEOUT_SECONDS = 120L

    /** 每路流（stdout / stderr）保留的字符上限 */
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** 请求停止当前命令：销毁远端进程，读流线程会跟着退出。 */
    fun stop() {
        stopRequested.set(true)
    }

    /**
     * 跑一条命令。
     *
     * @param onUpdate 每次有新的输出就回调（参数是「截至目前的全部输出」，已带条数上限），
     *   在读取线程里调用 —— 宿主必须自己切回主线程。
     */
    suspend fun run(
        command: String,
        shizukuUnavailableMessage: String,
        timeoutMessage: String,
        cancelledMessage: String,
        noOutputMessage: String,
        exitMessage: (Int) -> String,
        onUpdate: (String, Boolean) -> Unit,
    ): ComputResult = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) {
            return@withContext ComputResult(
                text = "",
                exitCode = -1,
                finished = false,
                cancelled = false,
                timedOut = false,
                startedAt = SystemClock.elapsedRealtime(),
            )
        }
        stopRequested.set(false)

        try {
            val binder = if (Shizuku.pingBinder()) Shizuku.getBinder() else null
                ?: return@withContext ComputResult(
                    text = shizukuUnavailableMessage,
                    exitCode = -1,
                    finished = false,
                    cancelled = false,
                    timedOut = false,
                    startedAt = SystemClock.elapsedRealtime(),
                ).also { onUpdate(it.text, true) }

            val service = IShizukuService.Stub.asInterface(binder)
            val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val shutdown = { runCatching { remote.destroy() } }

            val out = StreamFilter(MAX_OUTPUT_CHARS)
            val err = StreamFilter(MAX_OUTPUT_CHARS)
            val stdoutPfd = remote.getInputStream()
            val stderrPfd = remote.getErrorStream()

            // 打开管道的写端：不打开的话子进程可能读不到 EOF 卡在那里
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close() }

            fun snapshot(): String =
                composeOutput(out.snapshot(), err.snapshot(), out.truncated || err.truncated)

            val stdoutThread = Thread({
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd)
                        .bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(4096)
                            while (!stopRequested.get()) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                out.append(String(buffer, 0, read))
                                onUpdate(snapshot(), false)
                            }
                        }
                }
            }, "comput-stdout").apply { isDaemon = true }

            val stderrThread = Thread({
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(stderrPfd)
                        .bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(4096)
                            while (!stopRequested.get()) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                err.append(String(buffer, 0, read))
                                onUpdate(snapshot(), false)
                            }
                        }
                }
            }, "comput-stderr").apply { isDaemon = true }

            val startedAt = SystemClock.elapsedRealtime()
            stdoutThread.start()
            stderrThread.start()

            val finished = remote.waitForTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS.name)
            if (!finished) {
                // 超时或用户点了停止：远程进程和它的子进程一起干掉
                shutdown()
            }
            val cancelled = !finished && stopRequested.get()
            val timedOut = !finished && !cancelled
            val exitCode = if (finished) remote.exitValue() else 124 // 124 = timeout(1) 的约定

            stdoutThread.join(1500)
            stderrThread.join(1500)
            shutdown()

            val body = snapshot()
            val tail = when {
                cancelled -> cancelledMessage
                timedOut -> timeoutMessage
                else -> exitMessage(exitCode)
            }
            val text = (if (body.isBlank()) noOutputMessage else body) + "\n" + tail
            onUpdate(text, true)

            ComputResult(
                text = text,
                exitCode = exitCode,
                finished = finished,
                cancelled = cancelled,
                timedOut = timedOut,
                startedAt = startedAt,
                truncated = out.truncated || err.truncated,
            )
        } catch (e: Exception) {
            val text = "Shizuku: ${e.message ?: e.javaClass.simpleName}"
            onUpdate(text, true)
            ComputResult(
                text = text,
                exitCode = -1,
                finished = false,
                cancelled = false,
                timedOut = false,
                startedAt = SystemClock.elapsedRealtime(),
            )
        } finally {
            running.set(false)
            stopRequested.set(false)
        }
    }

    /** stdout 和 stderr 拼一起；stderr 用 `[E]` 前缀标出来（和上游同一个约定）。 */
    private fun composeOutput(stdout: String, stderr: String, truncated: Boolean): String {
        if (stdout.isBlank() && stderr.isBlank()) return ""
        return buildString {
            if (truncated) append("…(output truncated to the last 64 KB)\n")
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append("[E] ").append(stderr.trimEnd())
            }
        }
    }

    /**
     * 边走边截断的缓冲：**只留最后 [limit]** 个字符。
     *
     * 留尾巴而不是留开头 —— 命令报错的信息几乎总在末尾。
     * 双倍水位一次删一半，避免每来一个块都做一次大数组搬移。
     */
    private class StreamFilter(private val limit: Int) {

        private val lock = Any()
        private val builder = StringBuilder()

        /** 有没有真的丢掉过内容（UI 拿它决定要不要提示条数上限） */
        @Volatile
        var truncated: Boolean = false
            private set

        fun append(chunk: String) = synchronized(lock) {
            builder.append(chunk)
            if (builder.length > limit * 2) {
                builder.delete(0, builder.length - limit)
                truncated = true
            }
        }

        fun snapshot(): String = synchronized(lock) {
            if (builder.length > limit) builder.substring(builder.length - limit) else builder.toString()
        }
    }
}

/** 一次执行的结果。 */
data class ComputResult(
    val text: String,
    val exitCode: Int,
    val finished: Boolean,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val startedAt: Long,
    /** 输出有没有被 64KB 上限截断（UI 拿它决定要不要显示提示） */
    val truncated: Boolean = false,
)

// ============================================================================
// 屏幕
// ============================================================================

/**
 * Comput 控制台。
 *
 * 逻辑（shell 会话、宏、Gemini 解释）照搬 Shevery 的 `ComputScreen`，
 * UI 全部用本项目的提示页组件重写：`HintPage` / `HintCard` / `HintPrimaryButton`，
 * 配色走 `resolveHintPalette(context)`，所以 MD3 和玻璃两套风格都自动跟上。
 *
 * 输出区刻意用 `FontFamily.Monospace`：终端内容用比例字体对不齐 `ls -l` 这类列。
 */
@Composable
fun ComputScreen(
    state: ComputUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    output: String,
    history: List<String>,
    macros: List<ComputMacro>,
    macRotation: Int,
    callbacks: ComputCallbacks,
    /** 设置二级页 = true：顶部加返回行 + 「控制台设置」入口 */
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** 「控制台设置」入口：设置里的二级页和底栏 Tab 两个入口都要有，才能长得一样 */
    showSettingsEntry: Boolean = true,
) {
    val context = LocalContext.current
    var command by remember { mutableStateOf("") }
    var aiExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var macroDialog by remember { mutableStateOf<MacroDialogState?>(null) }
    // -1 = 不在历史里游走；否则是 history 里的下标
    var historyIndex by remember { mutableStateOf(-1) }

    val quickCommands = remember {
        listOf(
            "pm list packages -3",
            "dumpsys battery",
            "logcat -d -t 30",
            "settings get secure android_id",
            "df -h",
            "getprop ro.build.version.release",
        )
    }

    // 输出里的 stderr 前缀：给这些行换色，一眼看出哪些是报错
    val outputLines = remember(output, palette.style) {
        output.split('\n').filter { it.isNotEmpty() }
    }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {

        // 返回行（只有设置二级页需要）+「控制台设置」入口（两个入口都有）
        if (showBack || showSettingsEntry) {
            item {
                HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                    if (showBack) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onBack)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_back),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.accent,
                        )
                    }
                    if (showSettingsEntry) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenSettings)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_outline_settings_24),
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.comput_settings),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.onCard,
                        )
                    }
                }
            }
        }

        // ---------------- 命令输入 ----------------
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.comput_title),
                )

                OutlinedTextField(
                    value = command,
                    onValueChange = {
                        command = it
                        historyIndex = -1
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // 上下键翻历史：必须在 TextField 消费之前拦下来
                        .onPreviewKeyEvent { event -> handleHistoryKey(event, history, historyIndex) {
                            historyIndex = it.first
                            command = it.second
                        } },
                    singleLine = false,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.comput_command_label)) },
                    placeholder = { Text(stringResource(R.string.comput_command_placeholder)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    enabled = !state.isRunning,
                )

                if (history.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = palette.variant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.comput_history_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                }

                // 常用命令：一条命令一行太长，横向滚动比换行更好读
                HintSectionTitle(palette = palette, text = stringResource(R.string.comput_quick_commands))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickCommands.forEach { quick ->
                        FilterChip(
                            selected = false,
                            onClick = { command = quick },
                            enabled = !state.isRunning,
                            label = {
                                Text(
                                    quick,
                                    maxLines = 1,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            // 默认的 M3 chip 配色是按"不透明面板"设计的：压在玻璃卡 + 壁纸上
                            // 字会糊成一团。这里显式按当前配色板取色，保证读得清。
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = palette.onCard.copy(alpha = 0.10f),
                                labelColor = palette.onCard,
                                disabledContainerColor = palette.onCard.copy(alpha = 0.05f),
                                disabledLabelColor = palette.variant.copy(alpha = 0.6f),
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = !state.isRunning,
                                selected = false,
                                borderColor = palette.variant.copy(alpha = 0.45f),
                                disabledBorderColor = palette.variant.copy(alpha = 0.25f),
                            ),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HintPrimaryButton(
                        palette = palette,
                        text = if (state.isRunning) {
                            stringResource(R.string.comput_running)
                        } else {
                            stringResource(R.string.comput_run)
                        },
                        enabled = !state.isRunning && command.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = { callbacks.onRun(command) },
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.comput_history_title),
                        enabled = history.isNotEmpty(),
                        onClick = { historyExpanded = !historyExpanded },
                    )
                }

                if (historyExpanded && history.isNotEmpty()) {
                    HorizontalDivider(color = palette.outline.copy(alpha = 0.3f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        history.forEach { entry ->
                            TextButton(
                                onClick = {
                                    command = entry
                                    historyExpanded = false
                                    historyIndex = -1
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = entry,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 工具条 ----------------
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComputToolButton(
                        palette = palette,
                        icon = Icons.Rounded.Delete,
                        label = stringResource(R.string.comput_toolbar_clear),
                        enabled = true,
                        onClick = callbacks.onClear,
                    )
                    ComputToolButton(
                        palette = palette,
                        icon = Icons.Rounded.Stop,
                        label = stringResource(R.string.comput_toolbar_stop),
                        enabled = state.isRunning,
                        danger = true,
                        onClick = callbacks.onStop,
                    )
                    ComputToolButton(
                        palette = palette,
                        icon = Icons.Rounded.Terminal,
                        label = stringResource(R.string.comput_toolbar_copy),
                        enabled = output.isNotBlank(),
                        onClick = {
                            callbacks.onCopy(
                                context.getString(R.string.comput_output_title),
                                output,
                            )
                        },
                    )
                    ComputToolButton(
                        palette = palette,
                        icon = Icons.Rounded.AutoAwesome,
                        label = stringResource(R.string.comput_toolbar_ai),
                        contentDescription = stringResource(R.string.comput_toolbar_ai_desc),
                        enabled = state.aiKeyConfigured && output.isNotBlank() && !state.isExplaining,
                        onClick = { callbacks.onExplain() },
                    )
                }
            }
        }

        // ---------------- 输出 ----------------
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HintSectionTitle(palette = palette, text = stringResource(R.string.comput_output_title))
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = outputStatusLine(state, palette),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(state, palette),
                    )
                }

                if (output.isBlank()) {
                    Text(
                        text = stringResource(R.string.comput_output_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                    )
                } else {
                    // 输出可能很长：先限高再自己滚，页面整体还能往下走
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            outputLines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = outputLineColor(line, palette),
                                )
                            }
                        }
                    }
                    // 只有真的截断过才提示，否则每跑一条命令都挂一句废话
                    if (state.outputTruncated) {
                        Text(
                            text = stringResource(R.string.comput_output_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                }
            }
        }

        // ---------------- AI 解释 ----------------
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(3)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    HintSectionTitle(palette = palette, text = stringResource(R.string.comput_ai_title))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { aiExpanded = !aiExpanded }) {
                        Text(
                            text = stringResource(R.string.comput_ai_key_label),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (!state.aiKeyConfigured) {
                    HintNote(
                        palette = palette,
                        iconRes = R.drawable.ic_outline_info_24,
                        text = stringResource(R.string.comput_ai_key_missing),
                    )
                }

                HintPrimaryButton(
                    palette = palette,
                    text = if (state.isExplaining) {
                        stringResource(R.string.comput_ai_explaining)
                    } else {
                        stringResource(R.string.comput_ai_explain)
                    },
                    enabled = state.aiKeyConfigured && output.isNotBlank() && !state.isExplaining,
                    onClick = { callbacks.onExplain() },
                )

                if (state.isExplaining) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = palette.accent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.comput_ai_explaining),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                }

                if (state.aiExplanation.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = state.aiExplanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onCard,
                        )
                    }
                }

                if (aiExpanded) {
                    HorizontalDivider(color = palette.outline.copy(alpha = 0.3f))
                    var keyDraft by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.comput_ai_key_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(
                        text = if (state.aiKeyConfigured) {
                            stringResource(R.string.comput_ai_key_present)
                        } else {
                            stringResource(R.string.comput_ai_key_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HintPrimaryButton(
                            palette = palette,
                            text = stringResource(R.string.comput_ai_key_save),
                            enabled = keyDraft.isNotBlank(),
                            onClick = {
                                callbacks.onSaveApiKey(keyDraft.trim())
                                keyDraft = ""
                            },
                        )
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.comput_ai_key_clear),
                            enabled = state.aiKeyConfigured,
                            onClick = { callbacks.onSaveApiKey("") },
                        )
                    }
                }
            }
        }

        // ---------------- 宏 ----------------
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HintSectionTitle(palette = palette, text = stringResource(R.string.comput_macros_title))
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.comput_macros_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }

                if (macros.isEmpty()) {
                    Text(
                        text = stringResource(R.string.comput_macros_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                    )
                }

                // key 里带上名字 + 顺序：改名 / 替换命令后行内的编辑态不会串
                val edits = remember(macRotation) { mutableStateMapOf<String, String>() }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(macros, key = { it.name }) { macro ->
                        if (macro.commands.isEmpty()) return@items
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = macro.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.onCard,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = macro.commands.first(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.variant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { callbacks.onRunMacro(macro) },
                                enabled = !state.isRunning,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.comput_macro_run),
                                    tint = if (state.isRunning) palette.variant else palette.accent,
                                )
                            }
                            IconButton(onClick = {
                                edits[macro.name] = macro.name
                                macroDialog = MacroDialogState(macro.name, macro.commands.firstOrNull().orEmpty(), true)
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.comput_macro_rename),
                                    tint = palette.variant,
                                )
                            }
                            IconButton(onClick = { callbacks.onDeleteMacro(macro.name) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.comput_macro_delete),
                                    tint = palette.error,
                                )
                            }
                        }
                    }
                }

                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.comput_macro_add),
                    onClick = { macroDialog = MacroDialogState(command, command, false) },
                )
            }
        }

        // ---------------- 命令历史 ----------------
        if (history.isNotEmpty()) {
            item {
                HintCard(palette = palette, modifier = Modifier.itemEntrance(5)) {
                    HintSectionTitle(palette = palette, text = stringResource(R.string.comput_history_title))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        history.forEach { entry ->
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = palette.variant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    macroDialog?.let { dialog ->
        MacroEditDialog(
            palette = palette,
            state = dialog,
            currentCommand = command,
            existingNames = macros.map { it.name },
            onDismiss = { macroDialog = null },
            onConfirm = { name, cmd ->
                if (dialog.rename) {
                    callbacks.onRenameMacro(dialog.name, name)
                } else {
                    callbacks.onSaveMacro(name, cmd)
                }
                macroDialog = null
            },
        )
    }
}

// ============================================================================
// 小组件
// ============================================================================

/** 对话框正在编辑什么：新建还是改名（改名时命令不改，只换 key）。 */
private data class MacroDialogState(
    val name: String,
    val command: String,
    val rename: Boolean,
)

/** 工具条上的一个按钮：图标 + 下面一行小字（图标按钮光看图标猜不出「复制哪一份」）。 */
@Composable
private fun ComputToolButton(
    palette: HintPalette,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    contentDescription: String = label,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> palette.variant.copy(alpha = 0.4f)
                    danger -> palette.error
                    else -> palette.accent
                },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) palette.variant else palette.variant.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 新建 / 改名宏的对话框。两个字段都实时校验，不合法就不让保存。 */
@Composable
private fun MacroEditDialog(
    palette: HintPalette,
    state: MacroDialogState,
    currentCommand: String,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(state.name) }
    var cmd by remember { mutableStateOf(state.command) }
    val nameTaken = !state.rename && existingNames.any { it == name.trim() }
    val nameError = when {
        name.isBlank() -> stringResource(R.string.comput_macro_name_empty)
        nameTaken -> stringResource(R.string.comput_macro_name_taken)
        else -> null
    }
    val cmdError = if (!state.rename && cmd.isBlank()) {
        stringResource(R.string.comput_macro_command_empty)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (state.rename) {
                    stringResource(R.string.comput_macro_rename_title)
                } else {
                    stringResource(R.string.comput_macro_add)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = nameError != null,
                    label = { Text(stringResource(R.string.comput_macro_name_label)) },
                    supportingText = nameError?.let { { Text(it) } },
                )
                if (!state.rename) {
                    OutlinedTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        isError = cmdError != null,
                        label = { Text(stringResource(R.string.comput_macro_command_label)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        supportingText = cmdError?.let { { Text(it) } },
                    )
                    if (currentCommand.isNotBlank() && currentCommand != cmd) {
                        TextButton(onClick = { cmd = currentCommand }) {
                            Text(stringResource(R.string.comput_macro_use_current))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), cmd.trim()) },
                enabled = nameError == null && cmdError == null,
            ) {
                Text(stringResource(R.string.comput_macro_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

// ============================================================================
// 纯函数小工具
// ============================================================================

/** 上下键翻命令历史。返回 true 表示这次按键已经被我们吃掉了。 */
private fun handleHistoryKey(
    event: KeyEvent,
    history: List<String>,
    currentIndex: Int,
    onNavigate: (Pair<Int, String>) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || history.isEmpty()) return false
    val up = event.key == Key.DirectionUp
    val down = event.key == Key.DirectionDown
    if (!up && !down) return false
    if (up) {
        val next = if (currentIndex < 0) 0 else (currentIndex + 1).coerceAtMost(history.lastIndex)
        onNavigate(next to history[next])
    } else {
        if (currentIndex < 0) return true
        val next = currentIndex - 1
        // 走到最早一条再往下 = 退出历史浏览，输入框留空
        onNavigate(if (next < 0) -1 to "" else next to history[next])
    }
    return true
}

/** 输出区最后一行状态：跑没跑、退出码多少、耗时多久。 */
@Composable
private fun outputStatusLine(state: ComputUiState, palette: HintPalette): String = when {
    state.isRunning -> stringResource(R.string.comput_running)
    state.lastOutcome == ComputOutcome.NONE -> ""
    state.lastOutcome == ComputOutcome.CANCELLED -> stringResource(R.string.comput_output_cancelled)
    state.lastOutcome == ComputOutcome.TIMEOUT -> stringResource(R.string.comput_output_timeout)
    state.lastOutcome == ComputOutcome.OK -> buildString {
        append(stringResource(R.string.comput_output_exit_ok))
        if (state.lastElapsedMs > 0) append("  ${state.lastElapsedMs} ms")
    }
    else -> buildString {
        append(stringResource(R.string.comput_output_exit, state.lastExitCode))
        if (state.lastElapsedMs > 0) append("  ${state.lastElapsedMs} ms")
    }
}

private fun statusColor(state: ComputUiState, palette: HintPalette): Color = when (state.lastOutcome) {
    ComputOutcome.FAILED, ComputOutcome.TIMEOUT -> palette.error
    ComputOutcome.CANCELLED -> palette.variant
    ComputOutcome.OK -> palette.accent
    ComputOutcome.NONE -> palette.variant
}

/** stderr 行和我们的状态行换色，其余用正文色。 */
private fun outputLineColor(line: String, palette: HintPalette): Color = when {
    line.startsWith("[E]") -> palette.error
    line.startsWith("\u2014") -> palette.variant // 我们自己补的「— 执行结束 —」那行
    line.startsWith(">") -> palette.accent // 回显的命令
    else -> palette.onCard
}

/** 把内容写进系统剪贴板（工具条上的「复制输出」用）。 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 短提示：控制台的反馈一律走 toast，不打断正在看的输出。 */
fun toast(context: Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}
