package moe.shizuku.manager.ui.liquidglass

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 一个图标 tab。文字只用于无障碍朗读，界面上不显示。 */
@Immutable
data class LiquidGlassNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** 选中/发光时叠上来的图标（通常是实心版本），不传则复用 [icon]。 */
    val selectedIcon: ImageVector? = null,
    val badgeCount: Int = 0,
)

/** 尺寸与动画 token：唯一事实来源。 */
object LiquidGlassNavDefaults {
    /** 紧凑高度：48dp ~ 56dp。 */
    val BarHeight = 52.dp

    /** 滑块上下内边距：滑块高 = BarHeight - 2 × VerticalPadding。 */
    val VerticalPadding = 5.dp

    /** 滑块左右内边距：滑块宽 = 单 tab 宽 - 2 × SliderHorizontalInset，避免贴着玻璃栏两侧。 */
    val SliderHorizontalInset = 10.dp

    val HorizontalMargin = 16.dp
    val BottomMargin = 16.dp

    /** 玻璃边缘折射描边（0.5dp ~ 1dp）。 */
    val BarStrokeWidth = 0.75.dp

    /** 滑块微光描边。 */
    val SliderStrokeWidth = 0.5.dp

    /** 模糊克制在 10~15dp，透出背景层次。 */
    val BlurRadius = 12.dp
    val MaxBlurRadius = 15.dp

    /** 滑块泛光的模糊半径：越大扩散越柔。 */
    val GlowBlurRadius = 11.dp
    val IconSize = 24.dp

    /** 内容列表底部避让。 */
    val ContentBottomPadding = 96.dp

    /** 滑块滑动弹簧：放软一些，慢一点、更绵，仍保留轻微过冲。 */
    const val SpringDampingRatio = 0.72f
    const val SpringStiffness = 260f

    /** 图标选中态颜色过渡时长。 */
    const val TintDurationMillis = 200
}

/**
 * 悬浮胶囊底部选择栏 · Liquid Glass（透亮版）。
 *
 * 玻璃：极低透明度白底（0.08）+ 克制模糊（12dp）+ 内部微弱径向微光
 *      + 左上纯白 → 右下透明的 0.75dp 对角折射描边。全静态，无光效动画。
 * 滑块：白 0.15 透亮玻璃 + 0.5dp 对角微光描边；下方是**泛光层** ——
 *      沿轮廓的粗光带经 `Modifier.blur` 向外扩散，而不是贴在滑块里的高光。
 * 布局：根 `Box` 为唯一坐标系，垂直居中；滑块左右各留 `SliderHorizontalInset`，
 *      位移 = `inset + position × tabWidth`，并全程 `coerceIn` 防越界。
 *
 * @param backdrop 可选：传页面背景（渐变/图片）可得到真正的背景模糊。
 */
@Composable
fun LiquidGlassNavBar(
    items: List<LiquidGlassNavItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = LiquidGlassNavDefaults.BarHeight,
    horizontalMargin: Dp = LiquidGlassNavDefaults.HorizontalMargin,
    bottomMargin: Dp = LiquidGlassNavDefaults.BottomMargin,
    blurRadius: Dp = LiquidGlassNavDefaults.BlurRadius,
    accent: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.primary,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * 是否自绘玻璃底的**高斯模糊**。
     * 宿主已提供磨砂底（例如 View 侧的 BlurView）时传 false：仍画半透明底色 +
     * 内部微光 + 边缘折射 + 泛光 + 滑块 + 图标，只是不再重复模糊、也不再画背景拷贝。
     */
    frosted: Boolean = true,
    backdrop: (@Composable () -> Unit)? = null,
    /**
     * 泛光溢出内边距：滑块光晕可以画到胶囊之外（宿主容器要比胶囊高 2×此值，
     * 且不能裁剪子视图）。0dp = 光晕被胶囊裁掉，只在栏内可见。
     */
    glowOverflow: Dp = 0.dp,
) {
    if (items.isEmpty()) return

    val count = items.size
    val lastIndex = count - 1
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // Modifier.blur 依赖 RenderEffect，API 31+ 才生效
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 宿主已提供磨砂底时不自绘模糊（frosted = false）
    val useBlur = blurSupported && frosted
    val effectiveBlur = blurRadius.coerceIn(0.dp, LiquidGlassNavDefaults.MaxBlurRadius)
    val glass = rememberGlassColors(accent = accent, blurSupported = blurSupported)
    val capsule = remember { RoundedCornerShape(percent = 50) }
    val springSpec = remember {
        spring<Float>(
            dampingRatio = LiquidGlassNavDefaults.SpringDampingRatio,
            stiffness = LiquidGlassNavDefaults.SpringStiffness,
        )
    }
    val scope = rememberCoroutineScope()
    val barStrokePx = with(density) { LiquidGlassNavDefaults.BarStrokeWidth.toPx() }
    val sliderStrokePx = with(density) { LiquidGlassNavDefaults.SliderStrokeWidth.toPx() }

    // 滑块位置：浮点索引 0f..lastIndex（拖拽中间态也是这个值）
    val slide = remember { Animatable(selectedIndex.coerceIn(0, lastIndex).toFloat()) }
    var animationTarget by remember { mutableIntStateOf(selectedIndex.coerceIn(0, lastIndex)) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.coerceIn(0, lastIndex).toFloat()) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    // 手指在栏坐标系里的 x（px）；-1f = 未拖拽
    var fingerX by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(selectedIndex, lastIndex) {
        val clamped = selectedIndex.coerceIn(0, lastIndex)
        if (clamped != animationTarget) animationTarget = clamped
    }
    LaunchedEffect(animationTarget, lastIndex) {
        if (!dragging) slide.animateTo(animationTarget.coerceIn(0, lastIndex).toFloat(), springSpec)
    }

    var barTopLeft by remember { mutableStateOf(Offset.Zero) }
    val containerSize = LocalWindowInfo.current.containerSize

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = glowOverflow, vertical = glowOverflow)
            .padding(start = horizontalMargin, end = horizontalMargin, bottom = bottomMargin),
    ) {
        val barWidthPx = with(density) { maxWidth.toPx() }
        val barHeightPx = with(density) { barHeight.toPx() }
        // 每个图标的宽度
        val tabWidthPx = barWidthPx / count
        val verticalPaddingPx = with(density) { LiquidGlassNavDefaults.VerticalPadding.toPx() }
        val sliderInsetPx = with(density) { LiquidGlassNavDefaults.SliderHorizontalInset.toPx() }
        // 滑块比 tab 窄一圈，左右各留内距 → 不贴玻璃栏两侧
        val sliderWidthPx = (tabWidthPx - sliderInsetPx * 2f).coerceAtLeast(1f)
        val sliderHeightPx = (barHeightPx - verticalPaddingPx * 2f).coerceAtLeast(1f)
        val capsuleCornerPx = barHeightPx / 2f
        val sliderCornerPx = sliderHeightPx / 2f
        // 左边界上限：绝不能滑出玻璃栏左右边界
        val maxSliderLeftPx = (barWidthPx - sliderWidthPx).coerceAtLeast(0f)

        /** 位置 → 滑块左边界（px），始终夹在栏内。 */
        fun sliderLeft(position: Float): Float =
            (sliderInsetPx + position * tabWidthPx).coerceIn(0f, maxSliderLeftPx)

        /**
         * 拖拽时图标的泛光强度：按「图标与滑块中心的距离」做 smoothstep 衰减，
         * 1 个 tab 之外不发光。只在 draw 阶段调用（读状态不触发重组）。
         */
        fun dragGlowIntensity(index: Int): Float {
            if (!dragging) return 0f
            val distance = kotlin.math.abs(dragPosition - index)
            val t = (1f - distance).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** 拖拽结束：吸附最近图标；回到原位也走 spring。 */
        fun settleDrag() {
            val target = dragPosition.roundToInt().coerceIn(0, lastIndex)
            val changed = target != animationTarget
            fingerX = -1f
            scope.launch {
                slide.snapTo(dragPosition.coerceIn(0f, lastIndex.toFloat()))
                dragging = false
                if (changed) {
                    animationTarget = target
                    onSelected(target)
                } else {
                    slide.animateTo(target.toFloat(), springSpec)
                }
            }
            if (changed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        // ============ 唯一坐标系：Box（父） + Row（图标） ============
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .onGloballyPositioned { barTopLeft = it.positionInRoot() }
                .pointerInput(count, tabWidthPx, lastIndex) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragAccum = 0f
                            dragPosition = slide.value.coerceIn(0f, lastIndex.toFloat())
                            fingerX = offset.x
                            dragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                            dragPosition = (slide.value + dragAccum / tabWidthPx)
                                .coerceIn(0f, lastIndex.toFloat())
                            fingerX = change.position.x
                        },
                        onDragEnd = { settleDrag() },
                        onDragCancel = { settleDrag() },
                    )
                },
        ) {
            // ---------- 玻璃面板（全静态）----------
            Box(modifier = Modifier.matchParentSize().clip(capsule)) {
                if (useBlur && backdrop != null) {
                    Box(
                        modifier = Modifier
                            .requiredSize(
                                width = with(density) { containerSize.width.toDp() },
                                height = with(density) { containerSize.height.toDp() },
                            )
                            .offset {
                                IntOffset(-barTopLeft.x.roundToInt(), -barTopLeft.y.roundToInt())
                            }
                            .blur(effectiveBlur, BlurredEdgeTreatment.Unbounded),
                    ) {
                        backdrop()
                    }
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            if (useBlur) {
                                Modifier.blur(effectiveBlur, BlurredEdgeTreatment.Unbounded)
                            } else {
                                Modifier
                            },
                        )
                        .background(glassBaseBrush(glass)),
                )
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawInnerGlow(glass, capsuleCornerPx)
                    drawEdgeRefraction(glass, capsuleCornerPx, barStrokePx)
                }
            }

            // ---------- 泛光层：**不裁剪**，光晕可以透出玻璃栏 ----------
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(with(density) { sliderWidthPx.toDp() })
                        .height(with(density) { sliderHeightPx.toDp() })
                        .graphicsLayer {
                            val position = if (dragging) dragPosition else slide.value
                            translationX = sliderLeft(position)
                        }
                        .then(
                            if (blurSupported) {
                                Modifier.blur(
                                    LiquidGlassNavDefaults.GlowBlurRadius,
                                    BlurredEdgeTreatment.Unbounded,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .drawBehind {
                            if (blurSupported) {
                                drawSliderBloom(glass, sliderCornerPx)
                            } else {
                                drawSliderHaloFallback(glass)
                            }
                        },
                )
            }

            // ---------- 滑块本体：裁剪到胶囊内，与容器同坐标系、垂直居中 ----------
            Box(modifier = Modifier.matchParentSize().clip(capsule)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(with(density) { sliderWidthPx.toDp() })
                        .height(with(density) { sliderHeightPx.toDp() })
                        .graphicsLayer {
                            val position = if (dragging) dragPosition else slide.value
                            // 宽度 = tab 宽 - 左右内距 → 左边界 = inset + position × tabWidth，并 coerceIn 死防越界
                            translationX = sliderLeft(position)
                        }
                        .drawBehind {
                            drawSliderBody(glass, sliderCornerPx, sliderStrokePx)
                        },
                )
            }

            // ---------- 图标行（无文字标签）----------
            Row(
                modifier = Modifier.matchParentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    NavIconView(
                        item = item,
                        selected = index == selectedIndex,
                        // 拖拽中不用「已提交的选中态」上色，避免旧界面图标一直亮着
                        dragging = dragging,
                        accent = accent,
                        selectedContentColor = selectedContentColor,
                        unselectedContentColor = unselectedContentColor,
                        glowIntensity = { dragGlowIntensity(index) },
                        onClick = { if (index != selectedIndex) onSelected(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavIconView(
    item: LiquidGlassNavItem,
    selected: Boolean,
    /** 正在拖拽滑块：此时选中态改由「滑块距离」决定，旧界面图标要淡下去。 */
    dragging: Boolean,
    accent: Color,
    selectedContentColor: Color,
    unselectedContentColor: Color,
    /** 拖拽时的泛光强度 0f~1f；只在 draw 阶段读取，动画/拖拽全程不触发重组。 */
    glowIntensity: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 拖拽中所有图标都以「未选中」为底色，主题色只由泛光层按滑块位置叠上来
    val selectionActive = selected && !dragging
    val target = when {
        selectionActive -> selectedContentColor
        pressed -> unselectedContentColor.copy(alpha = 0.5f)
        else -> unselectedContentColor.copy(alpha = 0.68f)
    }
    // 只有颜色过渡：没有按压缩放 / 回弹
    val tint by animateColorAsState(
        targetValue = target,
        animationSpec = tween(LiquidGlassNavDefaults.TintDurationMillis),
        label = "navTint",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        Box {
            // 图标：底层常驻颜色 + 上层主题色（alpha 由泛光强度驱动，与滑块位置同步发光）
            Box(
                modifier = Modifier
                    .size(LiquidGlassNavDefaults.IconSize)
                    .drawBehind {
                        val intensity = glowIntensity()
                        drawIconGlow(accent, intensity)
                    },
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = glowIntensity() },
                ) {
                    Icon(
                        imageVector = item.selectedIcon ?: item.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                // 滑块压到本图标上时换上对比色（实心滑块是主题色，主题色图标会看不见）
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val intensity = glowIntensity()
                            alpha = ((intensity - 0.6f) / 0.4f).coerceIn(0f, 1f)
                        },
                ) {
                    Icon(
                        imageVector = item.selectedIcon ?: item.icon,
                        contentDescription = null,
                        tint = selectedContentColor,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            if (item.badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                        .size(8.dp)
                        .drawBehind {
                            drawCircle(color = accent)
                            drawCircle(color = Color.White.copy(alpha = 0.9f), style = Stroke(width = 1.dp.toPx()))
                        },
                )
            }
        }
    }
}
