package moe.shizuku.manager.module.catalog

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.discovery.ModuleDiscovery
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.itemEntrance
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模块目录（照搬 Shevery「从 GitHub 找模块」的思路，界面用本项目风格重写）。
 *
 * 流程：搜索 → 校验（仓库里真有合法 `module.prop`）→ 下载 ZIP 到 cache →
 * 交给 [AdbModuleManager.install] 安装（那一套限额和日志都在安装器里）。
 *
 * 网络是阻塞式的，全部在 IO 线程；没网时给一句人话，不弹出一堆异常。
 */
@Composable
fun ModuleCatalogScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ModuleDiscovery.DiscoveredModule>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    fun search() {
        if (loading) return
        loading = true
        message = null
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { ModuleDiscovery.search(query) }.getOrElse { emptyList() }
            }
            results = found
            searched = true
            loading = false
            if (found.isEmpty()) {
                message = context.getString(
                    if (moe.shizuku.manager.utils.NetworkUtils.isOnline(context)) {
                        R.string.catalog_empty
                    } else {
                        R.string.update_no_network
                    },
                )
            }
        }
    }

    fun install(module: ModuleDiscovery.DiscoveredModule) {
        val url = module.zipUrl ?: return
        if (installing != null) return
        installing = module.repoFullName
        message = null
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(context.cacheDir, "catalog-${module.moduleId}.zip")
                    if (!download(url, file)) error("下载失败")
                    val ok = AdbModuleManager.install(context, Uri.fromFile(file))
                    file.delete()
                    if (ok.id.isEmpty()) error("安装失败")
                    null
                }.getOrElse { it.message ?: "安装失败" }
            }
            installing = null
            message = error ?: context.getString(R.string.catalog_installed, module.moduleName)
        }
    }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                Row(
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

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.catalog_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.variant.copy(alpha = 0.4f),
                        focusedLabelColor = palette.accent,
                        unfocusedLabelColor = palette.variant,
                        focusedTextColor = palette.onCard,
                        unfocusedTextColor = palette.onCard,
                    ),
                )
                Spacer(Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HintPrimaryButton(
                        palette = palette,
                        text = stringResource(
                            if (loading) R.string.catalog_searching else R.string.catalog_search,
                        ),
                        modifier = Modifier.weight(1f),
                        enabled = !loading,
                        onClick = { search() },
                    )
                }
                if (loading) {
                    Spacer(Modifier.padding(top = 10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = palette.accent,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        message?.let { text ->
            item {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_info_24,
                    text = text,
                    modifier = Modifier.itemEntrance(1),
                )
            }
        }

        if (results.isNotEmpty()) {
            item {
                HintSectionTitle(
                    text = stringResource(R.string.catalog_results, results.size),
                    palette = palette,
                    modifier = Modifier.itemEntrance(1),
                )
            }
        }

        itemsIndexedCompat(results) { index, module ->
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2 + index)) {
                Text(
                    text = module.moduleName,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onCard,
                )
                Text(
                    text = buildString {
                        append(module.moduleId)
                        module.version?.let { append(" · v$it") }
                        append(" · ★ ${module.stars}")
                        module.author?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                module.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onCard,
                    )
                }
                Text(
                    text = module.repoFullName,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant.copy(alpha = 0.8f),
                )
                Spacer(Modifier.padding(top = 6.dp))
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(
                        if (installing == module.repoFullName) {
                            R.string.catalog_installing
                        } else {
                            R.string.catalog_install
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = installing == null,
                    onClick = { install(module) },
                )
            }
        }

        if (searched && results.isEmpty() && !loading) {
            item {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_help_outline_24dp,
                    text = stringResource(R.string.catalog_tip),
                    modifier = Modifier.itemEntrance(1),
                )
            }
        }
    }
}

/** 简单的列表包装：避免和 LazyColumn 的 items 扩展名冲突，也省得引 itemsIndexed 重载 */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    list: List<ModuleDiscovery.DiscoveredModule>,
    crossinline content: @Composable (Int, ModuleDiscovery.DiscoveredModule) -> Unit,
) {
    items(list.size) { index -> content(index, list[index]) }
}

/** 下载到文件；返回是否成功（带 GitHub 的 UA，超时 20s） */
private fun download(url: String, target: File): Boolean {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Shizako-ModuleCatalog/1.0")
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        if (connection.responseCode !in 200..299) return false
        connection.inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.length() > 0
    } catch (e: Exception) {
        false
    } finally {
        try { connection?.disconnect() } catch (_: Exception) {}
    }
}
