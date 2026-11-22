package moe.shizuku.manager.installer

import android.os.Bundle
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.authorization.AuthorizationManager
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
import moe.shizuku.manager.utils.AppSignatures
import moe.shizuku.manager.utils.ShizukuState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 →「安装」→「信任的安装来源」（规格 F1）。
 *
 * 两个添加入口：
 * - **从已授权应用添加**：列出当前被授予 Shizuku 权限的应用（[AuthorizationManager.getPackages]），
 *   点一个 → 二次确认 → 落库；
 * - **手动添加包名**：给「已授权列表里看不到」的场景保底（shell/ADB 调用、或应用没走
 *   Shizuku 授权流程）。同样按**当前安装信息**取 uid + 签名，签名读不到就拒绝添加。
 *
 * 列表行只显示签名前 16 位：完整摘要没有可读性，用户核对应用靠应用名 + 包名。
 */
class TrustedInstallersActivity : AppActivity() {

    private lateinit var shell: FragmentSubPageBinding

    private val entries = mutableStateOf<List<TrustedInstaller>>(emptyList())
    private val labels = mutableStateOf<Map<String, String>>(emptyMap())
    private val authorized = mutableStateOf<List<TrustedCandidate>>(emptyList())
    private val loadingAuthorized = mutableStateOf(false)
    private val shizukuRunning = mutableStateOf(false)
    private val errorText = mutableStateOf<String?>(null)

    private val listState = LazyListState()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shell = FragmentSubPageBinding.inflate(layoutInflater)
        setContentView(shell.root)
        shell.toolbar.title = getString(R.string.installer_trust_title)
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
            val palette = remember(style) { resolveHintPalette(this@TrustedInstallersActivity) }
            TrustScreen(
                palette = palette,
                listState = listState,
                onCollapsedChange = { expanded -> shell.appBar.setExpanded(expanded, true) },
                entries = entries.value,
                labels = labels.value,
                authorized = authorized.value,
                loadingAuthorized = loadingAuthorized.value,
                shizukuRunning = shizukuRunning.value,
                timeFormat = timeFormat,
                errorText = errorText.value,
                onLoadAuthorized = ::loadAuthorized,
                onAdd = ::add,
                onRemove = ::remove,
                onError = { errorText.value = it },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEntries()
        shizukuRunning.value = ShizukuState.safePing()
    }

    /** 应用名要在后台查（`PackageManager.getApplicationLabel` 是同步 IPC），Compose 里只读映射。 */
    private fun refreshEntries() {
        val list = TrustedInstallerStore.all(this)
        entries.value = list
        Thread({
            val map = list.associate { it.packageName to InstallCaller.labelOf(this, it.packageName) }
            runOnUiThread { labels.value = map }
        }, "trusted-labels").start()
    }

    /** 读已授权应用。`AuthorizationManager` 走 binder，必须离开主线程。 */
    private fun loadAuthorized() {
        if (loadingAuthorized.value) return
        loadingAuthorized.value = true
        errorText.value = null
        Thread({
            val result = try {
                AuthorizationManager.getPackages()
                    .filter { info ->
                        val uid = info.applicationInfo?.uid ?: return@filter false
                        AuthorizationManager.granted(info.packageName, uid)
                    }
                    .mapNotNull { info ->
                        val uid = info.applicationInfo?.uid ?: return@mapNotNull null
                        TrustedCandidate(
                            packageName = info.packageName,
                            uid = uid,
                            label = InstallCaller.labelOf(this, info.packageName),
                            signatureHash = AppSignatures.packageHash(this, info.packageName),
                        )
                    }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
            } catch (t: Throwable) {
                errorText.value = t.message ?: t.javaClass.simpleName
                emptyList()
            }
            runOnUiThread {
                authorized.value = result
                loadingAuthorized.value = false
            }
        }, "trusted-authorized").start()
    }

    private fun add(packageName: String, note: String?, expectedUid: Int?) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            errorText.value = getString(R.string.installer_trust_cannot_resolve)
            return
        }
        Thread({
            val entry = TrustedInstallerStore.fromCurrentPackage(this, pkg, note)
            val error = when {
                entry == null -> getString(R.string.installer_trust_cannot_resolve)
                expectedUid != null && entry.uid != expectedUid ->
                    getString(R.string.installer_trust_uid_changed)
                else -> null
            }
            if (error != null || entry == null) {
                runOnUiThread { errorText.value = error }
                return@Thread
            }
            TrustedInstallerStore.put(this, entry)
            runOnUiThread {
                errorText.value = null
                refreshEntries()
            }
        }, "trusted-add").start()
    }

    private fun remove(entry: TrustedInstaller) {
        TrustedInstallerStore.remove(this, entry.packageName)
        refreshEntries()
    }
}

/** 「从已授权应用添加」列表里的一项。 */
data class TrustedCandidate(
    val packageName: String,
    val uid: Int,
    val label: String,
    val signatureHash: String?,
)

@Composable
private fun TrustScreen(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    entries: List<TrustedInstaller>,
    labels: Map<String, String>,
    authorized: List<TrustedCandidate>,
    loadingAuthorized: Boolean,
    shizukuRunning: Boolean,
    timeFormat: SimpleDateFormat,
    errorText: String?,
    onLoadAuthorized: () -> Unit,
    onAdd: (String, String?, Int?) -> Unit,
    onRemove: (TrustedInstaller) -> Unit,
    onError: (String?) -> Unit,
) {
    var showAuthorized by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<TrustedCandidate?>(null) }
    var pendingRemove by remember { mutableStateOf<TrustedInstaller?>(null) }
    var manualInput by remember { mutableStateOf("") }

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintNote(
                palette = palette,
                iconRes = R.drawable.ic_warning_24,
                text = stringResource(R.string.installer_trust_note),
                secondary = stringResource(R.string.installer_denied_default_policy),
                modifier = Modifier.itemEntrance(0),
            )
        }
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(palette = palette, text = stringResource(R.string.installer_trust_add_section))
                HintPrimaryButton(
                    palette = palette,
                    text = if (loadingAuthorized) {
                        stringResource(R.string.installer_trust_loading)
                    } else {
                        stringResource(R.string.installer_trust_add_authorized)
                    },
                    enabled = shizukuRunning && !loadingAuthorized,
                    onClick = {
                        showAuthorized = true
                        onLoadAuthorized()
                    },
                )
                if (!shizukuRunning) {
                    Text(
                        text = stringResource(R.string.installer_trust_need_service),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.installer_trust_add_manual),
                    onClick = {
                        manualInput = ""
                        onError(null)
                        showManual = true
                    },
                )
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                    )
                }
            }
        }
        item {
            HintSectionTitle(
                palette = palette,
                text = stringResource(R.string.installer_trust_count, entries.size),
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        if (entries.isEmpty()) {
            item {
                HintCard(palette = palette) {
                    Text(
                        text = stringResource(R.string.installer_trust_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                    )
                }
            }
        }
        items(entries, key = { it.packageName }) { entry ->
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = labels[entry.packageName] ?: entry.packageName,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.onCard,
                        )
                        Text(
                            text = entry.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                        )
                    }
                    TextButton(onClick = { pendingRemove = entry }) {
                        Text(stringResource(R.string.installer_trust_remove))
                    }
                }
                HorizontalDivider(color = palette.variant.copy(alpha = 0.2f))
                Text(
                    text = stringResource(
                        R.string.installer_trust_detail,
                        entry.uid,
                        InstallCaller.shortSignature(entry.signatureHash),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Text(
                    text = stringResource(
                        R.string.installer_trust_times,
                        timeFormat.format(Date(entry.addedAt)),
                        entry.lastUsedAt?.let { timeFormat.format(Date(it)) }
                            ?: stringResource(R.string.installer_trust_never),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                if (!entry.note.isNullOrBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onCard,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (showAuthorized) {
        AlertDialog(
            onDismissRequest = { showAuthorized = false },
            title = { Text(stringResource(R.string.installer_trust_add_authorized)) },
            text = {
                if (loadingAuthorized) {
                    Text(stringResource(R.string.installer_trust_loading))
                } else if (authorized.isEmpty()) {
                    Text(stringResource(R.string.installer_trust_authorized_empty))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        authorized.forEach { candidate ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingAdd = candidate
                                        showAuthorized = false
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = candidate.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.onCard,
                                )
                                Text(
                                    text = "${candidate.packageName} · uid ${candidate.uid}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.variant,
                                )
                                if (candidate.signatureHash == null) {
                                    Text(
                                        text = stringResource(R.string.installer_trust_cannot_resolve),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.error,
                                    )
                                }
                            }
                            HorizontalDivider(color = palette.variant.copy(alpha = 0.15f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAuthorized = false }) {
                    Text(stringResource(R.string.installer_close))
                }
            },
        )
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text(stringResource(R.string.installer_trust_add_manual)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        label = { Text(stringResource(R.string.installer_trust_package_hint)) },
                        singleLine = true,
                    )
                    if (errorText != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManual = false
                        onAdd(manualInput, null, null)
                    },
                ) {
                    Text(stringResource(R.string.installer_trust_add_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false }) {
                    Text(stringResource(R.string.batch_action_cancel))
                }
            },
        )
    }

    val candidate = pendingAdd
    if (candidate != null) {
        AlertDialog(
            onDismissRequest = { pendingAdd = null },
            title = { Text(stringResource(R.string.installer_trust_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.installer_trust_confirm_message,
                        candidate.label,
                        candidate.packageName,
                        InstallCaller.shortSignature(candidate.signatureHash),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAdd = null
                        onAdd(candidate.packageName, null, candidate.uid)
                    },
                ) {
                    Text(stringResource(R.string.installer_trust_add_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAdd = null }) {
                    Text(stringResource(R.string.batch_action_cancel))
                }
            },
        )
    }

    val removing = pendingRemove
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.installer_trust_remove)) },
            text = { Text(stringResource(R.string.installer_trust_remove_message, removing.packageName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        onRemove(removing)
                    },
                ) {
                    Text(stringResource(R.string.installer_trust_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.batch_action_cancel))
                }
            },
        )
    }
}
