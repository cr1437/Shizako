package moe.shizuku.manager.activation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ui.hint.HintPalette
import java.net.ConnectException

/**
 * 应用内配对对话框（照搬 Shevery 的 AdbPairDialog 设计）。
 *
 * 现代 Android 要求「使用配对码配对设备」的系统对话框在配对期间保持可见，
 * 所以正确姿势是分屏：左边 Shizako、右边系统配对对话框，两者同屏 ——
 * 这里自动发现配对端口（也可手输），输入 6 位配对码后直连 127.0.0.1 完成配对。
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
    var portText by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var portError by remember { mutableStateOf<String?>(null) }
    var pairingCodeError by remember { mutableStateOf<String?>(null) }
    var isPairing by remember { mutableStateOf(false) }

    // 只有在配对窗口（分屏/多窗口）里才启动 mDNS 搜索并自动回填端口
    DisposableEffect(inPairingWindow) {
        if (!inPairingWindow) return@DisposableEffect onDispose { }
        val adbMdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { port ->
            discoveredPort = port
            if (port in 1..65535) {
                portText = port.toString()
                portError = null
            }
        }
        adbMdns.start()
        onDispose {
            adbMdns.stop()
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
                    discoveredPort !in 1..65535 -> stringResource(R.string.dialog_adb_pairing_discovery)
                    else -> stringResource(R.string.dialog_adb_pairing_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = palette.onCard,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !inPairingWindow -> {
                        Text(
                            text = stringResource(R.string.adb_pairing_requires_multi_window_reason),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.variant,
                        )
                    }
                    discoveredPort !in 1..65535 -> {
                        Text(
                            text = stringResource(R.string.dialog_adb_pairing_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onCard,
                        )
                        CircularProgressIndicator(
                            color = palette.accent,
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.CenterHorizontally),
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = {
                                pairingCode = it.filter(Char::isDigit).take(6)
                                pairingCodeError = null
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
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.dialog_adb_port)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = portError != null,
                            supportingText = portError?.let { error ->
                                { Text(error) }
                            },
                        )
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
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    !inPairingWindow -> {
                        Button(onClick = onDismissRequest) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                    discoveredPort !in 1..65535 -> {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                }
                            },
                        ) {
                            Text(stringResource(R.string.activation_wadb_action_open_settings))
                        }
                    }
                    else -> {
                        Button(
                            enabled = !isPairing && pairingCode.length == 6 && portText.isNotBlank(),
                            onClick = {
                                val port = portText.toIntOrNull() ?: -1
                                if (port !in 1..65535) {
                                    portError = context.getString(R.string.dialog_adb_invalid_port)
                                    return@Button
                                }
                                isPairing = true
                                scope.launch(Dispatchers.IO) {
                                    val host = "127.0.0.1"
                                    val key = try {
                                        AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
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
                                    val result = runCatching {
                                        AdbPairingClient(host, port, pairingCode, key).start()
                                    }
                                    withContext(Dispatchers.Main) {
                                        isPairing = false
                                        result.onSuccess { success ->
                                            if (success) {
                                                onPairSuccess()
                                            }
                                        }.onFailure { err ->
                                            when (err) {
                                                is ConnectException -> {
                                                    portError = context.getString(R.string.cannot_connect_port)
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
                                                else -> {
                                                    portError = err.message ?: context.getString(R.string.cannot_connect_port)
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