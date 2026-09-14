package moe.shizuku.manager.download

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
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
import moe.shizuku.manager.ui.hint.HintStyle
import moe.shizuku.manager.ui.hint.ensureReadable
import moe.shizuku.manager.ui.hint.itemEntrance

/**
 * 推荐应用下载页（Jetpack Compose + 双风格）。
 *
 * 只做三件事，别的都不做：
 * 1. 列出常用支持应用（名单在 [RecommendedApps]）；
 * 2. 「下载」用**系统浏览器**打开官方页面 —— Shizako 不内置、也不代下别人的安装包；
 * 3. 显示是否已安装，装了再给一个「打开」。
 *
 * 没有「注入」：授权由应用自己在运行时向 Shizako 申请，这里不碰权限。
 */
@Composable
fun AppsDownloadScreen(
    palette: HintPalette,
    listState: LazyListState,
    /** 已安装的包名（Fragment 在 onResume 刷新：从系统安装器回来立刻能看到变化） */
    installedPackages: Set<String>,
    /** 点「下载」：交给宿主用应用内下载器（更新同款）下载并安装 */
    onDownload: (RecommendedApp) -> Unit,
    onOpenApp: (String) -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    HintPage(
        listState = listState,
        onCollapsedChange = onCollapsedChange,
        modifier = modifier,
    ) {
        item {
            Box(modifier = Modifier.itemEntrance(0)) {
                HintNote(
                    palette = palette,
                    iconRes = R.drawable.ic_help_outline_24dp,
                    text = stringResource(R.string.download_note),
                    secondary = stringResource(R.string.download_note_secondary),
                )
            }
        }

        itemsIndexed(RecommendedApps.ALL) { index, app ->
            Box(modifier = Modifier.itemEntrance(1 + index)) {
                RecommendedAppCard(
                    app = app,
                    installed = app.packageName in installedPackages,
                    palette = palette,
                    onDownload = onDownload,
                    onOpenApp = onOpenApp,
                )
            }
        }

        item {
            Box(modifier = Modifier.itemEntrance(6)) {
                HintCard(palette = palette) {
                    Text(
                        text = stringResource(R.string.download_how_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.variant,
                    )
                    Text(
                        text = stringResource(R.string.download_how_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onCard,
                    )
                }
            }
        }
    }
}

/** 单个应用卡：图标 + 名字 + 包名 + 状态标签 + 说明 + 下载（应用内下载器）/ 打开。 */
@Composable
private fun RecommendedAppCard(
    app: RecommendedApp,
    installed: Boolean,
    palette: HintPalette,
    onDownload: (RecommendedApp) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    HintCard(palette = palette) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(app.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onCard,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = palette.variant,
                )
            }
            InstalledTag(installed, palette)
        }

        Text(
            text = stringResource(app.descRes),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onCard,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HintPrimaryButton(
                palette = palette,
                text = stringResource(R.string.download_action_download),
                modifier = Modifier.weight(1f),
                onClick = { onDownload(app) },
            )
            if (installed) {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.download_action_open),
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenApp(app.packageName) },
                )
            }
        }
    }
}

/**
 * 应用图标：全部用**随包内置**的图标（从镜像 APK 里抠出来的原始资源），
 * 装没装都显示，不会出现「只有下面的才有图标」。
 *
 * 自适应图标（有底图）按系统那套画法还原：原图是 108dp 画布、可见区是中间 72dp，
 * 所以底图 + 前层都放大到 1.5× 再裁成图标大小，看到的就接近启动器里的样子。
 */
@Composable
private fun AppIcon(app: RecommendedApp) {
    val size = 40.dp
    val inner = size * 1.5f

    if (app.iconBackgroundRes == null) {
        Image(
            painter = painterResource(app.iconRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp)),
        )
        return
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(app.iconBackgroundRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(inner),
        )
        Image(
            painter = painterResource(app.iconRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(inner),
        )
    }
}

/** 右上角状态标签：已安装（强调色）/ 未安装（灰）。 */
@Composable
private fun InstalledTag(installed: Boolean, palette: HintPalette) {
    val glass = palette.style == HintStyle.GLASS
    val background = if (installed) {
        if (glass) palette.accent.copy(alpha = 0.18f) else palette.tagBackground
    } else {
        palette.variant.copy(alpha = 0.14f)
    }
    val textColor = if (installed) {
        if (glass) ensureReadable(palette.accent, palette.glassFillTop)
        else ensureReadable(palette.onTagBackground, palette.tagBackground)
    } else {
        palette.variant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(
                if (installed) R.string.download_installed else R.string.download_not_installed,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

// ---------------- 打开已安装的应用 ----------------

/** 打开已安装的应用（没有启动入口就提示一句）。 */
internal fun openInstalledApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) {
        Toast.makeText(context, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
    }
}
