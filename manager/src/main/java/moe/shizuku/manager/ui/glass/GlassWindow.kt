package moe.shizuku.manager.ui.glass

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import java.util.function.Consumer

/**
 * 玻璃风格的**窗口模糊**实现（Android 12 公共 API）。
 *
 * 参考 Android 12 窗口模糊能力，两种模糊方式：
 *
 * 1. **窗口背景模糊（Background blur）**：`Window#setBackgroundBlurRadius` —— 模糊绘制在窗口
 *    Surface 之下，因此窗口必须半透明（`windowIsTranslucent`）才能看见；窗口背景使用一个
 *    带圆角的矩形 drawable（[R.drawable.window_background_glass]），这个圆角轮廓同时决定
 *    模糊区域，磨砂玻璃效果就出在窗口自己这一块。
 * 2. **模糊后方屏幕（Blur behind）**：`FLAG_BLUR_BEHIND` + `LayoutParams#setBlurBehindRadius`
 *    —— 模糊整个窗口后方的屏幕，形成景深；配合 `setDimAmount` 控制暗度。
 *
 * 两者叠加即「对话框 / 底部弹窗 / 悬浮窗」的玻璃质感。系统模糊被关闭时（跨窗口模糊开关、
 * 省电模式等）自动退化为不透明底 + 常规 dim，保证可读性；Android 12 以下同样走退化路径。
 */
object GlassWindow {

    /** 窗口自身背景模糊半径：越高越模糊、越费性能（文章示例值 90） */
    const val BACKGROUND_BLUR_RADIUS = 90

    /** 窗口后方屏幕模糊半径（文章示例值 20） */
    const val BLUR_BEHIND_RADIUS = 20

    /** 有模糊时不再额外压暗（模糊本身就是景深） */
    private const val DIM_WITH_BLUR = 0f

    /** 无模糊时压暗背景，保证前景可读 */
    private const val DIM_NO_BLUR = 0.4f

    /** 有模糊：窗口底半透明，透出下面的模糊 */
    private const val ALPHA_WITH_BLUR = 170

    /** 无模糊：窗口底不透明 */
    private const val ALPHA_NO_BLUR = 255

    /** 系统是否支持跨窗口模糊（Android 12+） */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 只在「玻璃材质」风格下套窗口模糊；MD3 风格保持标准 Material 3 对话框。
     * 调用点统一用它，省得每处都判断风格。
     */
    @JvmStatic
    fun applyIfGlass(dialog: Dialog?) {
        if (!moe.shizuku.manager.app.ThemeHelper.isUsingGlass()) return
        applyTo(dialog)
    }

    /** 给对话框套上玻璃窗口效果（背景模糊 + 后方屏幕模糊）。 */
    @JvmStatic
    @JvmOverloads
    fun applyTo(dialog: Dialog?, background: Drawable? = null) {
        val window = dialog?.window ?: return
        applyTo(window, background ?: window.context.getDrawable(R.drawable.window_background_glass))
    }

    /** 给任意窗口套上玻璃窗口效果。 */
    @JvmStatic
    fun applyTo(window: Window, background: Drawable?) {
        val bg = background
            ?: window.context.getDrawable(R.drawable.window_background_glass)
            ?: return

        // 用圆角矩形 drawable 替换窗口默认背景：轮廓即模糊区域
        window.setBackgroundDrawable(bg)

        if (!isSupported) {
            // 低版本没有窗口模糊：只保留半透明玻璃底 + 常规压暗
            bg.setAlpha(ALPHA_NO_BLUR)
            window.setDimAmount(DIM_NO_BLUR)
            return
        }
        applyBlurApi31(window, bg)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlurApi31(window: Window, bg: Drawable) {
        // 允许「模糊后方屏幕」与背景压暗（也可用主题属性 windowBlurBehindEnabled / backgroundDimEnabled）
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // 监听系统跨窗口模糊开关：关掉时要退化成不透明
        val listener = Consumer<Boolean> { enabled -> updateWindowForBlurs(window, bg, enabled) }
        window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                window.windowManager.addCrossWindowBlurEnabledListener(listener)
                // 首次进入也要应用一次当前状态
                updateWindowForBlurs(window, bg, window.windowManager.isCrossWindowBlurEnabled)
            }

            override fun onViewDetachedFromWindow(v: View) {
                window.windowManager.removeCrossWindowBlurEnabledListener(listener)
            }
        })
    }

    /** 应用/更新模糊状态。 */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.S)
    fun updateWindowForBlurs(window: Window, bg: Drawable, blursEnabled: Boolean) {
        // 窗口底透明度 + 压暗程度随模糊开关切换
        bg.setAlpha(if (blursEnabled) ALPHA_WITH_BLUR else ALPHA_NO_BLUR)
        window.setDimAmount(if (blursEnabled) DIM_WITH_BLUR else DIM_NO_BLUR)

        // 1) 窗口背景模糊（圆角轮廓即模糊区域）
        window.setBackgroundBlurRadius(if (blursEnabled) BACKGROUND_BLUR_RADIUS else 0)

        // 2) 后方屏幕模糊
        window.attributes = window.attributes.apply {
            blurBehindRadius = if (blursEnabled) BLUR_BEHIND_RADIUS else 0
        }
    }
}
