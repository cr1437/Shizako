package moe.shizuku.manager.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import moe.shizuku.manager.ShizukuSettings
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义背景图片：原图存一份、按「模糊 / 亮暗」处理后再存一份，窗口直接用它当底。
 *
 * - 模糊：把图**缩小再放大**（廉价高斯近似），比 RenderEffect 兼容性好（全 API 可用）；
 * - 亮暗：在处理后的图上叠一层黑色（alpha = 暗度），所以"调亮"= 减小暗度；
 * - 处理结果缓存在 filesDir，改滑块时后台重算一次，然后直接 `window.setBackgroundDrawable`，
 *   不需要重建 Activity。
 */
object BackgroundHelper {

    const val KEY_BLUR = "bg_blur"   // 0..24（越大越糊）
    const val KEY_DIM = "bg_dim"     // 0..200（黑色叠加的不透明度）

    private const val SRC = "custom_bg_src"
    private const val ORIG = "custom_bg_orig"
    private const val PROC = "custom_bg_proc.png"
    private const val MAX_EDGE = 1600

    /** 日志 tag：模糊/亮暗这条链路出问题时用 `adb logcat -s ShizakoBg` 看 */
    private const val TAG = "ShizakoBg"

    fun sourceFile(context: Context): File = File(context.filesDir, SRC)

    /** 用户选进来的原始图（裁切永远从它开始，反复裁不会越裁越糊） */
    fun originalFile(context: Context): File = File(context.filesDir, ORIG)

    fun processedFile(context: Context): File = File(context.filesDir, PROC)

    fun isEnabled(context: Context): Boolean = sourceFile(context).exists()

    fun hasOriginal(context: Context): Boolean = originalFile(context).exists()

    fun blur(context: Context): Int = ShizukuSettings.getPreferences().getInt(KEY_BLUR, 10)
    fun dim(context: Context): Int = ShizukuSettings.getPreferences().getInt(KEY_DIM, 60)

    fun setBlur(context: Context, value: Int) {
        ShizukuSettings.getPreferences().edit().putInt(KEY_BLUR, value.coerceIn(0, 24)).apply()
    }

    fun setDim(context: Context, value: Int) {
        ShizukuSettings.getPreferences().edit().putInt(KEY_DIM, value.coerceIn(0, 200)).apply()
    }

    /**
     * 把用户选中的图片收进 filesDir（避免依赖外部 URI 权限）。
     *
     * 原图另存一份（ORIG），当前使用图（SRC）默认等于原图 —— 之后可以在裁切页里
     * 缩放/平移取景，裁切结果写回 SRC，ORIG 不动，所以「还原」是免费的。
     */
    fun import(context: Context, uri: Uri): Boolean {
        return try {
            originalFile(context).outputStream().use { output ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(output)
                } ?: return false
            }
            resetCrop(context)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /** 放弃裁切：使用图回到原图，然后重算处理图。 */
    fun resetCrop(context: Context): Boolean {
        val orig = originalFile(context)
        if (!orig.exists()) return false
        return try {
            sourceFile(context).outputStream().use { out ->
                orig.inputStream().use { it.copyTo(out) }
            }
            process(context)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 按取景框裁切。[left]/[top]/[right]/[bottom] 是**相对原图的分数**（0..1），
     * 和预览用的解码尺寸无关，所以预览缩放过也不会裁偏。
     */
    fun applyCrop(
        context: Context,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        val orig = originalFile(context)
        if (!orig.exists()) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(orig.path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return false

            val x0 = (left.coerceIn(0f, 1f) * srcW).toInt().coerceIn(0, srcW - 1)
            val y0 = (top.coerceIn(0f, 1f) * srcH).toInt().coerceIn(0, srcH - 1)
            val x1 = (right.coerceIn(0f, 1f) * srcW).toInt().coerceIn(x0 + 1, srcW)
            val y1 = (bottom.coerceIn(0f, 1f) * srcH).toInt().coerceIn(y0 + 1, srcH)

            var sample = 1
            while (maxOf(x1 - x0, y1 - y0) / sample > MAX_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeFile(
                orig.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return false

            // 解码是采样过的：把原图坐标换算到解码图坐标
            val fx = decoded.width.toFloat() / srcW
            val fy = decoded.height.toFloat() / srcH
            val cx0 = (x0 * fx).toInt().coerceIn(0, decoded.width - 1)
            val cy0 = (y0 * fy).toInt().coerceIn(0, decoded.height - 1)
            val cx1 = (x1 * fx).toInt().coerceIn(cx0 + 1, decoded.width)
            val cy1 = (y1 * fy).toInt().coerceIn(cy0 + 1, decoded.height)

            val cropped = Bitmap.createBitmap(decoded, cx0, cy0, cx1 - cx0, cy1 - cy0)
            FileOutputStream(sourceFile(context)).use {
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
            process(context)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    fun clear(context: Context) {
        sourceFile(context).delete()
        originalFile(context).delete()
        processedFile(context).delete()
    }

    /** 按当前模糊/亮暗生成处理图。 */
    fun process(context: Context) {
        try {
            val src = sourceFile(context)
            if (!src.exists()) {
                android.util.Log.w(TAG, "process: 没有底图，跳过")
                return
            }

            // 先按最大边缩放，避免原图太大占内存
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
            val decoded0 = BitmapFactory.decodeFile(
                src.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
            if (decoded0 == null) {
                android.util.Log.e(
                    TAG,
                    "process: 底图解码失败 src=${src.length()}B bounds=${bounds.outWidth}x${bounds.outHeight}",
                )
                return
            }

            val blur = blur(context)
            // 真正的模糊（Box blur 三遍 ≈ 高斯），不是"缩小再放大"那种马赛克。
            // 半径映射放大了一点：滑块从 1 开始就要看得见变化（老映射 blur=1 时半径只有 1，几乎没效果）
            val radius = if (blur > 0) 1 + blur * 3 / 4 else 0
            val reduced = if (radius > 0) boxBlur(decoded0, radius, passes = 3) else decoded0

            // 亮暗：叠一层黑。注意 Canvas 只接受**可变**位图 ——
            // 未模糊时 reduced 就是 decodeFile 出来的不可变位图，直接 new Canvas 会抛
            // IllegalStateException（被 catch 吞掉 → 处理图永远写不出来 = 滑块没反应）
            var work = reduced
            if (!work.isMutable) {
                work = work.copy(Bitmap.Config.ARGB_8888, true) ?: work
            }
            val dim = dim(context)
            if (dim > 0 && work.isMutable) {
                Canvas(work).drawColor(
                    Color.argb(dim, 0, 0, 0),
                    PorterDuff.Mode.SRC_OVER,
                )
            }

            val file = processedFile(context)
            val ok = FileOutputStream(file).use {
                work.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            android.util.Log.i(
                TAG,
                "process: blur=$blur(r=$radius) dim=$dim ok=$ok -> ${file.name} ${file.length()}B " +
                    "(${work.width}x${work.height})",
            )
            if (work !== decoded0) work.recycle()
            decoded0.recycle()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "process 失败", e)
        }
    }

    /** 处理图是否需要重算（不存在，或比底图旧）。 */
    fun isProcessedStale(context: Context): Boolean {
        val src = sourceFile(context)
        if (!src.exists()) return false
        val proc = processedFile(context)
        return !proc.exists() || proc.lastModified() < src.lastModified()
    }

    /** 处理图重新生成后，让缓存的底图作废（下次取会重新解码）。 */
    @Synchronized
    fun invalidateBitmapCache() {
        cachedKey = null
        cachedBitmap = null
        windowBitmapKey = null
        windowBitmap = null
    }

    /** 窗口背景 drawable；没设置自定义图片时返回 null（用主题纯色底）。 */
    fun loadDrawable(context: Context): Drawable? {
        val file = if (processedFile(context).exists()) processedFile(context) else sourceFile(context)
        if (!file.exists()) return null
        val bmp = windowBitmapCache(context, file) ?: return null
        // centerCrop：保持原始比例、居中裁剪填充（不再拉伸变形）
        return CenterCropDrawable(bmp)
    }

    /**
     * 只取**已经在缓存里**的窗口背景（不触发解码）。
     *
     * onCreate 主线程上先问这个：命中就直接换底，没命中交给后台线程去解
     * （见 [moe.shizuku.manager.app.AppActivity.applyCustomBackground]）。
     */
    @Synchronized
    fun cachedWindowDrawable(context: Context): Drawable? {
        val file = if (processedFile(context).exists()) processedFile(context) else sourceFile(context)
        if (!file.exists()) return null
        val key = file.absolutePath + ":" + file.lastModified() + ":" + screenMaxEdge(context)
        val existing = windowBitmap
        if (key == windowBitmapKey && existing != null && !existing.isRecycled) {
            return CenterCropDrawable(existing)
        }
        return null
    }

    // ---- 窗口背景位图缓存（按 文件+修改时间+采样率 复用，避免每次重建都解码） ----

    @Volatile
    private var windowBitmapKey: String? = null

    @Volatile
    private var windowBitmap: Bitmap? = null

    @Synchronized
    private fun windowBitmapCache(context: Context, file: File): Bitmap? {
        val target = screenMaxEdge(context)
        val key = file.absolutePath + ":" + file.lastModified() + ":" + target
        val existing = windowBitmap
        if (key == windowBitmapKey && existing != null && !existing.isRecycled) return existing

        val decoded = decodeSampled(file, target)
        // 旧图不 recycle：可能还被上一帧的绘制引用（让 GC 自己收），只换引用
        windowBitmapKey = key
        windowBitmap = decoded
        return decoded
    }

    /** 屏幕最长边（px）：窗口底的采样目标 */
    private fun screenMaxEdge(context: Context): Int {
        val dm = context.resources.displayMetrics
        return maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(720)
    }

    /**
     * 按「解码后最长边 >= targetMax」的 2 的幂采样解码。
     *
     * 相比 `BitmapFactory.decodeFile(path)`（原尺寸）：内存降到 ~1/4 或更低，主线程解码快得多。
     */
    private fun decodeSampled(file: File, targetMax: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetMax) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = config
                },
            )
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Compose 侧用的背景 Bitmap（Haze 的 hazeSource 需要真像素，不能只是 View 的 windowBackground）。
     * 没设置自定义图片时返回 null —— 这时候页面底色就是主题纯色。
     *
     * 同样按屏幕尺寸采样解码：Haze 取像本来就会自己缩，没必要拿 4K 原图。
     */
    fun loadBitmap(context: Context): Bitmap? {
        val file = currentFile(context) ?: return null
        return decodeSampled(file, screenMaxEdge(context))
    }

    /** 当前底图的版本号（文件修改时间）：换图/裁切/调模糊后用它让 Compose 重新记 Bitmap。 */
    fun imageVersion(context: Context): Long = currentFile(context)?.lastModified() ?: 0L

    // ---- 复用的解码结果：自适应文字取色要反复读这张图，不能每次都解码 ----

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedBitmap: Bitmap? = null

    /** 当前底图（已处理的那张）；换图/裁切/调模糊后自动重新解码。 */
    @Synchronized
    fun cachedBitmap(context: Context): Bitmap? {
        val file = currentFile(context) ?: return null
        val key = file.absolutePath + ":" + file.lastModified()
        val existing = cachedBitmap
        if (key == cachedKey && existing != null && !existing.isRecycled) return existing
        // 【性能】这张图只用来算明暗/取色，512 px 足够；以前是全尺寸解码，白占几十 MB
        val decoded = decodeSampled(file, 512, Bitmap.Config.RGB_565)
        cachedKey = key
        cachedBitmap = decoded
        return decoded
    }

    private fun currentFile(context: Context): File? {
        val processed = processedFile(context)
        if (processed.exists()) return processed
        val src = sourceFile(context)
        return if (src.exists()) src else null
    }

    // ---- 文字自适应：判断当前背景整体是亮还是暗（带缓存，底图没变就不重算） ----

    @Volatile
    private var darkCacheKey: String? = null

    @Volatile
    private var darkCacheValue: Boolean = false

    /**
     * 背景图是否偏暗。字号/字色自适应用它决定用亮字还是暗字，
     * 免得背景换张图之后标题就"糊"在画面里。
     */
    fun isBackgroundDark(context: Context): Boolean {
        val file = if (processedFile(context).exists()) processedFile(context) else sourceFile(context)
        if (!file.exists()) return false
        val key = file.absolutePath + ":" + file.lastModified()
        if (key == darkCacheKey) return darkCacheValue

        val options = BitmapFactory.Options().apply { inSampleSize = 32 }
        val bmp = try {
            BitmapFactory.decodeFile(file.path, options)
        } catch (e: Throwable) {
            null
        }
        val value = if (bmp == null) {
            false
        } else {
            averageLuminance(bmp) < 0.45f
        }
        bmp?.recycle()
        darkCacheKey = key
        darkCacheValue = value
        return value
    }

    private fun averageLuminance(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 1f
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        return (sum / pixels.size).toFloat()
    }

    /**
     * 快速 Box Blur（滑动窗口求平均），跑 3 遍近似高斯 —— 观感是柔和的模糊，不会出现马赛克块。
     * 纯 Int 数组运算，1080p 图上跑三遍约几百毫秒，放在后台线程里做。
     */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        if (radius <= 0 || passes <= 0) return src
        val w = src.width
        val h = src.height
        var pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) { pass ->
            val r = radius
            // 横向
            for (y in 0 until h) {
                val row = y * w
                for (c in 0..2) {
                    val shift = c * 8
                    var sum = 0
                    for (i in -r..r) sum += (pixels[row + i.coerceIn(0, w - 1)] shr shift) and 0xFF
                    var x = 0
                    while (x < w) {
                        val alpha = (pixels[row + x] ushr 24) and 0xFF
                        tmp[row + x] = tmp[row + x] and (0xFF shl shift).inv() or (((sum / (2 * r + 1)) and 0xFF) shl shift)
                        if (x == 0) tmp[row + x] = tmp[row + x] or (alpha shl 24)
                        val outIdx = (x - r).coerceAtLeast(0)
                        val inIdx = (x + r + 1).coerceAtMost(w - 1)
                        sum += ((pixels[row + inIdx] shr shift) and 0xFF) - ((pixels[row + outIdx] shr shift) and 0xFF)
                        x++
                    }
                }
            }
            // 纵向
            for (x in 0 until w) {
                for (c in 0..2) {
                    val shift = c * 8
                    var sum = 0
                    for (i in -r..r) sum += (tmp[i.coerceIn(0, h - 1) * w + x] shr shift) and 0xFF
                    var y = 0
                    while (y < h) {
                        val idx = y * w + x
                        pixels[idx] = pixels[idx] and (0xFF shl shift).inv() or (((sum / (2 * r + 1)) and 0xFF) shl shift)
                        val outIdx = ((y - r).coerceAtLeast(0)) * w + x
                        val inIdx = ((y + r + 1).coerceAtMost(h - 1)) * w + x
                        sum += ((tmp[inIdx] shr shift) and 0xFF) - ((tmp[outIdx] shr shift) and 0xFF)
                        y++
                    }
                }
                // 保留 alpha
                for (y in 0 until h) {
                    val idx = y * w + x
                    pixels[idx] = (pixels[idx] and 0x00FFFFFF) or (0xFF shl 24)
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}