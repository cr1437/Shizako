package moe.shizuku.manager.activation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbPairingFailedException
import moe.shizuku.manager.adb.AdbPairingState
import moe.shizuku.manager.adb.DeferredAdbKeyStore
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ui.hint.HintPalette
import java.net.ConnectException
import javax.net.ssl.SSLException

/**
 * 应用内配对对话框（照搬 Shevery 的 AdbPairDialog 设计）。
 *
 * 现代 Android 要求「使用配对码配对设备」的系统对话框在配对期间保持可见，
 * 所以正确姿势是分屏：左边 Shizako、右边系统配对对话框，两者同屏 ——
 * 这里自动发现配对端口，输入 6 位配对码后连接本机回环完成配对（回环连不上时
 * 自动改用本机局域网地址，见 [AdbPairingClient] 的候选地址说明）。
 *
 * 端口**任何时候都能手输**（不只是自动发现之后）：mDNS 用的是组播，GrapheneOS 开着 VPN
 * 时组播不会送进 App，华为 / 鸿蒙部分机型同样拦 —— 这类设备上自动发现必然失败，
 * 以前对话框会一直转圈、连输入框都不渲染，用户只能干看着。现在自动发现只负责「预填」，
 * 6 秒没结果就转为手动输入引导。
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun AdbPairDialog(
    palette: HintPalette,
    inPairingWindow: Boolean,
    onDismissRequest: () -> Unit,
    onPairSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var discoveredPort by remember { mutableIntStateOf(-1) }
    /**
     * 顺带探到的「连接端口」（`_adb-tls-connect._tcp`）。
     *
     * 用途只有一个：用户把无线调试页面顶部那个「IP 地址和端口」填进来时**当场拦下**。
     * 填错的表现很隐蔽 —— TCP 连得上、TLS 握手却报证书校验失败（adbd 的连接服务会校验
     * 客户端证书，而配对服务明确接受任意证书），看起来像「配对码错了」或「服务坏了」，
     * 光靠报错没法让用户想到端口填错。
     */
    var discoveredConnectPort by remember { mutableIntStateOf(-1) }
    /**
     * 配对服务是否已经**停止监听**。
     *
     * 现场日志（Pixel 9 Pro / Android 17）里最常见的失败不是证书、也不是端口填错：
     * mDNS 报出端口时 `isPortInUse` 是通过的（那一刻确实有服务在听），
     * 但用户输完六位配对码再点配对，回环与局域网都变成 ECONNREFUSED ——
     * 系统「使用配对码配对设备」那个对话框一旦不再可见，配对服务就被回收了。
     * 以前界面只会说「无法连接」，于是用户对着一个死端口反复重试。
     */
    var serviceGone by remember { mutableStateOf(false) }
    var serviceGonePort by remember { mutableIntStateOf(-1) }
    var portText by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf<String?>(null) }
    var pairingCodeError by remember { mutableStateOf<String?>(null) }
    /**
     * 与具体输入框无关的失败原因（握手断了、协议数据异常……）。
     *
     * 这些原因挂在端口框下面会误导用户去改端口，所以单独一块展示。
     */
    var generalError by remember { mutableStateOf<String?>(null) }
    var isPairing by remember { mutableStateOf(false) }
    /** mDNS 是否还在搜索。只影响文案与转圈：端口框**任何情况下**都能手输。 */
    var scanning by remember { mutableStateOf(false) }
    /**
     * 搜索超时（mDNS 一直没回报端口）。
     *
     * 这时不能再让用户对着转圈干等：mDNS 用的是组播，任何「防泄漏」策略都可能把它掐掉 ——
     * GrapheneOS 开着 VPN 时组播不会送进 App，华为/鸿蒙部分机型同样拦。
     * 这些设备上唯一的出路就是手输端口，所以超时后要明确引导。
     */
    var scanTimedOut by remember { mutableStateOf(false) }

    /** 跳系统「开发者选项」：手输端口时要照着设备上「无线调试」页显示的端口填。 */
    fun openDeveloperSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    // 只有在配对窗口（分屏/多窗口）里才启动 mDNS 搜索并自动回填端口
    DisposableEffect(inPairingWindow) {
        if (!inPairingWindow) return@DisposableEffect onDispose { }
        scanning = true
        scanTimedOut = false
        discoveredPort = -1
        val adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { port ->
            discoveredPort = port
            if (port in 1..65535) {
                portText = port.toString()
                portError = null
                scanning = false
                scanTimedOut = false
            }
        }
        adbMdns.start()
        // 同时探一遍连接端口（不参与任何流程，只用来在用户填错时当场点出来）
        val connectMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port in 1..65535) discoveredConnectPort = port
        }
        connectMdns.start()
        onDispose {
            scanning = false
            adbMdns.stop()
            runCatching { connectMdns.stop() }
        }
    }

    // 搜索计时：6 秒还没结果就认定 mDNS 被拦，切到「手动输入」的引导文案
    LaunchedEffect(inPairingWindow, discoveredPort, scanTimedOut) {
        if (!inPairingWindow || discoveredPort in 1..65535 || scanTimedOut) return@LaunchedEffect
        delay(6_000L)
        if (discoveredPort !in 1..65535) {
            scanning = false
            scanTimedOut = true
        }
    }

    /**
     * 盯着配对服务还在不在。
     *
     * 端口是系统对话框打开时才临时开的，对话框一不可见它就被回收；而用户填六位配对码
     * 要花几秒到十几秒。现场日志里正是这个窗口期把服务等没了，然后连上去就是
     * ECONNREFUSED。所以这里每 1.5 秒探一次端口占用，一旦没了立刻在对话框里说明，
     * 并且禁用配对按钮 —— 让用户去重开系统对话框，而不是对着死端口重试。
     */
    LaunchedEffect(inPairingWindow, discoveredPort) {
        serviceGone = false
        if (!inPairingWindow || discoveredPort !in 1..65535) return@LaunchedEffect
        while (true) {
            val alive = withContext(Dispatchers.IO) { AdbMdns.isListening(discoveredPort) }
            if (!alive) {
                serviceGonePort = discoveredPort
                serviceGone = true
                break
            }
            delay(1_500L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_adb_24dp),
                contentDescription = null,
                tint = palette.accent,
            )
        },
        title = {
            Text(
                text = when {
                    !inPairingWindow -> stringResource(R.string.adb_pairing_requires_multi_window)
                    discoveredPort in 1..65535 -> stringResource(R.string.dialog_adb_pairing_title)
                    scanning -> stringResource(R.string.dialog_adb_pairing_discovery)
                    // 搜索超时 / 失败：不再卡住，标题直接告诉用户可以手输
                    else -> stringResource(R.string.dialog_adb_pairing_manual_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = palette.onCard,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!inPairingWindow) {
                    Text(
                        text = stringResource(R.string.adb_pairing_requires_multi_window_reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                    )
                } else {
                    // 搜索中：给个转圈 + 步骤说明，但下面照样能立刻手输
                    if (scanning && discoveredPort !in 1..65535) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color = palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.dialog_adb_pairing_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onCard,
                            )
                        }
                    }
                    // 超时/失败：解释 mDNS 为什么发现不到，下面照样能手输
                    if (scanTimedOut) {
                        Text(
                            text = stringResource(R.string.dialog_adb_pairing_mdns_timeout),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                        )
                    }
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = {
                            pairingCode = it.filter(Char::isDigit).take(6)
                            pairingCodeError = null
                            generalError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dialog_adb_pairing_paring_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = pairingCodeError != null,
                        supportingText = pairingCodeError?.let { error ->
                            { Text(error) }
                        },
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter(Char::isDigit).take(5)
                            portError = null
                            generalError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dialog_adb_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = portError != null,
                        supportingText = {
                            Text(
                                portError ?: if (discoveredPort in 1..65535) {
                                    stringResource(R.string.dialog_adb_port_auto_filled)
                                } else {
                                    stringResource(R.string.dialog_adb_port_manual_hint)
                                }
                            )
                        },
                    )
                    // 配对服务已经不在监听（端口空转）：这是现场日志里最常见的失败 ——
                    // mDNS 报出端口时服务还在，用户输完配对码它已经停了。
                    // 直接说清楚，别让他对着一个死端口反复点。
                    if (serviceGone && serviceGonePort in 1..65535) {
                        Text(
                            text = context.getString(R.string.pairing_service_stopped, serviceGonePort),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                        )
                    }
                    generalError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.error,
                        )
                    }
                    if (isPairing) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!inPairingWindow) {
                Button(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.ok))
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 端口没被自动发现：给一条去「开发者选项」看端口号的路
                    if (discoveredPort !in 1..65535) {
                        TextButton(onClick = { openDeveloperSettings() }) {
                            Text(stringResource(R.string.activation_wadb_action_open_settings))
                        }
                    }
                    Button(
                        enabled = !isPairing && !serviceGone &&
                            pairingCode.length == 6 && portText.isNotBlank(),
                        onClick = {
                            val port = portText.toIntOrNull() ?: -1
                            if (port !in 1..65535) {
                                portError = context.getString(R.string.dialog_adb_invalid_port)
                                return@Button
                            }
                            // 填成「连接端口」时当场拦下：那种情况下 TCP 能连上、TLS 握手报
                            // 证书校验失败，报错看起来完全不像端口问题，用户会一直怀疑配对码。
                            if (discoveredConnectPort in 1..65535 && port == discoveredConnectPort) {
                                portError = context.getString(R.string.pairing_port_is_connect_port)
                                if (discoveredPort in 1..65535 && discoveredPort != port) {
                                    // 配对端口已经探到了就直接换上去，省得用户再找
                                    portText = discoveredPort.toString()
                                }
                                return@Button
                            }
                            isPairing = true
                            generalError = null
                            // 进 IO 之前把候选端口取好（Compose 状态别在别的线程上读）
                            val fallbackPort = discoveredPort
                            scope.launch(Dispatchers.IO) {
                                // 候选地址：回环优先，回环连不上时改用本机局域网地址兜底
                                // （部分设备/ROM 上应用连不上自己的 127.0.0.1，见 AdbPairingClient 类注释）
                                val hosts = AdbPairingClient.candidateHosts(context)
                                // 配对期间的密钥只留在内存里，配对真的成功才落盘：
                                // 一次失败的尝试不该在本地留下一把 adbd 不认识的密钥，
                                // 否则激活页会把它当成「已配对」的证据（用户根本没配上）。
                                val preferences = ShizukuSettings.getPreferences()
                                val keyStore = DeferredAdbKeyStore(PreferenceAdbKeyStore(preferences))
                                val key = try {
                                    AdbKey(keyStore, "shizuku")
                                } catch (e: Throwable) {
                                    withContext(Dispatchers.Main) {
                                        isPairing = false
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.adb_error_key_store),
                                            Toast.LENGTH_LONG,
                                        ).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
                                    }
                                    return@launch
                                }
                                // use{} 保证 socket 与原生配对上下文一定被回收（start() 内部也有
                                // finally 清理，这里再套一层是防止将来有人改动 start() 的清理逻辑）。
                                // 原先这里既没 use 也没 close：每次配对尝试（含失败重试）都会泄漏
                                // 两个 fd + 一块持有 SPAKE2 密钥的原生内存。
                                val result = runCatching {
                                    AdbPairingClient(hosts, port, pairingCode, key).use { client ->
                                        client.start()
                                    }
                                }.recoverCatching { first ->
                                    // 自动恢复：填的端口**握手阶段**就失败，而 mDNS 另外探到了一个
                                    // 配对端口 —— 说明填的这个多半是无线调试页顶部的连接端口
                                    // （adbd 的连接服务会校验客户端证书，配对服务不会）。
                                    // 直接换探到的那个再试一次，省得用户自己回来改。
                                    // 只在 SSLException 时重试：配对码错误、协议错误的重试没有意义。
                                    if (
                                        first is SSLException &&
                                        fallbackPort in 1..65535 &&
                                        fallbackPort != port
                                    ) {
                                        Log.w(
                                            "AdbPairDialog",
                                            "handshake failed on $port, retrying with discovered pairing port $fallbackPort",
                                        )
                                        AdbPairingClient(hosts, fallbackPort, pairingCode, key).use { client ->
                                            client.start()
                                        }
                                    } else {
                                        throw first
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    isPairing = false
                                    result.onSuccess {
                                        // 配对真的成功了：这一刻才把密钥落盘并记一笔，
                                        // 界面上的「已配对」从此有据可依
                                        keyStore.commit()
                                        AdbPairingState.markPaired(preferences)
                                        onPairSuccess()
                                    }.onFailure { err ->
                                        // 附上「端口 + 异常类型」：让用户截图就能说明问题，
                                        // 不用再去捞 logcat。异常类型是判断阶段的关键
                                        // （SSLException = 握手阶段；AdbPairingFailed = 协议阶段）。
                                        fun detail(base: String): String =
                                            base + "\n\n[" + port + " · " + err.javaClass.simpleName +
                                                (err.message?.take(80)?.let { ": $it" } ?: "") + "]"

                                        when (err) {
                                            is ConnectException -> {
                                                portError = detail(context.getString(R.string.cannot_connect_port))
                                                // 连接被拒 = 这个端口上没人监听。把「配对服务可能已经停了」
                                                // 直接摆出来，否则用户只会以为是端口数字打错。
                                                serviceGonePort = port
                                                serviceGone = true
                                            }
                                            is AdbInvalidPairingCodeException -> {
                                                pairingCodeError = context.getString(R.string.paring_code_is_wrong)
                                            }
                                            is AdbKeyException -> {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.adb_error_key_store),
                                                    Toast.LENGTH_LONG,
                                                ).apply { setGravity(Gravity.CENTER, 0, 0) }.show()
                                            }
                                            // 加密连接或协议层断了：跟端口号、配对码都无关，
                                            // 单独整块提示，别让用户白白折腾输入框
                                            is SSLException -> {
                                                generalError = detail(context.getString(R.string.pairing_handshake_failed))
                                            }
                                            is AdbPairingFailedException -> {
                                                generalError = detail(context.getString(R.string.pairing_failed_protocol))
                                            }
                                            else -> {
                                                generalError = detail(
                                                    err.message ?: context.getString(R.string.pairing_failed_unknown)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        },
        dismissButton = {
            if (inPairingWindow) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** 从 Compose 的 LocalContext 里找宿主 Activity（判断分屏状态用）。 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}