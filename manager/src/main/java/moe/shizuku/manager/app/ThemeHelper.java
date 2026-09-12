package moe.shizuku.manager.app;

import android.content.Context;
import android.os.Build;

import androidx.annotation.StyleRes;

import java.util.HashMap;
import java.util.Map;

import moe.shizuku.manager.R;
import moe.shizuku.manager.ShizukuSettings;
import moe.shizuku.manager.utils.EnvironmentUtils;
import rikka.core.util.ResourceUtils;

public class ThemeHelper {

    private static final String THEME_DEFAULT = "DEFAULT";
    private static final String THEME_BLACK = "BLACK";

    public static final String KEY_LIGHT_THEME = "light_theme";
    public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";
    public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";
    public static final String KEY_THEME_COLOR = "theme_color";
    public static final String THEME_COLOR_DEFAULT = "DEFAULT";

    private static final Map<String, Integer> COLOR_THEME_MAP = new HashMap<>();

    static {
        COLOR_THEME_MAP.put("SAKURA", R.style.ThemeOverlay_MaterialSakura);
        COLOR_THEME_MAP.put("MATERIAL_RED", R.style.ThemeOverlay_MaterialRed);
        COLOR_THEME_MAP.put("MATERIAL_PINK", R.style.ThemeOverlay_MaterialPink);
        COLOR_THEME_MAP.put("MATERIAL_PURPLE", R.style.ThemeOverlay_MaterialPurple);
        COLOR_THEME_MAP.put("MATERIAL_DEEP_PURPLE", R.style.ThemeOverlay_MaterialDeepPurple);
        COLOR_THEME_MAP.put("MATERIAL_INDIGO", R.style.ThemeOverlay_MaterialIndigo);
        COLOR_THEME_MAP.put("MATERIAL_BLUE", R.style.ThemeOverlay_MaterialBlue);
        COLOR_THEME_MAP.put("MATERIAL_LIGHT_BLUE", R.style.ThemeOverlay_MaterialLightBlue);
        COLOR_THEME_MAP.put("MATERIAL_CYAN", R.style.ThemeOverlay_MaterialCyan);
        COLOR_THEME_MAP.put("MATERIAL_TEAL", R.style.ThemeOverlay_MaterialTeal);
        COLOR_THEME_MAP.put("MATERIAL_GREEN", R.style.ThemeOverlay_MaterialGreen);
        COLOR_THEME_MAP.put("MATERIAL_LIGHT_GREEN", R.style.ThemeOverlay_MaterialLightGreen);
        COLOR_THEME_MAP.put("MATERIAL_LIME", R.style.ThemeOverlay_MaterialLime);
        COLOR_THEME_MAP.put("MATERIAL_YELLOW", R.style.ThemeOverlay_MaterialYellow);
        COLOR_THEME_MAP.put("MATERIAL_AMBER", R.style.ThemeOverlay_MaterialAmber);
        COLOR_THEME_MAP.put("MATERIAL_ORANGE", R.style.ThemeOverlay_MaterialOrange);
        COLOR_THEME_MAP.put("MATERIAL_DEEP_ORANGE", R.style.ThemeOverlay_MaterialDeepOrange);
        COLOR_THEME_MAP.put("MATERIAL_BROWN", R.style.ThemeOverlay_MaterialBrown);
        COLOR_THEME_MAP.put("MATERIAL_BLUE_GREY", R.style.ThemeOverlay_MaterialBlueGrey);
    }

    public static boolean isBlackNightTheme(Context context) {
        return ShizukuSettings.getPreferences().getBoolean(KEY_BLACK_NIGHT_THEME, EnvironmentUtils.isWatch(context));
    }

    public static boolean isUsingSystemColor() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ShizukuSettings.getPreferences().getBoolean(KEY_USE_SYSTEM_COLOR, true);
    }

    public static String getTheme(Context context) {
        if (isBlackNightTheme(context)
                && ResourceUtils.isNightMode(context.getResources().getConfiguration()))
            return THEME_BLACK;

        return ShizukuSettings.getPreferences().getString(KEY_LIGHT_THEME, THEME_DEFAULT);
    }

    @StyleRes
    public static int getThemeStyleRes(Context context) {
        switch (getTheme(context)) {
            case THEME_BLACK:
                return R.style.ThemeOverlay_Black;
            case THEME_DEFAULT:
            default:
                return R.style.ThemeOverlay;
        }
    }

    public static String getColorTheme() {
        if (isUsingSystemColor()) {
            return "SYSTEM";
        }
        return ShizukuSettings.getPreferences().getString(KEY_THEME_COLOR, THEME_COLOR_DEFAULT);
    }

    @StyleRes
    public static int getColorThemeStyleRes() {
        Integer res = COLOR_THEME_MAP.get(getColorTheme());
        return res == null ? 0 : res;
    }

    // ==================== 双风格：MD3 / 玻璃 ====================

    public static final String KEY_UI_STYLE = "ui_style";
    /** 标准 Material 3 观感：不透明表面、M3 组件、M3 底栏指示器 */
    public static final String UI_STYLE_MD3 = "md3";
    /** Liquid Glass 玻璃材质：半透明玻璃卡片 + 胶囊底栏 + 滑块指示器 */
    public static final String UI_STYLE_GLASS = "glass";

    /** 旧值（M3E / Miuix）映射：M3E → md3，Miuix → md3 */
    private static final String UI_STYLE_M3E_LEGACY = "m3e";
    private static final String UI_STYLE_MIUIX_LEGACY = "miuix";

    public static String getUiStyle() {
        String value = ShizukuSettings.getPreferences().getString(KEY_UI_STYLE, UI_STYLE_GLASS);
        if (UI_STYLE_GLASS.equals(value)) {
            return UI_STYLE_GLASS;
        }
        if (UI_STYLE_MD3.equals(value)) {
            return UI_STYLE_MD3;
        }
        // 兼容旧值
        if (UI_STYLE_MIUIX_LEGACY.equals(value) || UI_STYLE_M3E_LEGACY.equals(value)) {
            return UI_STYLE_MD3;
        }
        return UI_STYLE_GLASS;
    }

    /** 玻璃材质风格（默认） */
    public static boolean isUsingGlass() {
        return UI_STYLE_GLASS.equals(getUiStyle());
    }

    /** 标准 Material 3 风格 */
    public static boolean isUsingMd3() {
        return UI_STYLE_MD3.equals(getUiStyle());
    }
}