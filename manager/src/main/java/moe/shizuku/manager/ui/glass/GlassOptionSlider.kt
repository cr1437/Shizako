package moe.shizuku.manager.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 玻璃风格的分段滑块：选项 2~4 个时用它替代 M3 分段按钮。
 *
 * 和底部导航的滑块同一套观感 —— 圆角胶囊里一块滑动的玻璃滑块 + 细描边 + 弹簧吸附，
 * 支持**拖动**（松手吸附到最近项）与**点击**某段。
 *
 * 尺寸刻意收小：默认高 38dp、内边距 3dp，放进设置卡片里不显笨重。
 */
@Composable
fun GlassOptionSlider(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    thumbContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trackColor: Color = Color.White.copy(alpha = 0.10f),
    edgeColor: Color = Color.White.copy(alpha = 0.40f),
    height: Dp = 38.dp,
) {
    if (options.isEmpty()) return
    val count = options.size
    val density = LocalDensity.current.density
    val insetPx = 3f * density

    var widthPx by remember { mutableIntStateOf(0) }
    val position = remember { Animatable(selectedIndex.toFloat()) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex, count) {
        position.animateTo(
            targetValue = selectedIndex.toFloat().coerceIn(0f, (count - 1).toFloat()),
            // 比底栏滑块更紧一点：设置里的切换后面紧接着要重建 Activity，
            // 滑块必须在 170ms 内落位，否则会看到"滑到一半画面就切了"。
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 520f),
        )
    }

    // 点击某一段：滑块**滑过去**，而不是瞬移
    val scope = rememberCoroutineScope()
    fun animateTo(index: Int) {
        scope.launch {
            position.animateTo(
                targetValue = index.toFloat().coerceIn(0f, (count - 1).toFloat()),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 520f),
            )
        }
    }

    val segmentPx = if (count == 0) 0f else widthPx.toFloat() / count
    // 【性能】不把拖动 / 弹簧动画状态读进组合期：位移在绘制阶段读，
    // 文字高亮用 derivedStateOf —— 只在跨过半格时触发重组。
    val activeIndex by remember { derivedStateOf { (if (dragging) dragPosition else position.value).roundToInt() } }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor)
            .border(0.6.dp, edgeColor, RoundedCornerShape(percent = 50))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(count, widthPx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragPosition = position.value
                        dragging = true
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        if (segmentPx > 0f) {
                            dragPosition = (dragPosition + amount / segmentPx)
                                .coerceIn(0f, (count - 1).toFloat())
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        onSelected(dragPosition.roundToInt().coerceIn(0, count - 1))
                    },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        // 会滑动的玻璃滑块（绝对定位：左内边距 + 索引 × 段宽）
        Box(
            modifier = Modifier
                .width(with(LocalDensity.current) { ((segmentPx - insetPx * 2) / density).dp })
                .height(height)
                .padding(vertical = 3.dp)
                .graphicsLayer {
                    // 【性能】绘制阶段读状态：拖动时不再每帧重组
                    val pos = if (dragging) dragPosition else position.value
                    translationX = insetPx + pos * segmentPx
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(thumbColor)
                .border(0.6.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(percent = 50)),
        )

        // 文案层（在滑块之上）
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val active = activeIndex == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable {
                            animateTo(index)
                            onSelected(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) thumbContentColor else unselectedContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}