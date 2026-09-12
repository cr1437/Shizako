package moe.shizuku.manager.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import androidx.collection.LruCache
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min

/**
 * 卡片文字自适应：**按卡片身后那块背景图**选黑字还是白字。
 *
 * 三种做法都在这里，按需组合：
 *
 * 1. [fromPalette] —— androidx.palette 的 `dominantSwatch`，取它的 `titleTextColor` /
 *    `bodyTextColor`（Palette 自己已经算好对比度，直接给黑或白）。swatch 为 null（纯色图、
 *    透明图、解码失败）时返回 null，交给下面两种兜底。
 * 2. [contrastColor] —— 不依赖 Palette：缩到 16×16，按感知亮度
 *    `(0.299R + 0.587G + 0.114B) / 255` 求平均，大于阈值给黑、否则给白。阈值可配。
 * 3. [TextColors.bodyScrim] —— 不是文字色，是**兜底**：实在判不准（比如背景是半黑半白的
 *    渐变照片）就给副标题加一层淡 scrim，保证任何背景上都读得清。
 *
 * 默认走 2 秒开：它是纯 CPU 的 16×16 采样，几百微秒级，可以直接在 `onBindViewHolder`
 * 里同步算；1 的 Palette 量化是毫秒到几十毫秒级，请放到后台线程（见 [preloadPalette]）。
 */
object AdaptiveTextHelper {

    /** 感知亮度阈值：> 阈值 → 背景偏亮 → 用黑字 */
    const val DEFAULT_LUMINANCE_THRESHOLD = 0.5f

    /** 采样尺寸：16×16 足够代表一块区域的明暗，又便宜到可以每次 bind 都算 */
    private const val SAMPLE_SIZE = 16

    /** 副标题在自适应文字上的透明度（比标题弱一档，但仍然可读） */
    private const val BODY_ALPHA = 0.78f

    /**
     * 一组自适应文字色。
     *
     * @param title 标题色（纯黑 / 纯白）
     * @param body 副标题色（同色系，降一点透明度）
     * @param source 这组色是怎么来的，便于排查（"palette" / "luminance" / "fallback"）
     */
    data class TextColors(
        val title: Int,
        val body: Int,
        val source: String,
    ) {
        /** 需要额外压一层 scrim 吗（判不准的时候） */
        val needsScrim: Boolean get() = source == SOURCE_FALLBACK

        companion object {
            const val SOURCE_PALETTE = "palette"
            const val SOURCE_LUMINANCE = "luminance"
            const val SOURCE_FALLBACK = "fallback"
        }
    }

    private const val CACHE_MAX = 64

    /** 区域亮度缓存：key = 底图版本 + 区域 + 阈值 */
    private val luminanceCache = object : LruCache<String, Float>(CACHE_MAX) {}

    /** Palette 结果缓存（后台线程算好后主线程直接取） */
    private val paletteCache = object : LruCache<String, TextColors>(CACHE_MAX) {}

    // ---------------- 2. 纯亮度（默认路径，快，可同步调用） ----------------

    /**
     * 一块背景区域的平均感知亮度（0..1）。
     *
     * @param region 图片坐标里的区域（null = 整张图）；越界会自动裁到图内
     */
    fun averageLuminance(
        bitmap: Bitmap,
        region: RectF? = null,
        cacheKey: String? = null,
    ): Float {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 1f

        val key = cacheKey?.let { "lum:$it:${region?.toShortString()}" }
        if (key != null) luminanceCache.get(key)?.let { return it }

        // 先抠区域，再缩到 16×16：两步都便宜，且能避开整图缩放的开销
        val sampled = sample(bitmap, region)
        val pixels = IntArray(sampled.width * sampled.height)
        sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
        if (sampled !== bitmap) sampled.recycle()

        var sum = 0.0
        var count = 0
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 8) continue // 全透明像素不参与（否则会把透明区当黑）
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // 感知亮度：人眼对绿色最敏感、蓝色最不敏感
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            count++
        }
        val value = if (count == 0) 1f else (sum / count).toFloat()
        if (key != null) luminanceCache.put(key, value)
        return value
    }

    /** 亮度 → 文字色：亮背景给黑字，暗背景给白字。阈值可配。 */
    fun contrastColor(
        luminance: Float,
        threshold: Float = DEFAULT_LUMINANCE_THRESHOLD,
    ): Int = if (luminance > threshold) Color.BLACK else Color.WHITE

    /** 亮度直接出结果（标题 + 副标题） */
    fun fromLuminance(
        luminance: Float,
        threshold: Float = DEFAULT_LUMINANCE_THRESHOLD,
    ): TextColors {
        val base = contrastColor(luminance, threshold)
        return TextColors(
            title = base,
            body = withAlpha(base, BODY_ALPHA),
            source = TextColors.SOURCE_LUMINANCE,
        )
    }

    // ---------------- 1. Palette（更"聪明"，但要放后台线程） ----------------

    /**
     * 用 androidx.palette 从这张图（或区域）取主色 swatch，直接拿它的
     * `titleTextColor` / `bodyTextColor`。
     *
     * **注意**：Palette 的量化是毫秒级工作，别在主线程对每张卡片反复调；
     * 结果会进缓存，同一张图 + 同一区域只算一次。
     *
     * @return swatch 为 null（纯色图 / 全透明 / 解码失败）时返回 null
     */
    fun fromPalette(
        bitmap: Bitmap,
        region: RectF? = null,
        cacheKey: String? = null,
    ): TextColors? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        val key = cacheKey?.let { "pal:$it:${region?.toShortString()}" }
        if (key != null) paletteCache.get(key)?.let { return it }

        val sampled = sample(bitmap, region)
        val palette = try {
            Palette.from(sampled).clearFilters().generate()
        } catch (e: Throwable) {
            null
        } finally {
            if (sampled !== bitmap) sampled.recycle()
        } ?: return null

        val swatch = palette.dominantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch
            ?: return null

        // 这就是我们要的：Palette 已经按对比度算好了黑或白
        val result = TextColors(
            title = swatch.titleTextColor,
            body = swatch.bodyTextColor,
            source = TextColors.SOURCE_PALETTE,
        )
        if (key != null) paletteCache.put(key, result)
        return result
    }

    /** 后台线程预热 Palette，主线程 [resolve] 时就能命中缓存。 */
    fun preloadPalette(
        bitmap: Bitmap,
        region: RectF? = null,
        cacheKey: String? = null,
        onReady: ((TextColors) -> Unit)? = null,
    ) {
        Thread {
            val colors = fromPalette(bitmap, region, cacheKey)
            if (colors != null && onReady != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(colors) }
            }
        }.start()
    }

    // ---------------- 组合：Palette 优先，失败回落亮度，再不行给兜底 ----------------

    /**
     * @param preferPalette true = 先看缓存里有没有 Palette 结果（不现算，避免卡界面）；
     *                      false = 直接走亮度
     */
    fun resolve(
        bitmap: Bitmap?,
        region: RectF? = null,
        threshold: Float = DEFAULT_LUMINANCE_THRESHOLD,
        cacheKey: String? = null,
        preferPalette: Boolean = true,
    ): TextColors {
        if (bitmap == null || bitmap.isRecycled) {
            // 没有背景图（纯色主题底）→ 交给主题，不做自适应
            return TextColors(Color.TRANSPARENT, Color.TRANSPARENT, TextColors.SOURCE_FALLBACK)
        }

        if (preferPalette && cacheKey != null) {
            paletteCache.get("pal:$cacheKey:${region?.toShortString()}")?.let { return it }
        }
        return fromLuminance(averageLuminance(bitmap, region, cacheKey), threshold)
    }

    // ---------------- 直接套到 View 上 ----------------

    /**
     * 给卡片上的标题 / 副标题上自适应色。
     *
     * @param card 卡片本身（用它算"身后那块背景"的范围）
     * @param title 标题 TextView
     * @param body 副标题 TextView（可为 null）
     * @return 真正生效的 [TextColors]，null 表示没有背景图、没动颜色
     */
    fun applyToCard(
        card: View,
        title: TextView,
        body: TextView? = null,
        threshold: Float = DEFAULT_LUMINANCE_THRESHOLD,
    ): TextColors? {
        val context = card.context.applicationContext
        val bitmap = BackgroundHelper.cachedBitmap(context) ?: return null
        val window = windowSize(card) ?: return null
        val region = regionBehind(card, bitmap, window)
        val cacheKey = "${BackgroundHelper.imageVersion(context)}:${bitmap.width}x${bitmap.height}"

        val colors = resolve(bitmap, region, threshold, cacheKey)
        apply(title, body, colors)
        return colors
    }

    /** 直接上色（外部已经算好亮度/取好色时用） */
    fun apply(title: TextView, body: TextView?, colors: TextColors) {
        if (colors.title != Color.TRANSPARENT) title.setTextColor(colors.title)
        if (body != null && colors.body != Color.TRANSPARENT) body.setTextColor(colors.body)
    }

    /**
     * 卡片身后的背景图像素区域（图片坐标）。
     *
     * 背景图是按 `centerCrop` 铺满窗口的，所以先把"窗口坐标 → 图片坐标"的映射算出来，
     * 再把卡片的窗口矩形换算过去 —— 这样取到的才是卡片**真正压着的那块**，
     * 而不是整张图的平均色（整图平均在"上黑下白"的照片上必然判错）。
     */
    fun regionBehind(card: View, bitmap: Bitmap, window: Point): RectF? {
        if (window.x <= 0 || window.y <= 0) return null

        // centerCrop：和 CenterCropDrawable / Compose 背景用同一套算法
        val scale = max(window.x.toFloat() / bitmap.width, window.y.toFloat() / bitmap.height)
        val drawnW = bitmap.width * scale
        val drawnH = bitmap.height * scale
        val originX = (window.x - drawnW) / 2f
        val originY = (window.y - drawnH) / 2f

        val location = IntArray(2)
        card.getLocationInWindow(location)

        val left = (location[0] - originX) / scale
        val top = (location[1] - originY) / scale
        val right = (location[0] + card.width - originX) / scale
        val bottom = (location[1] + card.height - originY) / scale

        val rect = RectF(left, top, right, bottom)
        // 夹到图内；宽高太小（卡片完全在图外）就直接给整图
        rect.left = rect.left.coerceIn(0f, bitmap.width.toFloat())
        rect.top = rect.top.coerceIn(0f, bitmap.height.toFloat())
        rect.right = rect.right.coerceIn(0f, bitmap.width.toFloat())
        rect.bottom = rect.bottom.coerceIn(0f, bitmap.height.toFloat())
        if (rect.width() < 2f || rect.height() < 2f) return null
        return rect
    }

    // ---------------- 4. 对比度驱动（WCAG）：先算够不够，再决定加多少遮罩 ----------------

    /**
     * 一张卡片的文字方案。
     *
     * @param textColor 文字颜色（黑或白）
     * @param scrimColor 遮罩颜色（黑或白，和文字相反）
     * @param scrimAlpha 遮罩需要多少不透明度（**算出来的**，不是拍脑袋的 70%；0 = 不需要遮罩）
     * @param minContrast 加上遮罩后，区域里最差的那个像素能达到的对比度
     */
    data class TextPlan(
        val textColor: Int,
        val scrimColor: Int,
        val scrimAlpha: Float,
        val minContrast: Double,
    )

    /** WCAG 正文对比度下限：4.5:1（大字号 18sp / 14sp 粗体可放宽到 3:1） */
    const val MIN_CONTRAST_BODY = 4.5
    const val MIN_CONTRAST_LARGE = 3.0

    /**
     * 给一块背景算文字方案：**不是取平均亮度拍一个阈值，而是按 WCAG 对比度算**。
     *
     * 做法（和 AOSP `ContrastColorUtil` / `ColorUtils.calculateContrast` 一个思路）：
     * 1. 把区域缩成 16×16，得到 256 个像素；
     * 2. 两个候选：白字 + 黑遮罩、黑字 + 白遮罩；
     * 3. 对每个候选，找**最差的那个像素**（对比度最低的），必要时按 5% 步进加遮罩，
     *    直到最差像素也能达到 [minRatio]；
     * 4. 选需要遮罩更少的那个候选（都不需要就按 [preferWhite]）。
     *
     * 好处：半黑半白的照片也能判对 —— 因为看的是"最差的像素"，
     * 平均值那套在渐变/花哨照片上必然翻车。
     *
     * @return null = 没有背景图
     */
    fun planForRegion(
        bitmap: Bitmap?,
        region: RectF? = null,
        minRatio: Double = MIN_CONTRAST_BODY,
        preferWhite: Boolean = true,
    ): TextPlan? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0) return null
        val sampled = sample(bitmap, region)
        val w = sampled.width
        val h = sampled.height
        val pixels = IntArray(w * h)
        sampled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (sampled !== bitmap) sampled.recycle()

        // 只留有内容的像素（全透明的忽略）
        val opaque = pixels.filter { ((it ushr 24) and 0xFF) >= 8 }
        if (opaque.isEmpty()) return null

        val whitePlan = searchScrim(opaque, Color.WHITE, Color.BLACK, minRatio)
        val blackPlan = searchScrim(opaque, Color.BLACK, Color.WHITE, minRatio)

        return when {
            whitePlan == null && blackPlan == null -> null
            whitePlan == null -> blackPlan
            blackPlan == null -> whitePlan
            // 都行的时候：谁需要的遮罩更少用谁；一样多就看偏好
            whitePlan.scrimAlpha < blackPlan.scrimAlpha -> whitePlan
            blackPlan.scrimAlpha < whitePlan.scrimAlpha -> blackPlan
            else -> if (preferWhite) whitePlan else blackPlan
        }
    }

    /** 对给定文字色，二分步进找"刚好够"的遮罩不透明度；加到满还是不够就返回 null。 */
    private fun searchScrim(
        pixels: List<Int>,
        textColor: Int,
        scrimColor: Int,
        minRatio: Double,
    ): TextPlan? {
        var alpha = 0f
        while (alpha <= 1.0001f) {
            val scrim = androidx.core.graphics.ColorUtils.setAlphaComponent(
                scrimColor,
                (alpha * 255).toInt().coerceIn(0, 255),
            )
            // 最差像素 = 加完遮罩后对比度最低的那个
            var worst = Double.MAX_VALUE
            for (pixel in pixels) {
                val blended = androidx.core.graphics.ColorUtils.compositeColors(scrim, pixel or 0xFF000000.toInt())
                val contrast = androidx.core.graphics.ColorUtils.calculateContrast(textColor, blended)
                if (contrast < worst) worst = contrast
                if (worst == 0.0) break
            }
            if (worst >= minRatio) {
                return TextPlan(
                    textColor = textColor,
                    scrimColor = scrimColor,
                    scrimAlpha = alpha,
                    minContrast = worst,
                )
            }
            alpha += 0.05f
        }
        return null
    }

    /**
     * 按方案生成遮罩 drawable：从完全透明渐变到 [TextPlan.scrimAlpha]。
     *
     * 和 XML 里那张固定 70% 的遮罩不同 —— 这里的不透明度是算出来的：
     * 背景本来就够暗（比如夜景照片）就基本不加，背景很亮（雪景/白墙）才会加到接近满。
     */
    fun scrimDrawable(
        plan: TextPlan,
        stops: FloatArray = floatArrayOf(0f, 0.35f, 0.65f, 1f),
    ): android.graphics.drawable.GradientDrawable {
        val colors = IntArray(stops.size)
        val full = androidx.core.graphics.ColorUtils.setAlphaComponent(
            plan.scrimColor,
            (plan.scrimAlpha * 255).toInt().coerceIn(0, 255),
        )
        for (i in stops.indices) {
            val alpha = (plan.scrimAlpha * stops[i] * 255).toInt().coerceIn(0, 255)
            colors[i] = androidx.core.graphics.ColorUtils.setAlphaComponent(plan.scrimColor, alpha)
        }
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if (plan.scrimAlpha <= 0f) IntArray(stops.size) { Color.TRANSPARENT } else colors,
        )
    }

    // ---------------- 内部工具 ----------------

    /** 抠区域 + 缩到 [SAMPLE_SIZE]；区域为 null 时直接缩整图 */
    private fun sample(bitmap: Bitmap, region: RectF?): Bitmap {
        val crop = if (region != null) {
            val x = region.left.toInt().coerceIn(0, bitmap.width - 1)
            val y = region.top.toInt().coerceIn(0, bitmap.height - 1)
            val w = min(region.width().toInt().coerceAtLeast(1), bitmap.width - x)
            val h = min(region.height().toInt().coerceAtLeast(1), bitmap.height - y)
            try {
                Bitmap.createBitmap(bitmap, x, y, w, h)
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }

        val src = crop ?: bitmap
        val scaled = Bitmap.createScaledBitmap(src, SAMPLE_SIZE, SAMPLE_SIZE, true)
        if (crop != null && crop !== scaled) crop.recycle()
        return scaled
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun windowSize(view: View): Point? {
        val windowManager = view.context.getSystemService(android.content.Context.WINDOW_SERVICE)
            as? android.view.WindowManager ?: return null
        val bounds: android.graphics.Rect = if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.R
        ) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            android.graphics.Rect(0, 0, size.x, size.y)
        }
        return Point(bounds.width(), bounds.height())
    }
}
