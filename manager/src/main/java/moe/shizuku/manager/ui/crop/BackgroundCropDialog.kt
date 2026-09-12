package moe.shizuku.manager.ui.crop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.shizuku.manager.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 背景图片裁剪：**比例锁定为图片原始宽高比**，用户只能缩放 / 拖动，不能改比例 ——
 * 和 uCrop 不调用 `withAspectRatio()` 时的行为一致（裁剪框 = 原图比例）。
 *
 * 为什么锁比例：背景最终是 centerCrop 铺满屏幕的。若裁剪框跟着屏幕比例走，
 * 竖屏裁出来的图放到横屏/平板上就会被再裁一次，或者干脆被拉变形。
 * 锁原比例 → 无论怎么铺都不变形、不丢内容。
 *
 * 关键点：只回报**相对原图的分数（0..1）**，预览是采样解码的，
 * 分数和采样比例无关，所以真实裁剪不会偏。
 */
@Composable
fun BackgroundCropDialog(
    sourcePath: String,
    accent: Color,
    onAccent: Color,
    onCancel: () -> Unit,
    onApply: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onResetSource: () -> Unit,
) {
    val image = remember(sourcePath) { decodePreview(sourcePath) } ?: return
    val imageAspect = image.width.toFloat() / image.height.toFloat()

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val padding = 14.dp
    // 裁剪框：可用区域里最大的「原图比例」矩形，居中
    val frame = remember(viewport, image) {
        cropFrame(viewport, imageAspect, paddingPx = 0f)
    }
    // 最小缩放 = 图片刚好铺满裁剪框（保证框内没有空白）
    val cover = remember(frame, image) {
        if (frame.width <= 0f || frame.height <= 0f) 1f
        else max(frame.width / image.width, frame.height / image.height)
    }

    // 尺寸变化 / 换图 → 复位到「铺满 + 居中」
    LaunchedEffect(cover) {
        scale = cover
        offsetX = 0f
        offsetY = 0f
    }

    fun clamp() {
        val drawnW = image.width * scale
        val drawnH = image.height * scale
        val maxX = max(0f, (drawnW - frame.width) / 2f)
        val maxY = max(0f, (drawnH - frame.height) / 2f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20B0B0D))
                .systemBarsPadding(),
        ) {
            // 顶部：取消 + 说明 + 锁定比例
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.settings_bg_crop_cancel),
                        color = Color(0xFFE6E6E6),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.settings_bg_crop_hint),
                        color = Color(0x99FFFFFF),
                        textAlign = TextAlign.Center,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_bg_crop_locked, ratioLabel(image)),
                        color = Color(0x66FFFFFF),
                        textAlign = TextAlign.Center,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }

            // 取景区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(padding)
                    .onSizeChanged { viewport = it }
                    .pointerInput(cover) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(cover, cover * 6f)
                            offsetX += pan.x
                            offsetY += pan.y
                            clamp()
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val localFrame = cropFrame(viewport, imageAspect, paddingPx = 0f)
                    val sc = if (scale <= 0f) cover else scale
                    val drawnW = image.width * sc
                    val drawnH = image.height * sc
                    val left = localFrame.center.x - drawnW / 2f + offsetX
                    val top = localFrame.center.y - drawnH / 2f + offsetY

                    clipRect(0f, 0f, size.width, size.height) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(drawnW.roundToInt(), drawnH.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }

                    // 框外压暗（uCrop 那种取景观感）
                    val scrim = Color(0xB3000000)
                    drawRect(scrim, Offset.Zero, Size(size.width, localFrame.top))
                    drawRect(
                        scrim,
                        Offset(0f, localFrame.bottom),
                        Size(size.width, size.height - localFrame.bottom),
                    )
                    drawRect(
                        scrim,
                        Offset(0f, localFrame.top),
                        Size(localFrame.left, localFrame.height),
                    )
                    drawRect(
                        scrim,
                        Offset(localFrame.right, localFrame.top),
                        Size(size.width - localFrame.right, localFrame.height),
                    )

                    // 三分网格 + 边框
                    for (i in 1..2) {
                        val x = localFrame.left + localFrame.width * i / 3f
                        val y = localFrame.top + localFrame.height * i / 3f
                        drawLine(
                            Color(0x44FFFFFF),
                            Offset(x, localFrame.top),
                            Offset(x, localFrame.bottom),
                            strokeWidth = 1.4f,
                        )
                        drawLine(
                            Color(0x44FFFFFF),
                            Offset(localFrame.left, y),
                            Offset(localFrame.right, y),
                            strokeWidth = 1.4f,
                        )
                    }
                    drawRect(
                        color = Color(0xF2FFFFFF),
                        topLeft = Offset(localFrame.left, localFrame.top),
                        size = Size(localFrame.width, localFrame.height),
                        style = Stroke(width = 2.2f),
                    )
                }
            }

            // 底部：还原 / 应用
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        scale = cover
                        offsetX = 0f
                        offsetY = 0f
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x1FFFFFFF),
                        contentColor = Color(0xFFEDEDED),
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_bg_crop_reset))
                }
                Button(
                    onClick = {
                        val sc = if (scale <= 0f) cover else scale
                        // 裁剪框 → 原图坐标 → 分数（和预览解码尺寸无关）
                        fun fractionX(p: Float) =
                            ((p - frame.center.x - offsetX) / sc + image.width / 2f) / image.width

                        fun fractionY(p: Float) =
                            ((p - frame.center.y - offsetY) / sc + image.height / 2f) / image.height

                        onApply(
                            fractionX(frame.left).coerceIn(0f, 1f),
                            fractionY(frame.top).coerceIn(0f, 1f),
                            fractionX(frame.right).coerceIn(0f, 1f),
                            fractionY(frame.bottom).coerceIn(0f, 1f),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = onAccent,
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_bg_crop_apply))
                }
            }
            TextButton(
                onClick = onResetSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp)),
            ) {
                Text(
                    text = stringResource(R.string.settings_bg_clear),
                    color = Color(0x99FFFFFF),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 可用区域里「原图比例」的最大内接矩形，居中。 */
private fun cropFrame(viewport: IntSize, aspect: Float, paddingPx: Float): Rect {
    if (viewport.width <= 0 || viewport.height <= 0 || aspect <= 0f) return Rect.Zero
    val availW = viewport.width - paddingPx * 2f
    val availH = viewport.height - paddingPx * 2f
    if (availW <= 0f || availH <= 0f) return Rect.Zero
    var w = availW
    var h = w / aspect
    if (h > availH) {
        h = availH
        w = h * aspect
    }
    val left = (viewport.width - w) / 2f
    val top = (viewport.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** 比例标签：能化简就化简（1080:1439 → 3:4 之类化简不了就给小数）。 */
private fun ratioLabel(image: ImageBitmap): String {
    val w = image.width
    val h = image.height
    val g = gcd(w, h)
    val a = w / g
    val b = h / g
    return if (a in 1..40 && b in 1..40) {
        "$a:$b"
    } else {
        String.format(java.util.Locale.US, "%.2f:1", w.toFloat() / h)
    }
}

private fun gcd(a: Int, b: Int): Int {
    var x = if (a < 0) -a else a
    var y = if (b < 0) -b else b
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return if (x == 0) 1 else x
}

/** 预览用解码：最长边限制到 2048，别把整张 4K 原图读进内存。 */
private fun decodePreview(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bmp = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    return bmp.asImageBitmap()
}
