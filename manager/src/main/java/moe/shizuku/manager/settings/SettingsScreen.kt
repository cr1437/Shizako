package moe.shizuku.manager.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.glass.GlassMaterials
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.nav.NavTabSpec
import moe.shizuku.manager.ui.nav.NavTabs

/** 设置页的全部状态（Fragment 单向下发，Compose 只读）。 */
@Immutable
data class SettingsUiState(
    val uiStyle: String = "glass",
    val nightMode: Int = -1,
    val blackNight: Boolean = false,
    val useSystemColor: Boolean = true,
    val themeColor: String = "",
    val showLogsTab: Boolean = true,
    val highRefresh: Boolean = true,
    val startOnBoot: Boolean = false,
    val watchdog: Boolean = false,
    val autoUpdate: Boolean = true,
    val apiLogEnabled: Boolean = true,
    val language: String = "SYSTEM",
    val languageLabel: String = "",
    val themeColorLabel: String = "",
    val contributors: String = "",
    val versionName: String = "",
    val supportsSystemColor: Boolean = true,
    /** 自定义背景图片：是否已设置 + 模糊 / 亮暗（滑块实时值） */
    val hasCustomBackground: Boolean = false,
    val hasBackgroundOriginal: Boolean = false,
    val bgBlur: Float = 10f,
    val bgDim: Float = 60f,
    /** 连点版本号 5 次解锁的调试项（翻车记录），持久化在偏好里 */
    val debugUnlocked: Boolean = false,
    /** 开机自动关闭 USB 调试 */
    val autoDisableUsbDebugging: Boolean = false,
    /** 持久 TCP 模式（照搬 Shevery）：断网也能直连回来 */
    val tcpMode: Boolean = false,
)

/** 设置页的层级：一级菜单 + 各二级页。 */
enum class SettingsPage { MAIN, APPEARANCE, BACKGROUND, NAV, MODULES, MODULE_POLICY, MODULE_CATALOG, COMPUT, COMPUT_SETTINGS, ACCESSIBILITY, LAB, STARTUP, LANGUAGE, DEBUG, ABOUT }

/**
 * 设置页（Compose 版，**两级菜单**）。
 *
 * - 一级：外观 / 启动与稳定性 / 语言 / 日志与调试 / 关于（每项带当前值摘要），点进去是二级页；
 * - 二级：具体开关 + 顶部「返回」行；系统返回键也回一级（由 Fragment 的返回回调驱动）；
 * - 关于页：**连点版本号 5 次**才出现「翻车记录」（崩溃日志）；
 * - 日志：单独的「记录 API 调用日志」开关（关掉后 server 不再写日志）+ 清空 / 打开日志页。
 *
 * 卡片材质与首页完全一致（HintCard：白 30% + 噪点 + 28dp 圆角；MD3 下为标准 M3 卡片）。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onUiStyle: (String) -> Unit,
    onNightMode: (Int) -> Unit,
    onBlackNight: (Boolean) -> Unit,
    onUseSystemColor: (Boolean) -> Unit,
    onThemeColor: () -> Unit,
    onShowLogsTab: (Boolean) -> Unit,
    onHighRefresh: (Boolean) -> Unit,
    onAutoDisableUsbDebugging: (Boolean) -> Unit = {},
    onStartOnBoot: (Boolean) -> Unit,
    onWatchdog: (Boolean) -> Unit,
    onTcpMode: (Boolean) -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onApiLogEnabled: (Boolean) -> Unit,
    onApiLogClear: () -> Unit,
    onOpenApiLog: () -> Unit,
    onLanguage: () -> Unit,
    onCheckUpdate: () -> Unit,
    onCrashLogs: () -> Unit,
    onTranslation: () -> Unit,
    onUnlockDebug: () -> Unit,
    /** 关于页「给项目点个 Star」：宿主用系统浏览器打开项目主页 */
    onOpenStar: () -> Unit = {},
    onPickBackground: () -> Unit,
    onCropBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundBlur: (Float) -> Unit,
    onBackgroundDim: (Float) -> Unit,
    onBackgroundCommit: () -> Unit,
    onPageChanged: (SettingsPage) -> Unit,
    onBackActionReady: ((() -> Unit)?) -> Unit,
    onOpenModules: () -> Unit = {},
    onOpenModuleWebUi: (String) -> Unit = {},
    pendingModuleZip: android.net.Uri? = null,
    onModuleZipConsumed: () -> Unit = {},
    onPickModuleZip: () -> Unit = {},
    onOpenComput: () -> Unit = {},
    onOpenAccessibility: () -> Unit = {},
    onOpenRecommendedApps: () -> Unit = {},
    onBackup: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    initialPage: SettingsPage = SettingsPage.MAIN,
) {
    // 重建（Activity recreate）之后仍停在上次那个二级页，不会把用户踢回一级
    var page by remember { mutableStateOf(initialPage) }
    val go: (SettingsPage) -> Unit = {
        page = it
        onPageChanged(it)
    }
    // 把「回到一级菜单」交给 Fragment，让系统返回键也能用
    // 返回上一级：控制台设置 -> 控制台，其余二级页 -> 一级菜单。
    // 以前固定回一级菜单，从「控制台设置」按返回会直接跳过控制台；现在按层级走。
    val backAction = remember {
        {
            val target = when (page) {
                SettingsPage.COMPUT_SETTINGS -> SettingsPage.COMPUT
                else -> SettingsPage.MAIN
            }
            page = target
            onPageChanged(target)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { onBackActionReady(backAction) }
    // 每次落页都通知宿主（含初始状态）：返回键的接管范围要和当前页一致 ——
    // 二级页接管返回、一级菜单放行给导航；重建后停在二级页也不会漏
    androidx.compose.runtime.LaunchedEffect(page) { onPageChanged(page) }

    // 每个页面各自记滚动位置：
    // - 打开一个二级页时它自己的状态是新建的 → 从顶部开始（不会停在上一页的半空）；
    // - 从二级页返回一级菜单时，一级菜单还是原来的位置（不用再滚回顶部）。
    // 状态在 AnimatedContent 的 content 里按目标页取（见下），这里只准备容器。
    val pageListStates = remember {
        mutableMapOf<SettingsPage, LazyListState>(SettingsPage.MAIN to listState)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onBackActionReady(null) }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // 转场参数全应用统一（PageMotion）：和底栏 Tab / 次级页那套 XML 是同一组数字
            moe.shizuku.manager.ui.motion.PageMotion.slideSpec(
                targetState.ordinal > initialState.ordinal,
            )
        },
        label = "settingsPage",
    ) { current ->
        // 每个页面用**自己的** LazyListState，按目标页取（不是取当前 page 的那份）：
        // AnimatedContent 过渡期间新旧两页会同时存在，两者共用一个 state 的话，
        // 两个 LazyColumn 会抢同一个滚动位置 —— 二级页滚过的 index 落到一级菜单的条目数之外，
        // 返回设置时列表就「卡住、滑不动」（有概率复现，取决于两级页面各自滚到哪）。
        val pageListState = pageListStates.getOrPut(current) {
            if (current == SettingsPage.MAIN) listState else LazyListState()
        }
        // 同一时刻只让「当前页」驱动大标题折叠：过渡中的旧页也活着，
        // 两边一起 setExpanded 会把 AppBarLayout 顶到错位、之后整页都滚不动。
        val pageCollapsedChange: (Boolean) -> Unit = { expanded ->
            if (current == page) onCollapsedChange(expanded)
        }
        when (current) {
            SettingsPage.MAIN -> MainPage(
                state = state,
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onOpen = go,
                onOpenModules = onOpenModules,
                onOpenComput = onOpenComput,
                onOpenAccessibility = onOpenAccessibility,
                onOpenRecommendedApps = onOpenRecommendedApps,
            )

            SettingsPage.APPEARANCE -> AppearancePage(
                state, palette, pageListState, pageCollapsedChange, go,
                onUiStyle, onNightMode, onBlackNight, onUseSystemColor, onThemeColor,
                onShowLogsTab, onHighRefresh,
            )

            SettingsPage.BACKGROUND -> BackgroundPage(
                state, palette, pageListState, pageCollapsedChange, go,
                onPickBackground, onCropBackground, onClearBackground, onBackgroundBlur,
                onBackgroundDim, onBackgroundCommit,
            )

            SettingsPage.NAV -> NavPage(
                state, palette, pageListState, pageCollapsedChange, go,
            )

            SettingsPage.MODULE_POLICY -> ModulePolicyPage(
                state, palette, pageListState, pageCollapsedChange, go,
            )

            SettingsPage.MODULES -> moe.shizuku.manager.module.ModulesScreen(
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onBack = { go(SettingsPage.MAIN) },
                onOpenWebUi = onOpenModuleWebUi,
                onOpenCatalog = { go(SettingsPage.MODULE_CATALOG) },
                onOpenPolicy = { go(SettingsPage.MODULE_POLICY) },
                pendingZip = pendingModuleZip,
                onZipConsumed = onModuleZipConsumed,
                onPickZip = onPickModuleZip,
            )

            SettingsPage.MODULE_CATALOG -> moe.shizuku.manager.module.catalog.ModuleCatalogScreen(
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onBack = { go(SettingsPage.MAIN) },
            )

            SettingsPage.COMPUT -> moe.shizuku.manager.comput.ComputPage(
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onBack = { go(SettingsPage.MAIN) },
                showBack = true,
                onOpenSettings = { go(SettingsPage.COMPUT_SETTINGS) },
            )

            SettingsPage.COMPUT_SETTINGS -> moe.shizuku.manager.comput.ComputSettingsPage(
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onBack = { go(SettingsPage.COMPUT) },
            )

            SettingsPage.ACCESSIBILITY -> moe.shizuku.manager.accessibility.AccessibilityPage(
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onBack = { go(SettingsPage.MAIN) },
            )

            SettingsPage.LAB -> LabFeaturesScreen(
                onBack = { go(SettingsPage.MAIN) },
                palette = palette,
                listState = pageListState,
                onCollapsedChange = pageCollapsedChange,
                onBackup = onBackup,
                onRestore = onRestore,
            )

            SettingsPage.STARTUP -> StartupPage(
                state, palette, pageListState, pageCollapsedChange, go,
                onStartOnBoot, onWatchdog, onTcpMode, onAutoUpdate, onAutoDisableUsbDebugging,
            )

            SettingsPage.LANGUAGE -> LanguagePage(
                state, palette, pageListState, pageCollapsedChange, go,
                onLanguage, onTranslation,
            )

            SettingsPage.DEBUG -> DebugPage(
                state, palette, pageListState, pageCollapsedChange, go,
                onApiLogEnabled, onApiLogClear, onOpenApiLog, onCheckUpdate,
            )

            SettingsPage.ABOUT -> AboutPage(
                state, palette, pageListState, pageCollapsedChange, go,
                onUnlockDebug, onCrashLogs,
                onOpenStar = onOpenStar,
            )
        }
    }
}

// ---------------- 一级菜单 ----------------

@Composable
private fun MainPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onOpenModules: () -> Unit,
    onOpenComput: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenRecommendedApps: () -> Unit,
) {
    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        // 头部：应用图标 + 问候语（按时间自动变：早上好 / 中午好 / 下午好 / 晚上好）
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = greetingText(),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.onCard,
                        )
                        Text(
                            text = stringResource(R.string.app_name) + " · v" + state.versionName,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.variant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.settings_group_appearance),
                    modifier = Modifier.padding(top = 10.dp),
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_dark_mode_24,
                    title = stringResource(R.string.settings_user_interface),
                    summary = uiStyleLabel(state.uiStyle),
                    onClick = { onOpen(SettingsPage.APPEARANCE) },
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_apps_24,
                    title = stringResource(R.string.settings_background),
                    summary = if (state.hasCustomBackground) {
                        stringResource(R.string.settings_bg_set)
                    } else {
                        stringResource(R.string.settings_bg_pick_summary)
                    },
                    onClick = { onOpen(SettingsPage.BACKGROUND) },
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_dock_24,
                    title = stringResource(R.string.settings_nav_bar),
                    summary = navBarSummary(),
                    onClick = { onOpen(SettingsPage.NAV) },
                )
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.settings_group_system),
                    modifier = Modifier.padding(top = 10.dp),
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_bolt_24dp,
                    title = stringResource(R.string.settings_stability),
                    summary = null,
                    onClick = { onOpen(SettingsPage.STARTUP) },
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_translate_24,
                    title = stringResource(R.string.settings_language),
                    summary = state.languageLabel,
                    onClick = { onOpen(SettingsPage.LANGUAGE) },
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_code_24dp,
                    title = stringResource(R.string.settings_api_log),
                    summary = if (state.apiLogEnabled) {
                        stringResource(R.string.settings_api_log_summary)
                    } else {
                        stringResource(R.string.settings_api_log_enabled)
                    },
                    onClick = { onOpen(SettingsPage.DEBUG) },
                )
                HintSectionTitle(
                    palette = palette,
                    text = stringResource(R.string.settings_group_about),
                    modifier = Modifier.padding(top = 10.dp),
                )
                NavRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_info_24,
                    title = stringResource(R.string.settings_about),
                    summary = "v" + state.versionName,
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

// ---------------- 二级：外观 ----------------

@Composable
private fun AppearancePage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    onUiStyle: (String) -> Unit,
    onNightMode: (Int) -> Unit,
    onBlackNight: (Boolean) -> Unit,
    onUseSystemColor: (Boolean) -> Unit,
    onThemeColor: () -> Unit,
    onShowLogsTab: (Boolean) -> Unit,
    onHighRefresh: (Boolean) -> Unit,
) {
    val styleLabels = stringArrayResource(R.array.ui_style_names).toList()
    val styleValues = stringArrayResource(R.array.ui_style_values).toList()
    val nightLabels = stringArrayResource(R.array.night_mode).toList()
    val nightValues = integerArrayResource(R.array.night_mode_value).map { it.toString() }

    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 0) {
        SettingSegmentedRow(
            palette = palette,
            title = stringResource(R.string.settings_ui_style),
            labels = styleLabels,
            values = styleValues,
            selected = state.uiStyle,
            onSelect = onUiStyle,
        )
        SettingSegmentedRow(
            palette = palette,
            title = stringResource(R.string.dark_theme),
            labels = nightLabels,
            values = nightValues,
            selected = state.nightMode.toString(),
            onSelect = { onNightMode(it.toInt()) },
        )
        if (state.nightMode != 1) {
            SettingSwitchRow(
                palette = palette,
                title = stringResource(R.string.settings_black_night_theme),
                summary = stringResource(R.string.settings_black_night_theme_summary),
                checked = state.blackNight,
                onChange = onBlackNight,
            )
        }
        if (state.supportsSystemColor) {
            SettingSwitchRow(
                palette = palette,
                title = stringResource(R.string.settings_use_system_color),
                summary = null,
                checked = state.useSystemColor,
                onChange = onUseSystemColor,
            )
            if (!state.useSystemColor) {
                SettingActionRow(
                    palette = palette,
                    title = stringResource(R.string.settings_theme_color),
                    summary = state.themeColorLabel,
                    onClick = onThemeColor,
                )
            }
        }
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_show_logs_tab),
            summary = stringResource(R.string.settings_show_logs_tab_summary),
            checked = state.showLogsTab,
            onChange = onShowLogsTab,
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_high_refresh_rate),
            summary = stringResource(R.string.settings_high_refresh_rate_summary),
            checked = state.highRefresh,
            onChange = onHighRefresh,
        )
        // 实时磨砂（BlurView）：默认关。开着时每帧要把整屏重录一遍再模糊，
        // 滚动掉帧 + 模糊慢一帧（卡片边缘拖影）都来自这里。
        if (state.uiStyle == "glass") {
            SettingSwitchRow(
                palette = palette,
                title = stringResource(R.string.settings_live_blur),
                summary = stringResource(R.string.settings_live_blur_summary),
                checked = GlassMaterials.isLiveBlurEnabled(),
                onChange = { GlassMaterials.setLiveBlurEnabled(it) },
            )
        }
    }
}

// ---------------- 二级：背景 ----------------

@Composable
private fun BackgroundPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    onPickBackground: () -> Unit,
    onCropBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundBlur: (Float) -> Unit,
    onBackgroundDim: (Float) -> Unit,
    onBackgroundCommit: () -> Unit,
) {
    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 1) {
        SettingActionRow(
            palette = palette,
            iconRes = R.drawable.ic_outline_apps_24,
            title = stringResource(R.string.settings_bg_pick),
            summary = if (state.hasCustomBackground) {
                stringResource(R.string.settings_bg_set)
            } else {
                stringResource(R.string.settings_bg_pick_summary)
            },
            onClick = onPickBackground,
        )
        if (state.hasCustomBackground) {
            if (state.hasBackgroundOriginal) {
                // 裁切永远从原图开始，所以可以反复裁、也可以随时还原
                SettingActionRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_crop_24,
                    title = stringResource(R.string.settings_bg_crop),
                    summary = stringResource(R.string.settings_bg_crop_summary),
                    onClick = onCropBackground,
                )
            }
            SettingSliderRow(
                palette = palette,
                title = stringResource(R.string.settings_bg_blur),
                value = state.bgBlur,
                valueRange = 0f..24f,
                steps = 23,
                valueLabel = state.bgBlur.toInt().toString(),
                onChange = onBackgroundBlur,
                onChangeFinished = onBackgroundCommit,
            )
            SettingSliderRow(
                palette = palette,
                title = stringResource(R.string.settings_bg_dim),
                value = state.bgDim,
                valueRange = 0f..200f,
                steps = 19,
                valueLabel = state.bgDim.toInt().toString(),
                onChange = onBackgroundDim,
                onChangeFinished = onBackgroundCommit,
            )
            SettingActionRow(
                palette = palette,
                title = stringResource(R.string.settings_bg_clear),
                summary = null,
                onClick = onClearBackground,
            )
        }
    }
}

// ---------------- 二级：底部栏入口自定义 ----------------

/**
 * 底栏自定义：**显示哪些入口 + 顺序**。
 *
 * 直接改 [NavTabs]（运行时可观察状态）→ 主界面底栏立即重组，不重建 Activity，
 * 所以在这里点完还停在原页面，不会被踢回一级菜单。
 */
@Composable
private fun NavPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
) {
    val order = NavTabs.order()
    val hidden = NavTabs.hiddenKeys()

    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 2) {
        Text(
            text = stringResource(R.string.settings_nav_bar_summary),
            style = MaterialTheme.typography.bodySmall,
            color = palette.variant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        order.forEachIndexed { index, spec ->
            // key = 入口 key：拖动换位后「拖动中」的状态和滑动动画跟着这一行走，不会串到邻居
            androidx.compose.runtime.key(spec.key) {
                NavTabRow(
                    palette = palette,
                    spec = spec,
                    index = index,
                    count = order.size,
                    enabled = spec.key !in hidden,
                )
            }
        }
        SettingActionRow(
            palette = palette,
            title = stringResource(R.string.settings_nav_reset),
            summary = null,
            onClick = { NavTabs.reset() },
        )
    }
}

/**
 * 单个底栏入口：图标 + 名称 + 上移/下移 + 拖动手柄 + 显示开关。
 *
 * 两套手感都留着（主人要的：箭头别删）：
 * - 上/下箭头：精确挪一位；
 * - **整行长按拖动**：跨过约一个行高挪一位（和系统列表重排一个手感），
 *   位置一变，这一行会**从前一个槽位滑到新槽位**（[slide] 那段动画），不是"啪"地跳过去。
 * 两者都走 `NavTabs.move`，只有一套排序状态。
 */
@Composable
private fun NavTabRow(
    palette: HintPalette,
    spec: NavTabSpec,
    index: Int,
    count: Int,
    enabled: Boolean,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stepPx = with(density) { 52.dp.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    // 排序动画：index 一变，先把这一行瞬移回"上一个槽位"，再滑到新槽位 —— 看起来就是被拖过去的那一行
    var previousIndex by remember { androidx.compose.runtime.mutableIntStateOf(index) }
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(index) {
        if (previousIndex != index) {
            slide.snapTo((previousIndex - index) * stepPx)
            previousIndex = index
            slide.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.8f,
                    stiffness = 520f,
                ),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .graphicsLayer {
                translationY = slide.value
                // 拖动中的那一行给一点"拿起来了"的反馈
                alpha = if (dragging) 0.92f else 1f
                scaleX = if (dragging) 1.02f else 1f
                scaleY = if (dragging) 1.02f else 1f
            }
            .pointerInput(spec.key, stepPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        accumulated = 0f
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                        )
                    },
                    onDragEnd = {
                        dragging = false
                        accumulated = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        accumulated = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount.y
                    while (accumulated >= stepPx) {
                        NavTabs.move(spec.key, 1)
                        accumulated -= stepPx
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                    }
                    while (accumulated <= -stepPx) {
                        NavTabs.move(spec.key, -1)
                        accumulated += stepPx
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                        )
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(palette, spec.iconRes, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(spec.titleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) palette.onCard else palette.variant,
            modifier = Modifier.weight(1f),
        )
        // 上/下箭头：精确挪一位（拖动排序是另一种手感，两套都留着）
        MoveButton(
            palette = palette,
            up = true,
            enabled = index > 0,
            description = stringResource(R.string.settings_nav_move_up),
            onClick = { NavTabs.move(spec.key, -1) },
        )
        MoveButton(
            palette = palette,
            up = false,
            enabled = index < count - 1,
            description = stringResource(R.string.settings_nav_move_down),
            onClick = { NavTabs.move(spec.key, 1) },
        )
        // 手柄只是"这里能拖"的提示（手势装在上面整行上）
        Icon(
            painter = painterResource(R.drawable.ic_outline_drag_handle_24),
            contentDescription = stringResource(R.string.settings_nav_drag_to_sort),
            tint = if (dragging) palette.accent else palette.variant,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(22.dp),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { NavTabs.setEnabled(spec.key, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.variant,
                uncheckedTrackColor = palette.card,
                uncheckedBorderColor = palette.variant.copy(alpha = 0.5f),
            ),
        )
    }
}

/** 上/下移一位的小按钮（箭头）。 */
@Composable
private fun MoveButton(
    palette: HintPalette,
    up: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_outline_arrow_upward_24),
            contentDescription = description,
            tint = if (enabled) palette.accent else palette.variant.copy(alpha = 0.35f),
            modifier = Modifier
                .size(19.dp)
                .rotate(if (up) 0f else 180f),
        )
    }
}

/** 一级菜单摘要：当前启用的入口名（按自定义顺序）。 */
@Composable
private fun navBarSummary(): String {
    val names = NavTabs.visible().map { stringResource(it.titleRes) }
    return if (names.isEmpty()) {
        stringResource(R.string.settings_nav_bar_summary)
    } else {
        names.joinToString(" · ")
    }
}

// ---------------- 二级：启动与稳定性 ----------------

@Composable
private fun StartupPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onWatchdog: (Boolean) -> Unit,
    onTcpMode: (Boolean) -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onAutoDisableUsbDebugging: (Boolean) -> Unit,
) {
    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 1) {
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_start_on_boot),
            summary = stringResource(R.string.settings_start_on_boot_summary),
            checked = state.startOnBoot,
            onChange = onStartOnBoot,
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_watchdog),
            summary = stringResource(R.string.settings_watchdog_summary),
            checked = state.watchdog,
            onChange = onWatchdog,
        )
        // 持久 TCP 模式（照搬 Shevery）：切到本机 5555，断网也能直连回来
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_tcp_mode),
            summary = stringResource(R.string.settings_tcp_mode_summary),
            checked = state.tcpMode,
            onChange = onTcpMode,
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_auto_update),
            summary = stringResource(R.string.settings_auto_update_summary),
            checked = state.autoUpdate,
            onChange = onAutoUpdate,
        )
        // 开机自动关掉 USB 调试（需要 WRITE_SECURE_SETTINGS，ADB 给过一次就有）
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_auto_disable_usb_debugging),
            summary = stringResource(R.string.settings_auto_disable_usb_debugging_summary),
            checked = state.autoDisableUsbDebugging,
            onChange = onAutoDisableUsbDebugging,
        )
    }
}

// ---------------- 二级：语言 ----------------

@Composable
private fun LanguagePage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    onLanguage: () -> Unit,
    onTranslation: () -> Unit,
) {
    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 2) {
        SettingActionRow(
            palette = palette,
            title = stringResource(R.string.settings_language),
            summary = state.languageLabel,
            onClick = onLanguage,
        )
        SettingActionRow(
            palette = palette,
            title = stringResource(R.string.settings_translation),
            summary = stringResource(
                R.string.settings_translation_summary,
                stringResource(R.string.app_name),
            ),
            onClick = onTranslation,
        )
        if (state.contributors.isNotBlank()) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.settings_translation_contributors),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onCard,
                )
                Text(
                    text = state.contributors,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---------------- 二级：日志与调试 ----------------

@Composable
private fun DebugPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    onApiLogEnabled: (Boolean) -> Unit,
    onApiLogClear: () -> Unit,
    onOpenApiLog: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 3) {
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.settings_api_log_enabled),
            summary = stringResource(R.string.settings_api_log_enabled_summary),
            checked = state.apiLogEnabled,
            onChange = onApiLogEnabled,
        )
        SettingActionRow(
            palette = palette,
            title = stringResource(R.string.settings_api_log_clear),
            summary = null,
            onClick = onApiLogClear,
        )
        SettingActionRow(
            palette = palette,
            title = stringResource(R.string.settings_api_log_open),
            summary = stringResource(R.string.settings_api_log_summary),
            onClick = onOpenApiLog,
        )
        SettingActionRow(
            palette = palette,
            title = stringResource(R.string.settings_check_update),
            summary = stringResource(R.string.settings_check_update_summary),
            onClick = onCheckUpdate,
        )
    }
}

// ---------------- 二级：关于（连点 5 次解锁翻车记录）----------------

@Composable
private fun AboutPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    onUnlockDebug: () -> Unit,
    onCrashLogs: () -> Unit,
    /** 关于页「给项目点个 Star」：宿主用系统浏览器打开项目主页 */
    onOpenStar: () -> Unit = {},
) {
    var taps by remember { mutableIntStateOf(0) }
    val soonUnlocked = state.debugUnlocked
    // Bilibili 那行要用它开链接
    val context = androidx.compose.ui.platform.LocalContext.current

    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        // 返回
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(0)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { go(SettingsPage.MAIN) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.settings_back),
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_back),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.accent,
                    )
                }
            }
        }

        // Hero：居中大图标 + 名称 + 版本徽章（连点 5 次解锁翻车记录）
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(1)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp)
                        .clickable {
                            if (!soonUnlocked) {
                                taps += 1
                                if (taps >= 5) {
                                    taps = 0
                                    onUnlockDebug()
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.onCard,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 版本徽章
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "v" + state.versionName,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                        )
                    }
                }
            }
        }

        // 信息
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(2)) {
                // 作者 / Bilibili：不把 URL 摊在界面上（那不算引导），
                // 只给平台名 + 「打开主页」，点了用系统浏览器打开（不用内置 Custom Tabs）
                ProfileRow(
                    palette = palette,
                    label = stringResource(R.string.about_author),
                    value = stringResource(R.string.about_author_name),
                    onClick = {
                        openInBrowser(
                            context,
                            context.getString(R.string.about_github_link),
                        )
                    },
                )
                // 求 Star：顺手能点到，比冷启动弹一次更管用（弹窗那套在 StarPrompt 里）
                ProfileRow(
                    palette = palette,
                    label = stringResource(R.string.about_star),
                    value = stringResource(R.string.about_star_summary),
                    onClick = { onOpenStar() },
                )
                ProfileRow(
                    palette = palette,
                    label = stringResource(R.string.about_bilibili),
                    value = stringResource(R.string.about_open_link),
                    onClick = {
                        openInBrowser(
                            context,
                            context.getString(R.string.about_bilibili_link),
                        )
                    },
                )
                ProfileRow(
                    palette = palette,
                    label = stringResource(R.string.about_license),
                    value = stringResource(R.string.about_license_text),
                )
                ProfileRow(
                    palette = palette,
                    label = stringResource(R.string.about_based_on),
                    value = stringResource(
                        R.string.about_based_on_text,
                        stringResource(R.string.app_name),
                    ),
                )
                if (soonUnlocked) {
                    SettingActionRow(
                        palette = palette,
                        iconRes = R.drawable.ic_outline_assignment_24,
                        title = stringResource(R.string.settings_crash_logs),
                        summary = stringResource(R.string.settings_crash_logs_summary),
                        onClick = onCrashLogs,
                    )
                }
            }
        }

        // 页脚 + 未解锁时的提示
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                if (!soonUnlocked) {
                    Text(
                        text = stringResource(R.string.settings_tap_version_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

// ---------------- 二级：模块策略 ----------------

/**
 * ADB Modules 的访问策略（照搬 Shevery 的三档 + Full Trust）。
 *
 * 这里只放**全局门**；单个模块的例外走「长按卡片 → 信任」，
 * 信任之后该模块绕过这些门（硬安全限制照旧）。
 */
@Composable
internal fun ModulePolicyPage(
    state: SettingsUiState,
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
) {
    // 让开关随偏好变化重组
    var version by remember { mutableIntStateOf(0) }
    val recompose = { version++ }
    version // 读取一次，订阅变化

    val mode = ModuleSettings.getAccessMode()
    val modeNames = listOf(
        stringResource(R.string.module_mode_safe),
        stringResource(R.string.module_mode_custom),
        stringResource(R.string.module_mode_full),
    )
    val modeValues = ModuleSettings.AccessMode.entries.map { it.value }

    SubPage(palette, listState, onCollapsedChange, go, entranceKey = 3) {
        SettingSegmentedRow(
            palette = palette,
            title = stringResource(R.string.module_access_mode),
            labels = modeNames,
            values = modeValues,
            selected = mode.value,
            onSelect = {
                ModuleSettings.setAccessMode(
                    ModuleSettings.AccessMode.entries.firstOrNull { e -> e.value == it }
                        ?: ModuleSettings.AccessMode.SAFE,
                )
                recompose()
            },
        )

        // 自定义模式下这几个开关才有意义；安全模式一律不放行，完全访问一律放行
        val customMode = mode == ModuleSettings.AccessMode.CUSTOM
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.module_service),
            summary = if (customMode) null else stringResource(R.string.module_only_in_custom),
            checked = ModuleSettings.isServiceEnabled(),
            onChange = { ModuleSettings.setServiceEnabled(it); recompose() },
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.module_background),
            summary = if (customMode) null else stringResource(R.string.module_only_in_custom),
            checked = ModuleSettings.isBackgroundEnabled(),
            onChange = { ModuleSettings.setBackgroundEnabled(it); recompose() },
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.module_webui_bridge),
            summary = stringResource(R.string.module_webui_bridge_summary),
            checked = ModuleSettings.isWebUiBridgeEnabled(),
            onChange = { ModuleSettings.setWebUiBridgeEnabled(it); recompose() },
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.module_webview_internet),
            summary = null,
            checked = ModuleSettings.isWebViewInternetEnabled(),
            onChange = { ModuleSettings.setWebViewInternetEnabled(it); recompose() },
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.module_webui_download),
            summary = null,
            checked = ModuleSettings.isWebUiDownloadEnabled(),
            onChange = { ModuleSettings.setWebUiDownloadEnabled(it); recompose() },
        )
        SettingSwitchRow(
            palette = palette,
            title = stringResource(R.string.module_recommand),
            summary = stringResource(R.string.module_recommand_summary),
            checked = ModuleSettings.isReCommandEnabled(),
            onChange = { ModuleSettings.setReCommandEnabled(it); recompose() },
        )
        if (ModuleSettings.isAiCheckerEnabled()) {
            SettingSwitchRow(
                palette = palette,
                title = stringResource(R.string.module_ai_checker),
                summary = stringResource(R.string.module_ai_checker_summary),
                checked = true,
                onChange = { ModuleSettings.setAiCheckerEnabled(false); recompose() },
            )
        }
        Text(
            text = stringResource(R.string.module_trust_hint),
            style = MaterialTheme.typography.bodySmall,
            color = palette.variant,
            modifier = Modifier.padding(top = 6.dp),
        )
        val trusted = ModuleSettings.trustedModules()
        if (trusted.isNotEmpty()) {
            Text(
                text = stringResource(R.string.module_trusted_list, trusted.joinToString("、")),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------------- 通用组件 ----------------

/** 二级页骨架：内容上面自动带一个「返回」行。 */
@Composable
private fun SubPage(
    palette: HintPalette,
    listState: LazyListState,
    onCollapsedChange: (Boolean) -> Unit,
    go: (SettingsPage) -> Unit,
    entranceKey: Int,
    content: @Composable () -> Unit,
) {
    HintPage(listState = listState, onCollapsedChange = onCollapsedChange) {
        item {
            HintCard(palette = palette, modifier = Modifier.itemEntrance(entranceKey)) {
                // 返回行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { go(SettingsPage.MAIN) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = stringResource(R.string.settings_back),
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_back),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.accent,
                    )
                }
                content()
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** 左侧图标徽章：强调色 14% 圆角方块 + 图标。 */
@Composable
private fun IconBadge(palette: HintPalette, iconRes: Int, size: Dp = 38.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(palette.accent.copy(alpha = 0.14f), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** 一级菜单入口：图标徽章 + 标题 + 当前值摘要 + 右侧箭头。 */
@Composable
private fun NavRow(
    palette: HintPalette,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    iconRes: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            IconBadge(palette, iconRes)
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = palette.onCard)
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
            contentDescription = null,
            tint = palette.variant,
            modifier = Modifier.size(18.dp).rotate(180f),
        )
    }
}

/** 开关行。 */
@Composable
private fun SettingSwitchRow(
    palette: HintPalette,
    title: String,
    summary: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    iconRes: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            IconBadge(palette, iconRes, size = 36.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = palette.onCard)
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.variant,
                uncheckedTrackColor = palette.card,
                uncheckedBorderColor = palette.variant.copy(alpha = 0.5f),
            ),
        )
    }
}

/** 点击行。 */
@Composable
private fun SettingActionRow(
    palette: HintPalette,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    iconRes: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            IconBadge(palette, iconRes, size = 36.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = palette.onCard)
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
            contentDescription = null,
            tint = palette.variant,
            modifier = Modifier.size(18.dp).rotate(180f),
        )
    }
}

/** 分段选择行（界面风格 / 深浅色）：玻璃风格用玻璃滑块，MD3 用 M3 分段按钮。 */
@Composable
private fun SettingSegmentedRow(
    palette: HintPalette,
    title: String,
    labels: List<String>,
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = palette.onCard)
        Spacer(Modifier.height(8.dp))

        if (palette.style == moe.shizuku.manager.ui.hint.HintStyle.GLASS) {
            // 和底部导航同一套滑块设计（拖动 + 点击 + 弹簧吸附），尺寸收小
            moe.shizuku.manager.ui.glass.GlassOptionSlider(
                options = labels,
                selectedIndex = values.indexOf(selected).coerceAtLeast(0),
                onSelected = { onSelect(values[it]) },
                modifier = Modifier.padding(horizontal = 2.dp),
                thumbColor = palette.accent,
                thumbContentColor = palette.onAccent,
                unselectedContentColor = palette.variant,
                trackColor = Color.White.copy(alpha = 0.10f),
                edgeColor = Color.White.copy(alpha = 0.38f),
            )
            return@Column
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = values[index] == selected,
                    onClick = { onSelect(values[index]) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = palette.accent,
                        activeContentColor = palette.onAccent,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = palette.variant,
                        activeBorderColor = Color.Transparent,
                        inactiveBorderColor = palette.variant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 带标题的数值滑块行（模糊 / 亮暗）。 */
@Composable
private fun SettingSliderRow(
    palette: HintPalette,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onCard,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.variant.copy(alpha = 0.3f),
            ),
        )
    }
}

/** 关于页的「标签 : 值」行。 */
@Composable
private fun ProfileRow(
    palette: HintPalette,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 带链接的行整行可点（Bilibili 那个），普通信息行不变
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.variant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) palette.accent else palette.onCard,
        )
    }
}

/** 用**系统浏览器**打开链接（明确不要内置 Custom Tabs）；没有浏览器就复制到剪贴板。 */
private fun openInBrowser(context: android.content.Context, url: String) {
    val view = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(url),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (e: Throwable) {
        // 设备上没有任何浏览器：退化成复制链接，别静默什么都不做
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.toast_copied_to_clipboard, url),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
/** 关于页的「标签: 值」行。 */
@Composable
private fun uiStyleLabel(uiStyle: String): String {
    val labels = stringArrayResource(R.array.ui_style_names)
    val values = stringArrayResource(R.array.ui_style_values)
    val index = values.indexOf(uiStyle)
    return if (index >= 0) labels[index] else uiStyle
}

/** 按当前时间给问候语：<11 早上 / <13 中午 / <18 下午 / 其余 晚上。 */
@Composable
private fun greetingText(): String {
    val hour = remember {
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }
    return stringResource(
        when {
            hour < 11 -> R.string.settings_greeting_morning
            hour < 13 -> R.string.settings_greeting_noon
            hour < 18 -> R.string.settings_greeting_evening
            else -> R.string.settings_greeting_night
        },
    )
}