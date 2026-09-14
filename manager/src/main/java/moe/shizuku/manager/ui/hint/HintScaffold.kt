package moe.shizuku.manager.ui.hint

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.shizuku.manager.app.ThemeHelper

/** 记住一个可复用的交互源（玻璃按钮没有 M3 自带状态层，按下反馈得自己接）。 */
@androidx.compose.runtime.Composable
private fun rememberPressInteractionSource(): androidx.compose.foundation.interaction.MutableInteractionSource =
    androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

/** 玻璃按钮的三个视觉状态（updateTransition 的 targetState）。 */
private const val VISUAL_IDLE = 0
private const val VISUAL_PRESSED = 1
private const val VISUAL_DISABLED = 2

/**
 * 提示 / 教程页的两套风格：
 * - [MD3]：标准 Material 3 —— 不透明 tonal 卡片、M3 Card/Button 组件、主题圆角。
 * - [GLASS]：Liquid Glass —— 半透明玻璃卡片 + 左上→右下 0.8dp 折射描边 + 内部微光，
 *   主按钮是实心主题色胶囊 + 外发光（与底栏滑块同一套语言）。
 */
enum class HintStyle { MD3, GLASS }

/** 从设置里读取当前风格。 */
fun currentHintStyle(): HintStyle =
    if (ThemeHelper.isUsingGlass()) HintStyle.GLASS else HintStyle.MD3

@Immutable
data class HintPalette(
    val style: HintStyle = HintStyle.GLASS,
    val accent: Color = Color(0xFFFF9CA8),
    val onAccent: Color = Color.White,
    /** MD3：不透明 tonal 容器色 */
    val card: Color = Color(0xFF1E1E22),
    val onCard: Color = Color(0xFFE6E1E5),
    val variant: Color = Color(0xFFCAC4D0),
    val outline: Color = Color(0xFF938F99),
    val error: Color = Color(0xFFFFB4AB),
    val errorContainer: Color = Color(0xFF8C1D18),
    val onErrorContainer: Color = Color(0xFFFFDAD6),
    /** GLASS：亚克力面板罩层（浅色淡黑 / 深色淡白）与中性细边 */
    val glassFillTop: Color = Color.Black.copy(alpha = 0.06f),
    val glassFillBottom: Color = Color.Black.copy(alpha = 0.09f),
    val glassEdge: Color = Color.Black.copy(alpha = 0.12f),
    val glassHighlight: Color = Color.White.copy(alpha = 0.06f),
    /** GLASS：亚克力面板底下的暗色 scrim（只有深色用） */
    val glassScrim: Color = Color.Transparent,
    /** 标签（「推荐」「Android 11+」这类小胶囊） */
    val tagBackground: Color = Color(0xFF4A4458),
    val onTagBackground: Color = Color(0xFFE8DEF8),
    /** 卡片圆角：MD3 跟随主题，玻璃用略小一点的 22dp 更利落 */
    val cardCorner: Dp = 28.dp,
    val glassCardCorner: Dp = 22.dp,
)

/**
 * 从 Android 主题解析提示页配色（跟随动态取色 / 色板 / 深浅色 / 当前风格）。
 * 所有提示页共用这一份，保证两套风格在各页面完全一致。
 */
/**
 * 提示页配色缓存。
 *
 * [resolveHintPalette] 每次要解析十几个主题属性 + 两三个 TypedArray（圆角 shapeAppearance），
 * 而每个页面组合时都会调一次 —— 换页、切风格、进二级页都在付这笔钱。
 * 颜色只跟「风格 / 深浅色 / 主题色 / 动态取色 / 背景图」有关，把这些组成 key 缓存住即可。
 */
private val paletteCache = HashMap<String, HintPalette>()

fun resolveHintPalette(context: android.content.Context): HintPalette {
    val key = try {
        val prefs = moe.shizuku.manager.ShizukuSettings.getPreferences()
        buildString {
            append(currentHintStyle()).append('|')
            append(context.resources.configuration.uiMode).append('|')
            append(prefs.getString("theme_color", "")).append('|')
            append(prefs.getBoolean(moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR, false)).append('|')
            append(prefs.getBoolean(moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME, false)).append('|')
            append(moe.shizuku.manager.app.BackgroundHelper.isEnabled(context)).append('|')
            append(moe.shizuku.manager.app.BackgroundHelper.isBackgroundDark(context)).append('|')
            append(moe.shizuku.manager.app.BackgroundHelper.imageVersion(context))
        }
    } catch (e: Throwable) {
        "" // 取不到 key 就不缓存，行为退回原来
    }

    if (key.isNotEmpty()) {
        paletteCache[key]?.let { return it }
    }

    val palette = resolveHintPaletteUncached(context)
    if (key.isNotEmpty()) {
        if (paletteCache.size > 6) paletteCache.clear()
        paletteCache[key] = palette
    }
    return palette
}

private fun resolveHintPaletteUncached(context: android.content.Context): HintPalette {
    val theme = context.theme
    fun color(attr: Int): Color = Color(rikka.core.util.ResourceUtils.resolveColor(theme, attr))

    val style = currentHintStyle()
    val night = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    // 卡片圆角跟随主题 shapeAppearanceMediumComponent
    val appearance = theme.obtainStyledAttributes(
        intArrayOf(com.google.android.material.R.attr.shapeAppearanceMediumComponent)
    )
    val appearanceRes = appearance.getResourceId(0, 0)
    appearance.recycle()
    var cornerPx = 0f
    if (appearanceRes != 0) {
        val corner = theme.obtainStyledAttributes(
            appearanceRes,
            intArrayOf(com.google.android.material.R.attr.cornerSize)
        )
        cornerPx = corner.getDimension(0, 0f)
        corner.recycle()
    }

    // 深色下玻璃卡的底几乎等于背景 —— 卡片与背景分不开，字就"被吃掉"了。
    // 这里给玻璃补一层暗色 scrim 并加大白色填充，让卡片重新浮起来；次要文字也提亮一档。
    val onSurface = color(com.google.android.material.R.attr.colorOnSurface)

    // 自定义背景图时的**文字自适应**：按底图整体明暗决定用亮字还是暗字，
    // 以及卡片罩层往白还是往黑偏 —— 否则浅色主题 + 一张夜景照片，标题直接看不见。
    // 只作用于玻璃风格：MD3 保持原样（不改标准模式的观感）。
    val customBg = style == HintStyle.GLASS &&
        moe.shizuku.manager.app.BackgroundHelper.isEnabled(context)
    val darkBg = customBg && moe.shizuku.manager.app.BackgroundHelper.isBackgroundDark(context)
    val adaptiveOnCard = when {
        !customBg -> onSurface
        darkBg -> Color(0xFFF3F3F6)
        else -> Color(0xFF16161C)
    }

    return HintPalette(
        style = style,
        accent = color(com.google.android.material.R.attr.colorPrimary),
        onAccent = color(com.google.android.material.R.attr.colorOnPrimary),
        card = color(com.google.android.material.R.attr.colorSurfaceContainerHighest),
        onCard = adaptiveOnCard,
        variant = when {
            !customBg && night -> onSurface.copy(alpha = 0.88f)
            !customBg -> color(com.google.android.material.R.attr.colorOnSurfaceVariant)
            darkBg -> adaptiveOnCard.copy(alpha = 0.82f)
            else -> adaptiveOnCard.copy(alpha = 0.78f)
        },
        outline = when {
            !customBg && night -> onSurface.copy(alpha = 0.5f)
            !customBg -> color(com.google.android.material.R.attr.colorOutline)
            darkBg -> adaptiveOnCard.copy(alpha = 0.45f)
            else -> adaptiveOnCard.copy(alpha = 0.4f)
        },
        error = color(com.google.android.material.R.attr.colorError),
        errorContainer = color(com.google.android.material.R.attr.colorErrorContainer),
        onErrorContainer = color(com.google.android.material.R.attr.colorOnErrorContainer),
        // 亚克力面板：浅色背景接近纯白，"更白的半透明"看不见 —— 浅色用淡黑罩（≈ surfaceContainer 色调），
        // 深色用淡白罩；细边取中性色，不要"高亮"亮线。面板靠明度差 + 投影分出来
        // 卡片色直接读 View 侧同一批颜色资源（@color/glass_card_*），深浅两套都跟着走 ——
        // Compose 卡片和首页 View 卡片因此是同一块料，不会一边 30% 一边 12%。
        // 有自定义背景图时改成"按底图明暗"取罩层，保证卡片始终能从照片里浮出来。
        glassFillTop = if (customBg && darkBg) {
            Color.White.copy(alpha = 0.14f)
        } else {
            Color(context.getColor(moe.shizuku.manager.R.color.glass_card_fill_top))
        },
        glassFillBottom = if (customBg && darkBg) {
            Color.White.copy(alpha = 0.18f)
        } else {
            Color(context.getColor(moe.shizuku.manager.R.color.glass_card_fill_bottom))
        },
        glassScrim = Color(context.getColor(moe.shizuku.manager.R.color.glass_card_scrim)),
        glassEdge = Color(context.getColor(moe.shizuku.manager.R.color.glass_card_stroke)),
        tagBackground = color(com.google.android.material.R.attr.colorSecondaryContainer),
        onTagBackground = color(com.google.android.material.R.attr.colorOnSecondaryContainer),
        cardCorner = if (cornerPx > 0f) {
            (cornerPx / context.resources.displayMetrics.density).dp
        } else {
            28.dp
        },
    )
}

/**
 * 页面级入场进度（0→1），放在 LazyColumn **外面**，避免项滚进滚出组合时重播动画。
 *
 * 传的是 `State` 而不是 `Float`：值在 `graphicsLayer`（绘制阶段）里读，
 * 整段动画不触发一次重组。
 */private val LocalPageEntrance =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Float>> {
        androidx.compose.runtime.mutableStateOf(1f)
    }

/**
 * Haze 状态：由 [HintPage] 注入 —— 页面最底层（壁纸 / 主题底色）就是模糊源，
 * 卡片用 [HintCard] 里同一份状态的 `hazeEffect` 去取「身后那一块」并做材质合成。
 * 和 Salt UI 的结构一样：`BasicScreen` 提供 backdrop，材质组件在上层取用。
 */
private val LocalHazeState =
    androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/** 页面背景色（没有自定义壁纸时，模糊源就是它）。 */
@Composable
private fun pageSurfaceColor(): Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        Color(
            rikka.core.util.ResourceUtils.resolveColor(
                context.theme,
                com.google.android.material.R.attr.colorSurface,
            ),
        )
    }
}

@Composable
private fun isNightNow(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * 模糊源层：把窗口背景（自定义壁纸，或主题纯色）画在页面最底下，并标记为 Haze 的 source。
 *
 * 壁纸是按**窗口坐标**画的（自己算窗口尺寸 + 页面在窗口里的偏移），
 * 这样它和 View 侧 `window.setBackgroundDrawable` 画出来的位置完全一致，
 * 卡片的模糊取样才不会错位。
 */
@Composable
private fun HintBackdrop(hazeState: dev.chrisbanes.haze.HazeState, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val surface = pageSurfaceColor()

    // 底图版本（文件修改时间）：换图 / 裁切 / 调模糊之后重新解码
    val imageVersion = moe.shizuku.manager.app.BackgroundHelper.imageVersion(context)
    val image = remember(imageVersion) {
        moe.shizuku.manager.app.BackgroundHelper.loadBitmap(context)?.asImageBitmap()
    }

    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    var origin by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val position = coords.positionInWindow()
                origin = androidx.compose.ui.unit.IntOffset(
                    position.x.toInt(),
                    position.y.toInt(),
                )
            }
            .hazeSource(hazeState),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val winW = if (windowSize.width > 0) windowSize.width.toFloat() else size.width
            val winH = if (windowSize.height > 0) windowSize.height.toFloat() else size.height
            // 把「窗口坐标」换算到这块画布里：整体平移 -origin
            val dx = -origin.x
            val dy = -origin.y
            if (image != null) {
                // centerCrop：max(宽比, 高比) 缩放 + 居中裁剪 —— 和 View 侧的
                // CenterCropDrawable 完全同一套算法，两边不会错位，也不会拉伸变形
                val cover = maxOf(winW / image.width, winH / image.height)
                val dw = image.width * cover
                val dh = image.height * cover
                drawImage(
                    image = image,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(image.width, image.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        dx + ((winW - dw) / 2f).toInt(),
                        dy + ((winH - dh) / 2f).toInt(),
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        dw.toInt().coerceAtLeast(1),
                        dh.toInt().coerceAtLeast(1),
                    ),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                )
            } else {
                drawRect(
                    color = surface,
                    topLeft = androidx.compose.ui.geometry.Offset(dx.toFloat(), dy.toFloat()),
                    size = androidx.compose.ui.geometry.Size(winW, winH),
                )
            }
        }
    }
}

/**
 * 卡片入场：不自己写时长曲线，直接用弹簧（Compose 的默认规范），
 * 而且值只在 `graphicsLayer`（绘制阶段）里读 —— 不触发重组、不改布局。
 *
 * 页面级进度由 [HintPage] 驱动（一次动画带动整页），阶梯感用 [index] 做相位偏移。
 *
 * 【性能】原来这里用 `Modifier.composed {}` 包了一层：**每个 item 都会多一个组合包装节点**，
 * 一页二十几张卡就是二十几次额外的组合开销。现在改成普通的 @Composable 扩展函数，
 * 只在组合期取一次进度状态和密度，绘制期照旧只读值 —— 效果一样，节点少一层。
 *
 * 另外：入场只做前 [ENTRANCE_MAX_ITEMS] 个 item，后面的直接给到终态。
 * 长列表（工具箱那种十几张卡的页面）没必要把相位一路排到 0.45 之后，白等一帧的动画。
 */
private const val ENTRANCE_MAX_ITEMS = 8

@Composable
fun Modifier.itemEntrance(index: Int): Modifier {
    val progress = LocalPageEntrance.current
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    val shift = if (index >= ENTRANCE_MAX_ITEMS) 1f else (index * 0.08f).coerceAtMost(0.45f)
    if (shift >= 1f) return this
    return this.graphicsLayer {
        val local = ((progress.value - shift) / (1f - shift)).coerceIn(0f, 1f)
        alpha = local
        translationY = (1f - local) * rise
    }
}

/**
 * 底栏占用的高度（只有顶层 Tab 才有底栏，二级页为 0）。
 *
 * 底栏浮在内容之上：MD3 是贴底整条（80dp，背景把手势条一起铺掉），
 * 玻璃是悬浮胶囊（76dp + 4dp 外边距，再叠手势条）。Compose 页面拿不到它的尺寸，
 * 所以统一从这里取 —— 之前只在 RecyclerView 那边补了内边距，
 * 结果控制台最下面那排「宏」被 MD3 底栏盖住。
 */
@Composable
fun navBottomSpace(): Dp {
    if (!moe.shizuku.manager.ui.nav.NavUiState.visible) return 0.dp
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val px = if (moe.shizuku.manager.ui.style.UiStyle.isGlass) {
        resources.getDimensionPixelSize(moe.shizuku.manager.R.dimen.ksu_nav_height_outer) +
            resources.getDimensionPixelSize(moe.shizuku.manager.R.dimen.ksu_nav_outer_margin)
    } else {
        resources.getDimensionPixelSize(moe.shizuku.manager.R.dimen.ksu_nav_height_md3)
    } + moe.shizuku.manager.ui.nav.NavUiState.bottomInsetPx
    return with(androidx.compose.ui.platform.LocalDensity.current) { px.toDp() }
}

/**
 * 提示页骨架：统一内边距 + 折叠大标题联动 + 卡片弹簧入场。
 *
 * 注意：**不要**再自己加 `?actionBarSize` 的上内边距 —— 宿主
 * `appbar_scrolling_view_behavior` 已经把内容放到大标题栏下方了，
 * 再加一次就会出现「标题与大标题之间空一大块」的双重偏移。
 */
@Composable
fun HintPage(
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
            .distinctUntilChanged()
            .collect { collapsed -> onCollapsedChange(!collapsed) }
    }

    // 入场：一次动画（spring）驱动整页所有卡片，进度状态在 LazyColumn 之外
    val entrance = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(listState) {
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.85f,
                stiffness = 320f,
            ),
        )
    }

    // 材质底：Haze（Salt UI 的 blurryGlass / acrylic / mica 都跑在它上面）
    val hazeState = dev.chrisbanes.haze.rememberHazeState()

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPageEntrance provides entrance.asState(),
        LocalHazeState provides hazeState,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // 模糊源：壁纸 / 主题底色（卡片的材质从这层取像）。
            // 只有开了 Haze 材质才画 —— 否则壁纸由窗口背景那层画就够了，
            // 再画一遍等于每帧多一次全屏绘制（滚动时白掉帧）。
            val materialOn = currentHintStyle() == HintStyle.GLASS &&
                moe.shizuku.manager.ui.glass.GlassMaterials.isEnabled(
                    moe.shizuku.manager.ui.glass.GlassMaterials.kind(),
                )
            if (materialOn) {
                HintBackdrop(hazeState, Modifier.matchParentSize())
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    // 24dp 常规留白 + 底栏高度（顶层 Tab 才有）：最后一张卡 / 控制台的
                    // 「宏」列表要能完整滚到悬浮底栏上方，不能被盖住
                    bottom = 24.dp + navBottomSpace(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/**
 * 双风格卡片。
 * MD3 = M3 Card（filled，无阴影，theme 圆角）；
 * GLASS = 半透明玻璃 + 内部微光 + 对角折射描边。
 */
@Composable
fun HintCard(
    palette: HintPalette,
    modifier: Modifier = Modifier,
    errorTone: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val onColor = if (errorTone) {
        if (palette.style == HintStyle.MD3) palette.onErrorContainer else palette.onCard
    } else {
        palette.onCard
    }

    if (palette.style == HintStyle.MD3) {
        Card(
            modifier = modifier.fillMaxWidth().animateContentSize(),
            shape = RoundedCornerShape(palette.cardCorner),
            colors = CardDefaults.cardColors(
                containerColor = if (errorTone) palette.errorContainer else palette.card,
                contentColor = onColor,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
        return
    }

    // 圆角与首页卡片一致（主题 shapeAppearanceMediumComponent，当前 28dp）
    val corner = palette.cardCorner
    val cardShape = RoundedCornerShape(corner)

    // ★ 材质：照搬 Salt UI 的那套 Haze 材质（毛玻璃 / 亚克力 / 云母）。
    //   卡片从页面底层的模糊源取像，按配方做「模糊 + 着色 + 噪点」合成 —— 所以背景图上
    //   卡片是真的把身后那一片糊上来，而不是盖一层半透明色。
    val hazeState = LocalHazeState.current
    val materialKind = moe.shizuku.manager.ui.glass.GlassMaterials.kind()
    val surface = pageSurfaceColor()
    val isDark = isNightNow()
    val materialStyle = if (hazeState != null &&
        moe.shizuku.manager.ui.glass.GlassMaterials.isEnabled(materialKind)
    ) {
        moe.shizuku.manager.ui.glass.GlassMaterials.style(
            kind = materialKind,
            layer = moe.shizuku.manager.ui.glass.GlassLayer.SUB_BACKGROUND,
            background = surface,
            isDark = isDark,
        )
    } else {
        null
    }
    val noiseContext = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 高度变化（状态文案、展开收起）走官方 animateContentSize（默认就是 spring）
            .animateContentSize()
            // 内部圆角保持（内容不被裁成方角）
            .clip(cardShape)
            .then(
                if (hazeState != null && materialStyle != null) {
                    androidx.compose.ui.Modifier.hazeEffect(hazeState, materialStyle)
                } else {
                    androidx.compose.ui.Modifier
                },
            ),
    ) {
        if (hazeState == null || materialStyle == null) {
            // 没有 Haze 源（独立使用卡片时）→ 退回老样子：同一块料 + 噪点纹理。
            // 【性能】「固体底 + 噪点」合并成同一个绘制节点：滚动时每张卡片少画一层
            // （工具箱那种大卡片多、滚动量大的页面收益最明显）；外层 clip(cardShape)
            // 仍在，圆角与观感和合并前完全一致。
            val fallbackSurface =
                if (errorTone) palette.error.copy(alpha = 0.30f) else palette.glassFillTop
            val noise = remember {
                android.graphics.BitmapFactory.decodeResource(
                    noiseContext.resources,
                    moe.shizuku.manager.R.drawable.acrylic_noise,
                ).asImageBitmap()
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val shader = androidx.compose.ui.graphics.ImageShader(
                            image = noise,
                            tileModeX = androidx.compose.ui.graphics.TileMode.Repeated,
                            tileModeY = androidx.compose.ui.graphics.TileMode.Repeated,
                        )
                        val shaderBrush = androidx.compose.ui.graphics.ShaderBrush(shader)
                        onDrawBehind {
                            drawRect(color = fallbackSurface)
                            drawRect(brush = shaderBrush)
                        }
                    },
            )
        }
        if (errorTone) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(color = palette.error.copy(alpha = 0.22f), shape = cardShape),
            )
        }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides onColor,
            ) { content() }
        }
    }
}

/** 图标 + 正文的说明卡（信息 / 警告）。 */
@Composable
fun HintNote(
    palette: HintPalette,
    iconRes: Int,
    text: String,
    modifier: Modifier = Modifier,
    errorTone: Boolean = false,
    secondary: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val contentColor = if (errorTone) {
        if (palette.style == HintStyle.MD3) palette.onErrorContainer else palette.onCard
    } else {
        palette.onCard
    }
    HintCard(palette = palette, errorTone = errorTone, modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (errorTone) {
                    if (palette.style == HintStyle.MD3) palette.onErrorContainer else palette.error
                } else {
                    palette.accent
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
                if (secondary != null) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                    )
                }
                action?.invoke()
            }
        }
    }
}

/** 小节标题（「操作步骤」「注意事项」）。 */
@Composable
fun HintSectionTitle(palette: HintPalette, text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = palette.variant,
        modifier = modifier.padding(top = 4.dp),
    )
}

/**
 * 步骤时间线的一步：左侧圆形序号（可带向下连接线），右侧标题 + 正文（可带提示与按钮）。
 * 连接线让三步在视觉上连成一条流程。
 */
@Composable
fun HintStep(
    palette: HintPalette,
    index: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    showConnector: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val glass = palette.style == HintStyle.GLASS
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier.width(28.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .then(
                        if (glass) {
                            Modifier
                                .background(palette.accent.copy(alpha = 0.20f))
                                .drawBehind {
                                    drawCircle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                palette.glassEdge.copy(alpha = 0.5f),
                                                Color.Transparent,
                                            ),
                                            start = Offset(0f, 0f),
                                            end = Offset(size.width, size.height),
                                        ),
                                        style = Stroke(width = 0.8.dp.toPx()),
                                    )
                                }
                        } else {
                            Modifier.background(palette.accent)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    // 自适应：序号压在自己的圆底上（玻璃是强调色薄纱、MD3 是实心强调色）
                    color = if (glass) {
                        ensureReadable(palette.accent, palette.glassFillTop)
                    } else {
                        ensureReadable(palette.onAccent, palette.accent)
                    },
                )
            }
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(if (glass) 1.5.dp else 2.dp)
                        .weight(1f)
                        .padding(top = 6.dp)
                        .background(
                            if (glass) palette.accent.copy(alpha = 0.35f) else palette.outline.copy(alpha = 0.6f),
                        ),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f).padding(bottom = if (showConnector) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.onCard,
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = palette.variant)
            if (hint != null) {
                Text(text = hint, style = MaterialTheme.typography.bodySmall, color = palette.error)
            }
            content?.invoke()
        }
    }
}

/** 紧凑提示行（放在「注意事项」卡里）：图标 + 短标题 + 说明。 */
@Composable
fun HintNoteRow(
    palette: HintPalette,
    iconRes: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    errorTone: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val titleColor = when {
        errorTone && palette.style == HintStyle.MD3 -> palette.onErrorContainer
        errorTone -> palette.error
        else -> palette.onCard
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (errorTone) palette.error else palette.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor,
            )
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = palette.variant)
            action?.invoke()
        }
    }
}

/** 深色模式按钮配色 token（禁用/启用对比、描边、文字色都从这里取，避免各处写死）。 */
private object ButtonTokens {
    /** 启用态首选文字色：接近纯白（深色底上对比最高） */
    val TextLight = Color(0xFFFFFFFF)

    /** 启用态次级文字色：浅灰（压在中等亮度的底上更柔和，仍然清晰） */
    val TextSoftLight = Color(0xFFE0E0E0)

    /** 禁用态：M3 标准做法 —— 底 12% 前景色、字 38% 前景色，一眼看出不能点 */
    const val DISABLED_CONTAINER_ALPHA = 0.12f
    const val DISABLED_CONTENT_ALPHA = 0.38f

    /** 次按钮的底与描边（让它和卡片背景明显区分） */
    const val OUTLINED_CONTAINER_ALPHA = 0.08f
    const val OUTLINED_STROKE_ALPHA = 0.30f
}

/**
 * 自适应文字色：同一段文字要压在什么颜色的底上，就按对比度挑一个看得清的颜色。
 *
 * 为什么需要它：主按钮的底是主题强调色、次按钮的字直接压在卡片上，
 * 而强调色会随动态取色 / 色板 / 深浅色变化 —— 写死 onPrimary / accent 总有读不清的组合。
 * 这里按 WCAG 相对亮度算对比度，候选色里挑对比更高的那个（都不够就再拉黑/白）。
 */
internal fun readableTextOn(background: Color, preferred: Color? = null): Color {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    fun luminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    fun ratio(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    val candidates = buildList {
        if (preferred != null) add(preferred)
        add(Color.White)
        add(Color.Black)
    }
    // 阈值取 4.5:1（WCAG AA 正文标准）；达不到就挑最高的那个
    return candidates.maxByOrNull { ratio(background, it) } ?: Color.White
}

/** 在给定的底上把颜色调到"至少能看清"的程度：够亮就原样用，不够就换成自适应色。 */
internal fun ensureReadable(foreground: Color, background: Color, minRatio: Float = 4.5f): Color {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    fun luminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    val lf = luminance(foreground)
    val lb = luminance(background)
    val ratio = (maxOf(lf, lb) + 0.05f) / (minOf(lf, lb) + 0.05f)
    return if (ratio >= minRatio) foreground else readableTextOn(background, foreground)
}

/**
 * 主操作：MD3 Filled Button / 玻璃实心胶囊（带外发光）。
 *
 * 文字色不写死：按按钮底色算对比度自适应（MD3 下强调色可能偏亮/偏暗，
 * 写死的 onPrimary 会出现"看不清"的组合）。
 */
@Composable
fun HintPrimaryButton(
    palette: HintPalette,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (palette.style == HintStyle.MD3) {
        // 【修改】文字色不再用可能和底色撞色的 onAccent：
        // 首选纯白（深色底上对比最高），对比度不足时自动退回黑/白里更清楚的那个。
        val contentColor = readableTextOn(palette.accent, preferred = ButtonTokens.TextLight)
        // 【修改】禁用态：M3 标准（底 = 前景色 12%、字 = 前景色 38%），和启用态一眼区分
        val disabledContainer = palette.onCard.copy(alpha = ButtonTokens.DISABLED_CONTAINER_ALPHA)
        val disabledContent =
            palette.onCard.copy(alpha = ButtonTokens.DISABLED_CONTENT_ALPHA)
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = contentColor,
                disabledContainerColor = disabledContainer,
                disabledContentColor = disabledContent,
            ),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    val shape = RoundedCornerShape(24.dp)
    // 按下 / 禁用：颜色 + 投影 + 缩放三个属性必须"同时"起止 →
    // 文档里的 updateTransition 分支（多个属性、需要同时开始）。
    val interaction = rememberPressInteractionSource()
    val pressed by interaction.collectIsPressedAsState()
    val visual = when {
        !enabled -> VISUAL_DISABLED
        pressed -> VISUAL_PRESSED
        else -> VISUAL_IDLE
    }
    val transition = updateTransition(visual, label = "glassPrimaryButton")
    // 颜色单独用 animateColorAsState（Transition 没有 animateColor 扩展），
    // 投影 / 缩放走上面的 updateTransition：三个属性都在同一状态下起止
    val container by androidx.compose.animation.animateColorAsState(
        targetValue = when (visual) {
            VISUAL_DISABLED -> palette.accent.copy(alpha = 0.35f)
            VISUAL_PRESSED -> palette.accent.copy(alpha = 0.82f)
            else -> palette.accent
        },
        label = "container",
    )
    // 投影用 animateDp 驱动、在 graphicsLayer 里读（文档：给阴影做动画用 graphicsLayer 更省）
    val elevation by transition.animateDp(label = "elevation") { s ->
        when (s) {
            VISUAL_DISABLED -> 0.dp
            VISUAL_PRESSED -> 4.dp
            else -> 14.dp
        }
    }
    val scale by transition.animateFloat(label = "scale") { s ->
        if (s == VISUAL_PRESSED) 0.97f else 1f
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = true
                scaleX = scale
                scaleY = scale
            }
            .background(container)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(color = palette.onAccent),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            // 自适应：压在强调色胶囊上，对比不够就换黑/白
            color = ensureReadable(palette.onAccent, container),
            // 玻璃胶囊没有 M3 Button 那套默认内边距，自己补上，
            // 否则 wrap_content 时按钮只有一个字那么大
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

/** 次操作：MD3 Outlined Button / 玻璃半透明胶囊（同样 48dp 高）。 */
@Composable
fun HintSecondaryButton(
    palette: HintPalette,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (palette.style == HintStyle.MD3) {
        // 【修改】次按钮的底和卡片太像 → 给一层 8% 前景色的底 + 1dp 描边，和卡片明显区分；
        // 文字优先用浅灰 #E0E0E0（深色底上清晰），对比不够时自动换更清楚的颜色。
        val containerColor = palette.onCard.copy(alpha = ButtonTokens.OUTLINED_CONTAINER_ALPHA)
        val borderColor = palette.onCard.copy(alpha = ButtonTokens.OUTLINED_STROKE_ALPHA)
        // 8% 前景色压在卡片上的实际颜色（手工线性混合，等价于 compositeOver）
        val effectiveBackground = androidx.compose.ui.graphics.lerp(
            palette.card,
            palette.onCard,
            ButtonTokens.OUTLINED_CONTAINER_ALPHA,
        )
        val contentColor = readableTextOn(
            background = effectiveBackground,
            preferred = ButtonTokens.TextSoftLight,
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, borderColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                // 【修改】禁用态：底更透、字 38%，和启用态区分
                disabledContainerColor =
                    palette.onCard.copy(alpha = ButtonTokens.DISABLED_CONTAINER_ALPHA),
                disabledContentColor =
                    palette.onCard.copy(alpha = ButtonTokens.DISABLED_CONTENT_ALPHA),
            ),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    val shape = RoundedCornerShape(24.dp)
    val interaction = rememberPressInteractionSource()
    val pressed by interaction.collectIsPressedAsState()
    // 次按钮也走 updateTransition：底色 + 缩放同时起止
    val transition = updateTransition(
        when {
            !enabled -> VISUAL_DISABLED
            pressed -> VISUAL_PRESSED
            else -> VISUAL_IDLE
        },
        label = "glassSecondaryButton",
    )
    val container by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            !enabled -> palette.glassFillTop.copy(alpha = 0.06f)
            pressed -> palette.accent.copy(alpha = 0.22f)
            else -> palette.glassFillTop
        },
        label = "container",
    )
    val scale by transition.animateFloat(label = "scale") { s ->
        if (s == VISUAL_PRESSED) 0.97f else 1f
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                this.shape = shape
                clip = true
                scaleX = scale
                scaleY = scale
            }
            .background(container)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(palette.glassEdge.copy(alpha = 0.5f), Color.Transparent),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(color = palette.accent),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            // 【修改】文字色按底自适应：启用态优先浅灰 #E0E0E0（深色底上清晰），
            // 禁用态整体压到 38% —— 一眼分清能不能点。
            color = if (enabled) {
                readableTextOn(
                    background = androidx.compose.ui.graphics.lerp(palette.card, container, 1f),
                    preferred = ButtonTokens.TextSoftLight,
                )
            } else {
                palette.onCard.copy(alpha = ButtonTokens.DISABLED_CONTENT_ALPHA)
            },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}