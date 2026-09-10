package moe.shizuku.manager.app

import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.core.res.isNight
import rikka.core.res.resolveColor
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()
    }

    override fun onResume() {
        super.onResume()
        // Re-check so toggling the setting applies immediately, without
        // recreating activities that are already on the back stack.
        applyHighRefreshRate()
    }

    /**
     * Prefer the highest refresh rate supported by the display so that UI
     * animations run smoothly on high refresh rate devices.
     *
     * On Android 11+ (API 30) the same can be declared per-activity via
     * android:preferredRefreshRate in the manifest, but setting the window's
     * preferred display mode at runtime works from API 23 on every device.
     *
     * Controlled by the "high_refresh_rate" setting; when disabled the
     * preference is cleared so the system's default mode is used.
     */
    private fun applyHighRefreshRate() {
        val window = window ?: return
        val lp = window.attributes

        if (!ShizukuSettings.isHighRefreshRateEnabled()) {
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                window.attributes = lp
            }
            return
        }

        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        } ?: return

        val modes = display.supportedModes
        if (modes.isEmpty()) return

        val current = display.mode
        // Prefer modes with the same resolution as the current one, so devices
        // whose peak refresh mode uses a lower resolution keep their full size.
        val best = modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: modes.maxByOrNull { it.refreshRate }
            ?: return

        if (lp.preferredDisplayModeId == best.modeId) return
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
    }

    override fun computeUserThemeKey(): String {
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor() + ThemeHelper.getColorTheme() + ThemeHelper.getUiStyle()
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)

        // 主题色自选：未跟随系统色时叠加用户选择的色板
        if (!ThemeHelper.isUsingSystemColor()) {
            val overlay = ThemeHelper.getColorThemeStyleRes()
            if (overlay != 0) theme.applyStyle(overlay, true)
        }

        // KernelSU 双风格：Miuix 时在色板之上再叠加形状/背景/卡片样式
        if (ThemeHelper.isUsingMiuix()) {
            theme.applyStyle(R.style.ThemeOverlay_Miuix, true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars()

        val window = window
        val theme = theme

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window?.decorView?.post {
                if (window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0 >= Resources.getSystem().displayMetrics.density * 40) {
                    window.navigationBarColor =
                        theme.resolveColor(android.R.attr.navigationBarColor) and 0x00ffffff or -0x20000000
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                } else {
                    window.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = true
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}