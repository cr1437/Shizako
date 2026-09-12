package moe.shizuku.manager.dhizuku

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.utils.AppIconCache

/** 一个已授权的 Dhizuku 应用。 */
data class DhizukuApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val info: ApplicationInfo?,
)

/**
 * 「被调教的小可爱们」的**第二页**：Dhizuku（设备所有者）授权管理。
 *
 * 原来这块在设置/工具箱里（`DhizukuManageFragment` 那一套），现在归到应用这一栏，
 * 和「Shizako 授权应用」并排两页：都是"哪个应用能借我的特权"，放一起才顺。
 */
@Composable
fun DhizukuAppsScreen(
    palette: HintPalette,
    listState: LazyListState,
    deviceOwner: Boolean,
    apps: List<DhizukuApp>,
    activating: Boolean,
    onRevoke: (DhizukuApp) -> Unit,
    onRefresh: () -> Unit,
    onActivate: () -> Unit,
    onViewCommand: () -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
) {
    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            Box(modifier = Modifier.animateItem().itemEntrance(0)) {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_dhizuku_24dp,
                    text = stringResource(
                        if (deviceOwner) R.string.dhizuku_device_owner_enabled
                        else R.string.dhizuku_device_owner_disabled
                    ),
                    secondary = stringResource(R.string.dhizuku_device_owner_desc),
                )
            }
        }

        if (!deviceOwner) {
            item {
                Box(modifier = Modifier.animateItem().itemEntrance(1)) {
                    HintCard(palette = palette) {
                        HintPrimaryButton(
                            palette = palette,
                            text = stringResource(
                                if (activating) R.string.activation_dhizuku_activating
                                else R.string.activation_method_action_activate
                            ),
                            enabled = !activating,
                            onClick = onActivate,
                        )
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.activation_method_action_view_command),
                            onClick = onViewCommand,
                        )
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.animateItem().itemEntrance(2)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.dhizuku_manage_title),
                )
            }
        }

        if (apps.isEmpty()) {
            item {
                Box(modifier = Modifier.animateItem().itemEntrance(2)) {
                    HintCard(palette = palette) {
                        Text(
                            text = stringResource(R.string.dhizuku_manage_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onCard,
                        )
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.dhizuku_refresh),
                            onClick = onRefresh,
                        )
                    }
                }
            }
        } else {
            items(apps, key = { it.uid }) { app ->
                Box(modifier = Modifier.animateItem().itemEntrance(3)) {
                    HintCard(palette = palette) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DhizukuIcon(app)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.onCard,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                    color = palette.variant,
                                )
                            }
                        }
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.dhizuku_revoke_action),
                            onClick = { onRevoke(app) },
                        )
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.animateItem().itemEntrance(4)) {
                HintCard(palette = palette) {
                    HintSectionTitle(
                        palette = palette,
                        text = stringResource(R.string.dhizuku_what_title),
                    )
                    Text(
                        text = stringResource(R.string.dhizuku_what_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onCard,
                    )
                }
            }
        }
    }
}

@Composable
private fun DhizukuIcon(app: DhizukuApp) {
    val context = LocalContext.current
    var bitmap by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        val info = app.info ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val size = (48 * context.resources.displayMetrics.density).toInt()
                AppIconCache.getOrLoadBitmap(context, info, info.uid / 100000, size)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            // 加载不出来就留一个空位，别让行高跳
            Spacer(Modifier.size(40.dp))
        }
    }
}

/** 读一遍本地授权白名单（uid → 包名 / 名称 / 图标信息）。 */
fun loadDhizukuApps(packageManager: PackageManager): List<DhizukuApp> {
    val out = ArrayList<DhizukuApp>()
    for (uid in DhizukuSettings.grantedUids()) {
        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()
        if (packageName == null) {
            DhizukuSettings.revoke(uid)
            continue
        }
        val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        val label = info?.let {
            runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull()
        } ?: packageName
        out.add(DhizukuApp(uid, packageName, label, info))
    }
    return out
}
