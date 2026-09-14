package moe.shizuku.manager;

import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.util.Locale;

import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static final String NIGHT_MODE = "night_mode";
    public static final String LANGUAGE = "language";
    public static final String KEEP_START_ON_BOOT = "start_on_boot";
    public static final String SETUP_COMPLETED = "setup_completed";
    public static final String PREFERRED_START_METHOD = "preferred_start_method";
    public static final String HIGH_REFRESH_RATE = "high_refresh_rate";
    public static final String WATCHDOG_ENABLED = "watchdog_enabled";
    public static final String AUTO_UPDATE = "auto_update";

    /** 开机后自动关掉 USB 调试（照搬 Shevery：ADB 启动过 Shizuku 之后把口子关上更安全） */
    public static final String AUTO_DISABLE_USB_DEBUGGING = "auto_disable_usb_debugging";

    /** 持久 TCP 模式：把 ADB 切到本机 5555，断网也能直接连回来（照搬 Shevery）。 */
    public static final String TCP_MODE = "tcp_mode";

    /** 上次启动用的 ADB 端口（TCP 模式直连的候选）。 */
    public static final String LAST_ADB_PORT = "last_adb_port";

    /** 用户对“启用持久 TCP 模式？”提示选择了不再提醒。 */
    public static final String SUPPRESS_TCP_MODE_PROMPT = "suppress_tcp_mode_prompt";

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    @IntDef({
            LaunchMethod.UNKNOWN,
            LaunchMethod.ROOT,
            LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt("mode", method).apply();
    }

    /** 默认开启（2026-09-14 起）：激活完自动把 ADB 切到本机 TCP 5555，免网络也能直连回来。 */
    public static boolean isTcpMode() {
        return getPreferences().getBoolean(TCP_MODE, true);
    }

    public static void setTcpMode(boolean enabled) {
        getPreferences().edit().putBoolean(TCP_MODE, enabled).apply();
    }

    public static boolean isTcpModePromptSuppressed() {
        return getPreferences().getBoolean(SUPPRESS_TCP_MODE_PROMPT, false);
    }

    public static void setTcpModePromptSuppressed(boolean suppressed) {
        getPreferences().edit().putBoolean(SUPPRESS_TCP_MODE_PROMPT, suppressed).apply();
    }

    public static int getLastAdbPort() {
        return getPreferences().getInt(LAST_ADB_PORT, -1);
    }

    public static void setLastAdbPort(int port) {
        getPreferences().edit().putInt(LAST_ADB_PORT, port).apply();
    }

    /**
     * Whether activities should request the display's highest refresh rate.
     * Defaults to true (the historical behavior).
     */
    public static boolean isHighRefreshRateEnabled() {
        return getPreferences().getBoolean(HIGH_REFRESH_RATE, true);
    }

    public static boolean isWatchdogEnabled() {
        return getPreferences().getBoolean(WATCHDOG_ENABLED, false);
    }

    public static boolean isAutoUpdateEnabled() {
        return getPreferences().getBoolean(AUTO_UPDATE, true);
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        int defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (EnvironmentUtils.isWatch(ActivityThread.currentActivityThread().getApplication())) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES;
        }
        return getPreferences().getInt(NIGHT_MODE, defValue);
    }

    /**
     * 保存深浅色模式。
     *
     * 设置页以前只调 {@link AppCompatDelegate#setDefaultNightMode(int)}，**没写偏好** ——
     * 那个调用只影响当前进程，重建之后 {@link #getNightMode()} 还是旧值，
     * 外观页的滑块就"弹回跟随系统"。写偏好这一步不能省（和设置向导里一致）。
     */
    public static void setNightMode(@AppCompatDelegate.NightMode int mode) {
        getPreferences().edit().putInt(NIGHT_MODE, mode).apply();
    }

    /** 开机自动关闭 USB 调试 */
    public static boolean getAutoDisableUsbDebugging() {
        return getPreferences().getBoolean(AUTO_DISABLE_USB_DEBUGGING, false);
    }

    public static void setAutoDisableUsbDebugging(boolean enabled) {
        getPreferences().edit().putBoolean(AUTO_DISABLE_USB_DEBUGGING, enabled).apply();
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString(LANGUAGE, null);
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return Locale.getDefault();
        }
        // 中文按文字分：老版本存的 zh-CN / zh-TW 迁到 zh-Hans / zh-Hant 后写回，
        // 免得设置里那个单选列表选中项对不上（列表用的是新标签）
        String normalized = moe.shizuku.manager.utils.LanguageNames.normalize(tag);
        if (!normalized.equals(tag)) {
            getPreferences().edit().putString(LANGUAGE, normalized).apply();
        }
        return Locale.forLanguageTag(normalized);
    }

    /**
     * Whether the user has finished (or skipped) the first-launch setup wizard.
     */
    public static boolean isSetupCompleted() {
        return getPreferences().getBoolean(SETUP_COMPLETED, false);
    }

    public static void setSetupCompleted(boolean completed) {
        getPreferences().edit().putBoolean(SETUP_COMPLETED, completed).apply();
    }

    /**
     * Start method picked in the setup wizard. The home page orders its
     * "start" cards accordingly so the preferred one comes first.
     */
    @StartMethod
    public static int getPreferredStartMethod() {
        return getPreferences().getInt(PREFERRED_START_METHOD, StartMethod.UNSET);
    }

    public static void setPreferredStartMethod(@StartMethod int method) {
        getPreferences().edit().putInt(PREFERRED_START_METHOD, method).apply();
    }

    @IntDef({
            StartMethod.UNSET,
            StartMethod.WIRELESS_ADB,
            StartMethod.ROOT,
            StartMethod.COMPUTER_ADB,
    })
    @Retention(SOURCE)
    public @interface StartMethod {
        int UNSET = 0;
        int WIRELESS_ADB = 1;
        int ROOT = 2;
        int COMPUTER_ADB = 3;
    }
}