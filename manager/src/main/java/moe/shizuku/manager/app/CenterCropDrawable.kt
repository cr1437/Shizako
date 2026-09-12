package moe.shizuku.manager.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 背景图 Drawable：**居中裁剪填充（centerCrop）**，保持原始比例不变形。
 *
 * 原来是直接 `window.setBackgroundDrawable(BitmapDrawable)` —— BitmapDrawable 默认
 * gravity 是 FILL，等于把图片**拉伸**到窗口比例，人像/风景都会被压扁。
 * 这里换成 ImageView `scaleType="centerCrop"` 的等价实现：
 * 取 `max(宽比, 高比)` 缩放，居中后超出部分裁掉。
 */
class CenterCropDrawable(
    private val bitmap: Bitmap,
    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
) : Drawable() {

    private val dst = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val bw = bounds.width().toFloat()
        val bh = bounds.height().toFloat()
        if (bw <= 0f || bh <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return

        val scale = maxOf(bw / bitmap.width, bh / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = bounds.left + (bw - drawW) / 2f
        val top = bounds.top + (bh - drawH) / 2f
        dst.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }
}
