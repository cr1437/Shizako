package moe.shizuku.manager.ui.widget

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AdaptiveTextHelper

/**
 * 图片卡片（`layout/item_image_card.xml`）的绑定工具：**动态换背景图**。
 *
 * 三层结构照旧：ImageView（底层，centerCrop）/ View 遮罩（中层，底部渐变）/ 文字层（顶层）。
 * 换图只碰 ImageView —— 遮罩、标题、副标题都是**兄弟节点**，不在图片的绘制范围内，
 * 所以换多少次图都不会影响遮罩，也不会把文字盖住或挤走。
 *
 * 三个千万别做的事（都是"遮罩失效"的常见原因）：
 * 1. 别把图设成容器的 background（`frameLayout.setBackground(bitmapDrawable)`）：
 *    BitmapDrawable 默认 gravity=FILL，会**拉伸变形**，而且遮罩 + 文字都变成"背景上的内容"，
 *    一换图整块重排。图必须放 ImageView。
 * 2. 别给 ImageView 设 `scaleType=fitXY`：那就是拉伸。要 `centerCrop`。
 * 3. 别把遮罩写在 ImageView 的 `android:foreground` 上或反过来：声明顺序决定叠放，
 *    遮罩必须声明在 ImageView **之后**、文字**之前**。
 */
object ImageCardBinder {

    /**
     * 绑定一张裁剪后的背景图 + 标题 / 副标题，**并自动算好文字对比度**。
     *
     * 文字不是死白：先按「卡片底部那块图」（文字实际压着的区域）算 WCAG 对比度，
     * 再决定用黑字还是白字、遮罩要加多少不透明度 —— 原来是固定白字 + 固定 70% 遮罩，
     * 碰上雪景/白墙这种亮背景照样看不清，碰上夜景又白压一层黑。
     *
     * @param root 卡片根布局（`item_image_card.xml` 的 FrameLayout）
     * @param bitmap 裁剪后的 Bitmap；null = 只更新文字，不动图
     */
    fun bind(
        root: View,
        bitmap: Bitmap?,
        title: CharSequence,
        subtitle: CharSequence? = null,
        minContrast: Double = AdaptiveTextHelper.MIN_CONTRAST_BODY,
    ) {
        val image = root.findViewById<ImageView>(R.id.image_card_background)
        val scrim = root.findViewById<View>(R.id.image_card_scrim)
        val titleView = root.findViewById<TextView>(R.id.image_card_title)
        val subtitleView = root.findViewById<TextView>(R.id.image_card_subtitle)

        if (bitmap != null && !bitmap.isRecycled) {
            // 只动这一层：centerCrop 由 layout 里的 scaleType 保证，不会被换图改掉
            image.setImageBitmap(bitmap)

            // 文字压在遮罩那一带 → 按那块区域算方案（不是整图平均）
            val region = backgroundRegionForScrim(root, bitmap)
            val plan = AdaptiveTextHelper.planForRegion(bitmap, region, minContrast)
            if (plan != null) {
                titleView.setTextColor(plan.textColor)
                subtitleView.setTextColor(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        plan.textColor,
                        (0.82f * 255).toInt(),
                    ),
                )
                scrim.background = AdaptiveTextHelper.scrimDrawable(plan)
            }
        }

        titleView.text = title
        if (subtitle.isNullOrEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.visibility = View.VISIBLE
            subtitleView.text = subtitle
        }
    }

    /** 遮罩那一带（卡片底部）对应的图片区域：文字实际压着的那块。 */
    private fun backgroundRegionForScrim(root: View, bitmap: Bitmap): android.graphics.RectF? {
        val scrim = root.findViewById<View>(R.id.image_card_scrim) ?: return null
        val windowManager = root.context.getSystemService(android.content.Context.WINDOW_SERVICE)
            as? android.view.WindowManager ?: return null
        val bounds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            android.graphics.Rect(0, 0, size.x, size.y)
        }
        return AdaptiveTextHelper.regionBehind(
            card = scrim,
            bitmap = bitmap,
            window = android.graphics.Point(bounds.width(), bounds.height()),
        )
    }

    /**
     * 只换图（例如用户重新裁切了背景）。文字和遮罩原样保留。
     */
    fun setBackgroundImage(root: View, bitmap: Bitmap) {
        root.findViewById<ImageView>(R.id.image_card_background)
            .setImageBitmap(bitmap)
    }

    /**
     * 清掉背景图（回到纯色底）。遮罩留着也没关系 —— 没有图时它就是一层从透明到黑的渐变，
     * 压在半透明容器底上等于"底部略暗"，文字依然可读。
     */
    fun clearBackgroundImage(root: View) {
        root.findViewById<ImageView>(R.id.image_card_background).setImageDrawable(null)
    }

    /**
     * 遮罩高度按需调（默认 120dp）：文字多行时可以加高，保证文字全部落在渐变里。
     *
     * @param heightPx 目标高度（像素）
     */
    fun setScrimHeight(root: View, heightPx: Int) {
        val scrim = root.findViewById<View>(R.id.image_card_scrim)
        scrim.layoutParams = scrim.layoutParams.apply { height = heightPx }
    }
}
