package moe.shizuku.manager.ui.liquidglass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Liquid Glass 全部颜色。核心原则：**极透**。
 * 底色只允许 alpha 0.08 级别的白，禁止大块白/灰（会立刻变浑浊）。
 * 所有渐变都是静态的，这里没有任何随时间变化的量。
 */
@Immutable
internal data class GlassColors(
    val baseTop: Color,
    val baseBottom: Color,
    val innerGlow: Color,
    val edgeStart: Color,
    val edgeMid: Color,
    val edgeEnd: Color,
    val sliderFill: Color,
    /** 滑块边缘收口：极淡的白色描边，避免实心块边缘发毛。 */
    val sliderStroke: Color,
    /** 泛光主色：整个滑块轮廓的强光，靠 blur 向外扩散。 */
    val bloom: Color,
    /** API < 31 无 blur 时的径向泛光兜底色。 */
    val bloomFallback: Color,
)

/**
 * @param blurSupported API 31+ 才真模糊；低版本略抬高底色不透明度，走半透明渐变降级。
 */
@Composable
internal fun rememberGlassColors(
    accent: Color,
    blurSupported: Boolean,
    dark: Boolean = isSystemInDarkTheme(),
): GlassColors = remember(accent, blurSupported, dark) {
    val baseAlpha = if (blurSupported) 0.08f else 0.16f
    GlassColors(
        baseTop = Color.White.copy(alpha = baseAlpha),
        baseBottom = Color.White.copy(alpha = baseAlpha * 0.25f),
        innerGlow = Color.White.copy(alpha = if (dark) 0.09f else 0.13f),
        // 左上纯白 → 右下透明：光线从玻璃边缘折射进来
        edgeStart = Color.White.copy(alpha = 0.60f),
        edgeMid = Color.White.copy(alpha = 0.15f),
        edgeEnd = Color.Transparent,
        // 滑块 = 实心主题色块（不是半透明玻璃）
        sliderFill = accent.copy(alpha = 0.98f),
        sliderStroke = Color.White.copy(alpha = 0.30f),
        // 泛光：整块滑块的强光，模糊后向外扩散
        bloom = accent.copy(alpha = 0.95f),
        bloomFallback = accent.copy(alpha = 0.35f),
    )
}

/** 玻璃底：极低透明度白（上亮下更透）。 */
internal fun glassBaseBrush(colors: GlassColors): Brush =
    Brush.verticalGradient(listOf(colors.baseTop, colors.baseBottom))

/** 内部微光：径向，中心透明 → 边缘略微发光。 */
internal fun DrawScope.drawInnerGlow(colors: GlassColors, cornerRadius: Float) {
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, colors.innerGlow),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.maxDimension / 2f,
        ),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )
}

/**
 * 边缘折射：0.5dp~1dp 极细描边，颜色沿「左上 → 右下」对角渐变
 * （纯白 0.6 → 半透白 → 全透明）。
 */
internal fun DrawScope.drawEdgeRefraction(colors: GlassColors, cornerRadius: Float, widthPx: Float) {
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(colors.edgeStart, colors.edgeMid, colors.edgeEnd),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(width = widthPx),
    )
}

/**
 * 泛光：整块滑块的实心轮廓。这个节点会被 `Modifier.blur` 模糊，
 * 于是**整个滑块**向外扩散成柔和光晕（不是只发光一圈边）。
 */
internal fun DrawScope.drawSliderBloom(colors: GlassColors, cornerRadius: Float) {
    val radius = CornerRadius(cornerRadius, cornerRadius)
    // 比本体大一圈：模糊后光晕自然地包住滑块
    val inflate = size.height * 0.08f
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(colors.bloom, colors.bloom.copy(alpha = colors.bloom.alpha * 0.7f))),
        topLeft = Offset(-inflate, -inflate),
        size = Size(size.width + inflate * 2f, size.height + inflate * 2f),
        cornerRadius = CornerRadius(cornerRadius + inflate, cornerRadius + inflate),
    )
}

/** API < 31 兜底：没有 blur 时用径向渐变模拟泛光。 */
internal fun DrawScope.drawSliderHaloFallback(colors: GlassColors) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val haloRadius = size.maxDimension * 0.75f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors.bloomFallback, colors.bloomFallback.copy(alpha = colors.bloomFallback.alpha * 0.35f), Color.Transparent),
            center = center,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = center,
    )
}

/** 滑块本体：**实心**主题色块 + 0.5dp 极淡白色收口描边。 */
internal fun DrawScope.drawSliderBody(colors: GlassColors, cornerRadius: Float, strokeWidthPx: Float) {
    val radius = CornerRadius(cornerRadius, cornerRadius)
    drawRoundRect(brush = Brush.verticalGradient(listOf(colors.sliderFill, colors.sliderFill)), cornerRadius = radius)
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(colors.sliderStroke, Color.Transparent),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        cornerRadius = radius,
        style = Stroke(width = strokeWidthPx),
    )
}

/**
 * 图标泛光：拖拽时按「图标与滑块的距离」发亮。
 * [intensity] 0f~1f，由 draw 阶段传入（不触发重组）。
 */
internal fun DrawScope.drawIconGlow(color: Color, intensity: Float) {
    if (intensity <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.maxDimension * 1.15f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.55f * intensity),
                color.copy(alpha = 0.18f * intensity),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
