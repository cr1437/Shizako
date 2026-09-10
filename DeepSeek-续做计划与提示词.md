# Shizako UI 重构 · 续做计划与 DeepSeek 提示词

> 交接说明：本仓库（Shizako，fork 自 Shizuku，`manager` 模块）正在做"LSPosed 风格 UI 全套重构"。
> **大部分已完成并静态校验通过**，剩余 2 个自定义功能未实现。本文档 = 现状清单 + 剩余任务的照抄级实现指导，可直接交给 DeepSeek 执行。

---

# 第一部分：现状（已完成，不要重做）

## 1.1 项目结构关键事实

- 仓库根：`Shizako/`，manager 模块：`manager/`，包名 `moe.shizuku.manager`，applicationId `com.churan.shizako`
- UI 技术栈：View/XML（非 Compose）、Material 1.12、RikkaX（appcompat/insets/material/material-preference/borderview/preference/simplemenu-preference/lifecycle-*）、Kotlin
- **沙箱没有 Android SDK，无法编译**。所有变更只做了静态校验，最终需要本地 `./gradlew :manager:assembleDebug` 验证
- 主题由 `dev.rikka.tools.materialthemebuilder` 插件在构建期生成（`Theme.Material3.Light.Shizuku` 等），种子色已改为 **#FF9CA8（LSPosed 同款 Sakura 粉）**

## 1.2 已完成的重构（对应 git 未提交变更，源码包 `Shizako-LSP-UI-源码.zip` 已包含）

**A. LSPosed 风格主界面（已完成 ✅）**
- 单 Activity：`MainActivity.kt`（extends `app/AppActivity`），`res/layout/activity_main.xml`（CoordinatorLayout + NavHost + BottomNavigationView）
- 导航图 `res/navigation/main_nav.xml`，4 个 Tab：`apps_fragment`（应用管理）/ `home_fragment`（首页，默认）/ `logs_fragment`（日志）/ `settings_fragment`（设置）
- `home/HomeFragment.kt`：M3 大标题折叠栏 + `colorPrimary` 服务状态大卡（`home_server_status.xml` 已改为主色卡），沿用 `HomeAdapter` 全部激活卡片
- `management/AppsFragment.kt` + `AppsAdapter.java`（带搜索过滤）；`settings/LogsFragment.kt`；`settings/SettingsTabFragment.kt`（容器嵌原 `SettingsFragment`）
- 资源：`menu/navigation_menu.xml`、`menu/menu_home.xml`、`menu/menu_apps.xml`、`values/integer.xml`（insets 常量）、`anim/fragment_*` 四件套、checkable 导航图标
- 已删：HomeActivity、ApplicationManagementActivity、SettingsActivity、ApiLogActivity 及其布局/菜单

**B. 单入口改造（已完成 ✅）**
- 5 个次级页已从独立 Activity 转为 Navigation 目的地（Fragment）：
  - `adb/AdbPairingTutorialFragment.kt`、`shell/ShellTutorialFragment.kt`、`starter/StarterFragment.kt`（参数走 nav arguments，key=EXTRA_IS_ROOT/EXTRA_HOST/EXTRA_PORT）、`dhizuku/DhizukuManageFragment.kt`、`activation/OneClickActivationFragment.kt`
- 通用次级页壳 `res/layout/fragment_sub_page.xml`（大标题折叠栏 + 返回键 `ic_baseline_arrow_back_24` + `content_container`）
- 6 个启动点已改为 `MainActivity.destinationIntent(context, R.id.xxx_fragment, args?)`：ActivationViewHolder、TerminalViewHolder、StartRootViewHolder、StartWirelessAdbViewHolder、AdbDialogFragment、StartDhizukuViewHolder
- Manifest 已移除这 5 个 Activity 声明并校验合法；旧 Activity 文件已删除
- `activity_activation.xml`、`starter_activity.xml` 已去掉 `paddingTop="?actionBarSize"`，insets 改为 `bottom`

## 1.3 已知警告级小尾巴（可顺手清理，非错误）

- `home/HomeFragment.kt` 有 2 个未使用 import：`moe.shizuku.manager.Helps`、`moe.shizuku.manager.utils.CustomTabsHelper`，删掉即可
- `MainActivity.kt` 的 `onSupportNavigateUp()` 建议加括号提高可读性

---

# 第二部分：剩余任务（交给 DeepSeek）

只剩两件事 + 校验打包：

- **任务 A：主题色自选**（LSPosed 同款 20 色板，设置页可选，跟随系统色时自动隐藏）
- **任务 B：日志 Tab 显隐自定义**（设置页开关，MainActivity 按设置增删菜单项）

---

# 第三部分：可直接粘贴给 DeepSeek 的提示词

````text
你是 Android 开发助手。在仓库 Shizako/manager 模块上完成 2 个功能。所有代码我已在下面写全，你的工作是【精确定位 + 新建/编辑文件 + 静态校验】，不要自由发挥改其他文件，不要动已完成的逻辑。

背景事实（必读）：
- 包名 moe.shizuku.manager；设置存储统一走 ShizukuSettings.getPreferences()（文件名 ShizukuSettings.NAME，device protected）
- 主题 helper：moe/shizuku/manager/app/ThemeHelper.java（已有 KEY_LIGHT_THEME/KEY_BLACK_NIGHT_THEME/KEY_USE_SYSTEM_COLOR）
- 主题基类：moe/shizuku/manager/app/AppActivity.kt（已有 onApplyUserThemeResource / computeUserThemeKey）
- 主题由 Gradle 插件 dev.rikka.tools.materialthemebuilder 生成；manager/build.gradle 里已有 shizuku 主题（种子 #FF9CA8）
- 设置页：res/xml/settings.xml + settings/SettingsFragment.kt；设置项用 rikka.material.preference.MaterialSwitchPreference 和自定义 SimpleMenuPreference
- 主界面 MainActivity.kt：BottomNavigationView id 为 nav，日志 Tab 的菜单 id 是 R.id.logs_fragment
- 没有 Android SDK，不能编译，只能静态校验（文末给校验脚本）

==================== 任务 A：主题色自选（20 色板） ====================

【A1】编辑 manager/build.gradle，在 materialThemeBuilder 的 themes {} 块内、shizuku {...} 之后追加（注意缩进）：

        // LSPosed 同款 20 色 ThemeOverlay（设置页「主题色」自选）
        def overlayColors = [
                Red: "F44336", Pink: "E91E63", Purple: "9C27B0", DeepPurple: "673AB7",
                Indigo: "3F51B5", Blue: "2196F3", LightBlue: "03A9F4", Cyan: "00BCD4",
                Teal: "009688", Green: "4CAF50", LightGreen: "8BC34A", Lime: "CDDC39",
                Yellow: "FFEB3B", Amber: "FFC107", Orange: "FF9800", DeepOrange: "FF5722",
                Brown: "795548", BlueGrey: "607D8F", Sakura: "FF9CA8"
        ]
        overlayColors.each { name, color ->
            "Material$name" {
                lightThemeFormat = "ThemeOverlay.Light.%s"
                darkThemeFormat = "ThemeOverlay.Dark.%s"
                primaryColor = "#$color"
            }
        }

如果构建时插件不认 Groovy 动态命名，改为手写 19 个块，例如：
        MaterialRed { lightThemeFormat = "ThemeOverlay.Light.%s"; darkThemeFormat = "ThemeOverlay.Dark.%s"; primaryColor = "#F44336" }
（逐个把上面 19 个颜色写完）

【A2】新建 manager/src/main/res/values/themes_custom.xml（ThemeOverlay.Light.* 由插件生成）：

<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="ThemeOverlay.MaterialRed" parent="ThemeOverlay.Light.MaterialRed"/>
    <style name="ThemeOverlay.MaterialPink" parent="ThemeOverlay.Light.MaterialPink"/>
    <style name="ThemeOverlay.MaterialPurple" parent="ThemeOverlay.Light.MaterialPurple"/>
    <style name="ThemeOverlay.MaterialDeepPurple" parent="ThemeOverlay.Light.MaterialDeepPurple"/>
    <style name="ThemeOverlay.MaterialIndigo" parent="ThemeOverlay.Light.MaterialIndigo"/>
    <style name="ThemeOverlay.MaterialBlue" parent="ThemeOverlay.Light.MaterialBlue"/>
    <style name="ThemeOverlay.MaterialLightBlue" parent="ThemeOverlay.Light.MaterialLightBlue"/>
    <style name="ThemeOverlay.MaterialCyan" parent="ThemeOverlay.Light.MaterialCyan"/>
    <style name="ThemeOverlay.MaterialTeal" parent="ThemeOverlay.Light.MaterialTeal"/>
    <style name="ThemeOverlay.MaterialGreen" parent="ThemeOverlay.Light.MaterialGreen"/>
    <style name="ThemeOverlay.MaterialLightGreen" parent="ThemeOverlay.Light.MaterialLightGreen"/>
    <style name="ThemeOverlay.MaterialLime" parent="ThemeOverlay.Light.MaterialLime"/>
    <style name="ThemeOverlay.MaterialYellow" parent="ThemeOverlay.Light.MaterialYellow"/>
    <style name="ThemeOverlay.MaterialAmber" parent="ThemeOverlay.Light.MaterialAmber"/>
    <style name="ThemeOverlay.MaterialOrange" parent="ThemeOverlay.Light.MaterialOrange"/>
    <style name="ThemeOverlay.MaterialDeepOrange" parent="ThemeOverlay.Light.MaterialDeepOrange"/>
    <style name="ThemeOverlay.MaterialBrown" parent="ThemeOverlay.Light.MaterialBrown"/>
    <style name="ThemeOverlay.MaterialBlueGrey" parent="ThemeOverlay.Light.MaterialBlueGrey"/>
    <style name="ThemeOverlay.MaterialSakura" parent="ThemeOverlay.Light.MaterialSakura"/>
</resources>

【A3】新建 manager/src/main/java/moe/shizuku/manager/settings/StringSimpleMenuPreference.java
（这是同目录 IntegerSimpleMenuPreference.java 的 String 版孪生，照它抄并把 int 换 String）：
- 类名 StringSimpleMenuPreference extends Preference
- mEntryValues 类型 String[]，mValue 类型 String
- 读取数组用 TypedArrayUtils.getTextArray(...)（不是 getIntArray）
- setValue(String) 里 persistString(value)；onSetInitialValue 用 getPersistedString((String) defaultValue)，defaultValue 为 null 时用 "DEFAULT"
- SavedState.value 改 String，writeToParcel 用 dest.writeString(value)，createFromParcel 用 source.readString()
- onGetDefaultValue 返回 a.getString(index)
- 其余结构（构造器 3 个、mPopupWindow、onClick、getSummary 的 %s 替换、onBindViewHolder 的 mAnchor）与 Integer 版完全一致

【A4】编辑 manager/src/main/res/values/arrays.xml，在 </resources> 前追加：

    <string-array name="theme_color_names">
        <item>@string/theme_color_default</item>
        <item>@string/theme_color_sakura</item>
        <item>Red</item><item>Pink</item><item>Purple</item><item>Deep Purple</item>
        <item>Indigo</item><item>Blue</item><item>Light Blue</item><item>Cyan</item>
        <item>Teal</item><item>Green</item><item>Light Green</item><item>Lime</item>
        <item>Yellow</item><item>Amber</item><item>Orange</item><item>Deep Orange</item>
        <item>Brown</item><item>Blue Grey</item>
    </string-array>
    <string-array name="theme_color_values">
        <item>DEFAULT</item><item>SAKURA</item>
        <item>MATERIAL_RED</item><item>MATERIAL_PINK</item><item>MATERIAL_PURPLE</item>
        <item>MATERIAL_DEEP_PURPLE</item><item>MATERIAL_INDIGO</item><item>MATERIAL_BLUE</item>
        <item>MATERIAL_LIGHT_BLUE</item><item>MATERIAL_CYAN</item><item>MATERIAL_TEAL</item>
        <item>MATERIAL_GREEN</item><item>MATERIAL_LIGHT_GREEN</item><item>MATERIAL_LIME</item>
        <item>MATERIAL_YELLOW</item><item>MATERIAL_AMBER</item><item>MATERIAL_ORANGE</item>
        <item>MATERIAL_DEEP_ORANGE</item><item>MATERIAL_BROWN</item><item>MATERIAL_BLUE_GREY</item>
    </string-array>

（两个数组必须都是 20 项且一一对应）

【A5】新建 manager/src/main/res/values-zh-rCN/arrays.xml（只覆盖 names，禁止翻译 values！）：

<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="theme_color_names">
        <item>@string/theme_color_default</item>
        <item>@string/theme_color_sakura</item>
        <item>红</item><item>粉</item><item>紫</item><item>深紫</item>
        <item>靛蓝</item><item>蓝</item><item>浅蓝</item><item>青</item>
        <item>蓝绿</item><item>绿</item><item>浅绿</item><item>黄绿</item>
        <item>黄</item><item>琥珀</item><item>橙</item><item>深橙</item>
        <item>棕</item><item>蓝灰</item>
    </string-array>
</resources>

【A6】编辑 strings：values/strings.xml 与 values-zh-rCN/strings.xml 的 </resources> 前分别追加：

英文：
    <string name="settings_theme_color">Theme color</string>
    <string name="settings_show_logs_tab">Show logs tab</string>
    <string name="settings_show_logs_tab_summary">Show the API call log entry in the bottom navigation</string>
    <string name="theme_color_default">Default (Sakura)</string>
    <string name="theme_color_sakura">Sakura pink</string>

中文：
    <string name="settings_theme_color">主题色</string>
    <string name="settings_show_logs_tab">显示日志标签页</string>
    <string name="settings_show_logs_tab_summary">在底部导航中显示 API 调用日志入口</string>
    <string name="theme_color_default">默认（樱花粉）</string>
    <string name="theme_color_sakura">樱花粉</string>

【A7】编辑 app/ThemeHelper.java：
a) 文件头加 import：java.util.HashMap、java.util.Map
b) 类内追加：

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

【A8】编辑 app/AppActivity.kt：
a) computeUserThemeKey() 改为：
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor() + ThemeHelper.getColorTheme()
b) onApplyUserThemeResource() 里，在 `theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)` 之后追加：
        // 主题色自选：未跟随系统色时叠加用户选择的色板
        if (!ThemeHelper.isUsingSystemColor()) {
            val overlay = ThemeHelper.getColorThemeStyleRes(this)
            if (overlay != 0) theme.applyStyle(overlay, true)
        }

【A9】编辑 res/xml/settings.xml，在 key="use_system_color" 的 MaterialSwitchPreference 结束之后追加：

        <moe.shizuku.manager.settings.StringSimpleMenuPreference
            android:entries="@array/theme_color_names"
            android:entryValues="@array/theme_color_values"
            android:key="theme_color"
            android:summary="%s"
            android:title="@string/settings_theme_color" />

        <rikka.material.preference.MaterialSwitchPreference
            android:defaultValue="true"
            android:key="show_logs_tab"
            android:summary="@string/settings_show_logs_tab_summary"
            android:title="@string/settings_show_logs_tab" />

【A10】编辑 settings/SettingsFragment.kt：
a) 在 `private lateinit var useSystemColorPreference: TwoStatePreference` 下面加：
    private lateinit var themeColorPreference: Preference
b) 在 `useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!` 之后加：
        themeColorPreference = findPreference("theme_color")!!
        themeColorPreference.isVisible = !ThemeHelper.isUsingSystemColor()
        themeColorPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

        findPreference<Preference>("show_logs_tab")!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }
c) 找到 useSystemColorPreference.onPreferenceChangeListener（约 158 行），在 `if (value is Boolean) {` 里面第一行加：
                        themeColorPreference.isVisible = !value

==================== 任务 B：日志 Tab 显隐 ====================

【B1】编辑 MainActivity.kt：
a) 在 onCreate 里 `NavigationUI.setupWithNavController(nav, navController)` 之后加一行：
        applyLogsTabVisibility()
b) 类内追加方法：
    /** 自定义功能：设置里可隐藏「日志」Tab（切换后设置页会 recreate 本 Activity 生效） */
    private fun applyLogsTabVisibility() {
        val show = ShizukuSettings.getPreferences().getBoolean("show_logs_tab", true)
        if (!show) {
            (binding?.nav as? NavigationBarView)?.menu?.removeItem(R.id.logs_fragment)
        }
    }

==================== 顺手清理（可选） ====================
- 删 HomeFragment.kt 里未使用的 import：moe.shizuku.manager.Helps、moe.shizuku.manager.utils.CustomTabsHelper

==================== 完成后必须执行的静态校验 ====================

1) XML 语法校验（在 manager/ 目录执行）：
python3 - <<'EOF'
import xml.dom.minidom, glob
files = glob.glob("src/main/res/values/themes_custom.xml") \
      + glob.glob("src/main/res/values/arrays.xml") \
      + glob.glob("src/main/res/values-zh-rCN/arrays.xml") \
      + glob.glob("src/main/res/values/strings.xml") \
      + glob.glob("src/main/res/values-zh-rCN/strings.xml") \
      + glob.glob("src/main/res/xml/settings.xml")
for f in files:
    xml.dom.minidom.parse(f)
print("all XML OK:", len(files))
EOF

2) 一致性检查：
- grep -rn "theme_color" src/main/res src/main/java | wc -l   # 应 >= 10
- grep -n "show_logs_tab" src/main/res/xml/settings.xml src/main/java/moe/shizuku/manager/MainActivity.kt
- grep -n "COLOR_THEME_MAP" src/main/java/moe/shizuku/manager/app/ThemeHelper.java
- python 校验 arrays 两个数组均为 20 项

3) 禁止事项：
- 不要翻译 values-zh-rCN 里的 theme_color_values
- 不要修改 materialThemeBuilder 里已有的 shizuku 块
- 不要动 navigation/main_nav.xml 和已完成 Fragment 的逻辑

4) 最后提醒用户：本地跑 ./gradlew :manager:assembleDebug 验证编译，如有报错贴回来。
````

---

# 第四部分：给用户的验收清单

DeepSeek 干完后，你按这个单子验收：

| 检查点 | 预期 |
|---|---|
| 设置 → 用户界面 出现「主题色」 | 点击弹 20 色菜单，跟随系统色开启时该项隐藏 |
| 选非默认色 | 界面立即重建并换色；开「跟随系统颜色」时回到系统取色 |
| 设置 → 「显示日志标签页」开关 | 关闭后底部导航只剩 3 个 Tab，重开恢复 |
| `./gradlew :manager:assembleDebug` | 编译通过；若报错，把报错发回 |

# 附：当前交付物

- `Shizako-LSP-UI-源码.zip` —— 含以上全部已完成变更的最新源码（解压即当前进度）
- `Shizako-LSP-UI-变更.patch` —— 相对上游 Shizako 仓库的完整补丁（65 个文件）
