package moe.shizuku.manager.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import moe.shizuku.manager.ShizukuSettings

/**
 * 玻璃材质：**照搬椒盐音乐 Salt Player 的那套**。
 *
 * Salt Player 的界面材质来自它自己的 UI 库 Salt UI（Moriafly/SaltUI），其中
 * `SaltHazeStyles`（blurryGlass / acrylic / mica）是直接跑在 [Haze]（chrisbanes/haze）
 * 上的：每个材质 = 模糊半径 + 噪点强度 + 背景容器色 + 一串带混合模式的 tint。
 * 这里把那份配方整份搬过来（Apache-2.0），参数逐条对齐：
 *
 * | 材质 | 模糊 | 噪点 | 着色 |
 * | --- | --- | --- | --- |
 * | 毛玻璃 blurryGlass | 45dp | 0.01 | 深色 0x80000000 / 浅色 0x80FFFFFF 单层 tint |
 * | 亚克力 acrylic | 60dp | 0.02 | Color + Luminosity 两层 tint（浓度随深浅色） |
 * | 云母 mica | 240dp | 0 | Color 0.5/0.8 + Luminosity 1.0 |
 *
 * 亚克力/云母的颜色值取自 Haze 的 `FluentMaterials`（WinUI 3 Figma 原值），
 * 也就是 Salt UI 里 `SaltHazeStyles.acrylic/mica` 注释所说 "Copy of FluentMaterials" 的那份。
 */
@JvmInline
value class GlassMaterialKind private constructor(val id: String) {
    companion object {
        /** 毛玻璃（Salt 的 blurryGlass，Salt Player 组件默认材质） */
        val GLASS = GlassMaterialKind("glass")

        /** 亚克力（WinUI Acrylic：透一点、明显发雾） */
        val ACRYLIC = GlassMaterialKind("acrylic")

        /** 云母（WinUI Mica：几乎糊成一片色，桌面壁纸那种感觉） */
        val MICA = GlassMaterialKind("mica")

        /** 关掉材质，回到老的半透明面板（浅色淡黑 / 深色淡白 + 噪点） */
        val NONE = GlassMaterialKind("none")

        val all = listOf(GLASS, ACRYLIC, MICA, NONE)

        fun of(id: String?): GlassMaterialKind =
            all.firstOrNull { it.id == id } ?: ACRYLIC

        fun values(): List<String> = all.map { it.id }
    }
}

/** 材质层级：Salt 的 MaterialLayer（Background = 铺满的那层，SubBackground = 卡片/弹层）。 */
enum class GlassLayer { BACKGROUND, SUB_BACKGROUND }

/**
 * 材质偏好：**可观察**（和 UiStyle / NavTabs 一个套路），
 * 设置里改完立即重组，不需要重建 Activity。
 */
object GlassMaterials {

    const val KEY_MATERIAL = "glass_material"
    const val KEY_BLUR_DP = "glass_blur_dp"

    /** 跟随材质默认值的哨兵值 */
    const val BLUR_FOLLOW_MATERIAL = -1f

    private var version by mutableIntStateOf(0)

    private fun prefs() = ShizukuSettings.getPreferences()

    fun kind(): GlassMaterialKind {
        version
        // Haze 材质（毛玻璃 / 亚克力 / 云母）暂时停用：设置里的入口已去掉，
        // 卡片回到「半透明面板 + 噪点」那条最省的路上（不用每帧取样模糊）。
        // 想恢复：把下面这行改回读偏好 KEY_MATERIAL 即可，配方都还在。
        return GlassMaterialKind.NONE
    }

    /** 旧入口，保留给以后恢复材质选择 */
    fun kindFromPrefs(): GlassMaterialKind {
        version
        return GlassMaterialKind.of(prefs().getString(KEY_MATERIAL, GlassMaterialKind.ACRYLIC.id))
    }

    fun setKind(value: String) {
        prefs().edit().putString(KEY_MATERIAL, GlassMaterialKind.of(value).id).apply()
        version++
    }

    /** 材质自带的模糊半径（照搬 Salt / FluentMaterials 的值） */
    fun defaultBlurDp(kind: GlassMaterialKind): Float = when (kind) {
        GlassMaterialKind.GLASS -> 45f
        GlassMaterialKind.ACRYLIC -> 60f
        GlassMaterialKind.MICA -> 240f
        else -> 45f
    }

    /** 是否启用 Haze 材质（NONE = 用老面板） */
    fun isEnabled(kind: GlassMaterialKind): Boolean = kind != GlassMaterialKind.NONE

    /** 用户拖过滑块就用用户的值，否则跟随材质默认 */
    fun blurDp(kind: GlassMaterialKind): Float {
        version
        val saved = prefs().getFloat(KEY_BLUR_DP, BLUR_FOLLOW_MATERIAL)
        return if (saved < 0f) defaultBlurDp(kind) else saved
    }

    fun setBlurDp(value: Float) {
        prefs().edit().putFloat(KEY_BLUR_DP, value).apply()
        version++
    }

    fun resetBlur() {
        prefs().edit().remove(KEY_BLUR_DP).apply()
        version++
    }

    // ---------------- 实时磨砂（BlurView）开关 ----------------

    const val KEY_LIVE_BLUR = "live_blur_enabled"

    /**
     * 顶栏 / 底栏的**实时**磨砂底（BlurView）。
     *
     * 默认关：这套实现每帧都要把整个内容层重新录制一遍再做 RenderEffect 模糊
     * （`RenderNodeBlurController.drawSnapshot()` 里 `drawRenderNode(target)`），
     * 也就是同一屏每帧画两遍以上。壁纸 + 半透明卡片一起上，滚动就会掉帧，
     * 而且模糊总是比内容慢一帧 —— 卡片边缘扫过时会出现拖影。
     * 关掉之后：顶栏只留渐变遮罩，底栏胶囊是半透明面板（壁纸本身已经糊过），
     * 滚动干净利落。想要「内容在玻璃后糊过去」的观感再打开。
     */
    fun isLiveBlurEnabled(): Boolean {
        version
        return prefs().getBoolean(KEY_LIVE_BLUR, false)
    }

    fun setLiveBlurEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_LIVE_BLUR, enabled).apply()
        version++
    }

    private const val NOISE_GLASS = 0.01f
    private const val NOISE_ACRYLIC = 0.02f
    private const val NOISE_MICA = 0f

    /**
     * 按 Salt 的配方生成 [HazeStyle]。
     *
     * @param background 主题背景色（Salt 里是 SaltTheme.colors.background）
     * @param isDark 当前是否深色（Salt 用它切换 tint 浓度 / 颜色）
     */
    @Composable
    fun style(
        kind: GlassMaterialKind,
        layer: GlassLayer,
        background: Color,
        isDark: Boolean,
        blurRadius: Dp? = null,
    ): HazeStyle {
        val radius = blurRadius ?: blurDp(kind).dp
        return when (kind) {
            GlassMaterialKind.GLASS -> blurryGlass(layer, background, isDark, radius)
            GlassMaterialKind.ACRYLIC -> acrylic(layer, isDark, radius)
            GlassMaterialKind.MICA -> mica(isDark, radius)
            // NONE 不该走到这（调用方会用老面板），兜底给毛玻璃
            else -> blurryGlass(layer, background, isDark, radius)
        }
    }

    /** Salt UI `SaltHazeStyles.blurryGlass` 原样搬：45dp / 噪点 0.01，深色黑罩、浅色白罩。 */
    private fun blurryGlass(
        layer: GlassLayer,
        background: Color,
        isDark: Boolean,
        radius: Dp,
    ): HazeStyle = when (layer) {
        GlassLayer.BACKGROUND -> hazeStyle(
            containerColor = background,
            tints = listOf(
                HazeTint(if (isDark) Color(0x80000000) else Color(0x80FFFFFF)),
            ),
            blurRadius = radius,
            noiseFactor = NOISE_GLASS,
        )

        GlassLayer.SUB_BACKGROUND -> hazeStyle(
            containerColor = background,
            tints = if (isDark) {
                listOf(
                    HazeTint(Color(0x60333333)),
                    HazeTint(Color(0x80000000), BlendMode.Overlay),
                )
            } else {
                listOf(
                    HazeTint(Color(0x99585858), BlendMode.Luminosity),
                    HazeTint(Color(0x60404040), BlendMode.Screen),
                    HazeTint(Color(0xFF808080), BlendMode.ColorDodge),
                    HazeTint(Color(0x8CFFFFFF), BlendMode.Luminosity),
                )
            },
            blurRadius = radius,
            noiseFactor = NOISE_GLASS,
        )
    }

    /** FluentMaterials.acrylicBase / acrylicDefault 原值（60dp / 噪点 0.02）。 */
    private fun acrylic(layer: GlassLayer, isDark: Boolean, radius: Dp): HazeStyle {
        val container: Color
        val fallback: Color
        val lightTint: Float
        val lightLum: Float
        val darkTint: Float
        val darkLum: Float
        when (layer) {
            GlassLayer.BACKGROUND -> {
                container = if (isDark) Color(0xFF202020) else Color(0xFFF3F3F3)
                fallback = if (isDark) Color(0xFF1C1C1C) else Color(0xFFEEEEEE)
                lightTint = 0f; lightLum = 0.9f
                darkTint = 0.5f; darkLum = 0.96f
            }

            GlassLayer.SUB_BACKGROUND -> {
                container = if (isDark) Color(0xFF2C2C2C) else Color(0xFFFCFCFC)
                fallback = if (isDark) Color(0xFF2C2C2C) else Color(0xFFF9F9F9)
                lightTint = 0f; lightLum = 0.85f
                darkTint = 0.15f; darkLum = 0.96f
            }
        }
        return hazeStyle(
            containerColor = container,
            tints = listOf(
                HazeTint(container.copy(alpha = if (isDark) darkTint else lightTint), BlendMode.Color),
                HazeTint(container.copy(alpha = if (isDark) darkLum else lightLum), BlendMode.Luminosity),
            ),
            blurRadius = radius,
            noiseFactor = NOISE_ACRYLIC,
            fallbackColor = fallback,
        )
    }

    /** FluentMaterials.mica 原值（240dp / 不噪点）。 */
    private fun mica(isDark: Boolean, radius: Dp): HazeStyle {
        val container = if (isDark) Color(0xFF202020) else Color(0xFFF3F3F3)
        return hazeStyle(
            containerColor = container,
            tints = listOf(
                HazeTint(container.copy(alpha = if (isDark) 0.8f else 0.5f), BlendMode.Color),
                HazeTint(container.copy(alpha = 1f), BlendMode.Luminosity),
            ),
            blurRadius = radius,
            noiseFactor = NOISE_MICA,
        )
    }

    private fun hazeStyle(
        containerColor: Color,
        tints: List<HazeTint>,
        blurRadius: Dp,
        noiseFactor: Float,
        fallbackColor: Color = containerColor,
    ): HazeStyle = HazeStyle(
        backgroundColor = containerColor,
        tints = tints,
        blurRadius = blurRadius,
        noiseFactor = noiseFactor,
        fallbackTint = HazeTint(fallbackColor),
    )
}
