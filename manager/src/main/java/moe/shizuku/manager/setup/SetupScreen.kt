package moe.shizuku.manager.setup

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.ui.hint.HintCard
import moe.shizuku.manager.ui.hint.HintNote
import moe.shizuku.manager.ui.hint.HintNoteRow
import moe.shizuku.manager.ui.hint.HintPage
import moe.shizuku.manager.ui.hint.HintPalette
import moe.shizuku.manager.ui.hint.HintPrimaryButton
import moe.shizuku.manager.ui.hint.HintSecondaryButton
import moe.shizuku.manager.ui.hint.HintSectionTitle
import moe.shizuku.manager.ui.hint.HintStep
import moe.shizuku.manager.ui.hint.ensureReadable
import moe.shizuku.manager.ui.hint.itemEntrance
import moe.shizuku.manager.ui.hint.resolveHintPalette
import moe.shizuku.manager.ui.motion.PageMotion
import moe.shizuku.manager.ui.style.UiStyle
import moe.shizuku.manager.utils.LanguageNames
import rikka.material.app.LocaleDelegate
import rikka.shizuku.manager.ShizukuLocales

/**
 * 首次启动引导（**全面重构 · Compose 版**）的全部界面。
 *
 * 设计约定（和 App 里其它页面同一套语言）：
 * - 页面骨架：`HintPage`（LazyColumn + 壁纸/Haze 模糊源 + 卡片弹簧入场），
 *   MD3 = 不透明 tonal 卡片；玻璃 = 半透明玻璃卡 + 折射描边——两套风格全部走着色板；
 * - 顶部：步骤进度条 +「第 n 步，共 N 步」（欢迎页 / 完成页不显示）；
 * - 底部：只有「上一步 / 下一步」，最后一步是「开始调教」——**没有跳过**；
 * - 步骤切换：`AnimatedContent` + `PageMotion.slideSpec`（和设置二级页同一组数字）；
 * - 选卡、开关、单选圆点全部用 HintPalette 着色，切风格 / 动态取色 / 深浅色自动适配。
 */
enum class SetupStep {
    WELCOME, DISCLAIMER, LANGUAGE, APPEARANCE, METHOD, ACTIVATE, FINISH;

    /** 「第 n 步」里的 n；欢迎页和完成页不算内容步骤。 */
    val contentIndex: Int?
        get() = when (this) {
            WELCOME, FINISH -> null
            DISCLAIMER -> 1
            LANGUAGE -> 2
            APPEARANCE -> 3
            METHOD -> 4
            ACTIVATE -> 5
        }

    companion object {
        /** 内容步骤总数（第 1~5 步）。 */
        const val CONTENT_COUNT = 5
    }
}

/** 引导页的全部状态，由宿主 Activity 单向下发。 */
@Immutable
data class SetupUiState(
    val step: SetupStep = SetupStep.WELCOME,
    val disclaimerAgreed: Boolean = false,
    val shizukuRunning: Boolean = false,
    val dhizukuActive: Boolean = false,
    val activatingDhizuku: Boolean = false,
    val preferredMethod: Int = ShizukuSettings.StartMethod.UNSET,
    val paired: Boolean = false,
    val canAutoAdb: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val nightMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    val blackNight: Boolean = false,
    val systemColor: Boolean = true,
)

/** 免责声明强制阅读倒计时（秒）。 */
private const val DISCLAIMER_SECONDS = 10

// ════════════════════════════════════════════════════════════════
// 根容器：顶栏进度 + 步骤内容 + 底部按钮
// ════════════════════════════════════════════════════════════════

@Composable
fun SetupFlow(
    state: SetupUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onAgreeChange: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectUiStyle: (String) -> Unit,
    onSelectNightMode: (Int) -> Unit,
    onToggleBlackNight: (Boolean) -> Unit,
    onToggleSystemColor: (Boolean) -> Unit,
    onSelectMethod: (Int) -> Unit,
    onStartRoot: () -> Unit,
    onStartWireless: () -> Unit,
    onStartPairing: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onViewCommand: () -> Unit,
    onActivateDhizuku: () -> Unit,
) {
    val context = LocalContext.current
    val style = UiStyle.current
    // 风格变了就重算配色（key 里带风格，缓存命中时几乎零开销）
    val palette = remember(style) { resolveHintPalette(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 安全区留白：AppActivity 是 edge-to-edge（decorFits=false），
            // 这里自己把状态栏 / 手势条 / 刘海避让掉（和无障碍管理器页同一套做法）
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SetupProgressHeader(state.step, palette)

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    // 和全应用统一：新页 ±1/4 页宽滑入 + 淡入，旧页反向滑出 + 淡出
                    PageMotion.slideSpec(targetState.ordinal > initialState.ordinal)
                },
                label = "setupStep",
            ) { step ->
                when (step) {
                    SetupStep.WELCOME -> WelcomePage(palette)
                    SetupStep.DISCLAIMER -> DisclaimerPage(
                        palette = palette,
                        agreed = state.disclaimerAgreed,
                        onAgreeChange = onAgreeChange,
                    )
                    SetupStep.LANGUAGE -> LanguagePage(palette, onSelectLanguage)
                    SetupStep.APPEARANCE -> AppearancePage(
                        palette = palette,
                        state = state,
                        onSelectUiStyle = onSelectUiStyle,
                        onSelectNightMode = onSelectNightMode,
                        onToggleBlackNight = onToggleBlackNight,
                        onToggleSystemColor = onToggleSystemColor,
                    )
                    SetupStep.METHOD -> MethodPage(
                        palette = palette,
                        selected = state.preferredMethod,
                        onSelectMethod = onSelectMethod,
                    )
                    SetupStep.ACTIVATE -> ActivatePage(
                        palette = palette,
                        state = state,
                        onStartRoot = onStartRoot,
                        onStartWireless = onStartWireless,
                        onStartPairing = onStartPairing,
                        onOpenDeveloperOptions = onOpenDeveloperOptions,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onViewCommand = onViewCommand,
                        onActivateDhizuku = onActivateDhizuku,
                    )
                    SetupStep.FINISH -> FinishPage(palette, state)
                }
            }
        }

        SetupFooter(state, palette, onBack = onBack, onNext = onNext)
    }
}

/** 顶部进度：细进度条 +「第 n 步，共 N 步」。欢迎页 / 完成页整行不显示。 */
@Composable
private fun SetupProgressHeader(step: SetupStep, palette: HintPalette) {
    val index = step.contentIndex ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            // 开场：顶栏从上方轻轻落下
            .entranceFade(delayMillis = 60, riseDp = -8f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.outline.copy(alpha = 0.25f)),
        ) {
            val fraction by animateFloatAsState(
                targetValue = index / SetupStep.CONTENT_COUNT.toFloat(),
                animationSpec = tween(PageMotion.ENTER_MS),
                label = "setupProgress",
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.accent),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(
                R.string.setup_step_counter_hint,
                index,
                SetupStep.CONTENT_COUNT,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = palette.variant,
        )
    }
}

/** 底部按钮区：上一步（淡入淡出）+ 下一步 / 开始调教。 */
@Composable
private fun SetupFooter(
    state: SetupUiState,
    palette: HintPalette,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp)
            // 开场：底栏从下方轻轻升起（底部额外留出舒适的安全间隔）
            .entranceFade(delayMillis = 200, riseDp = 10f),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = state.step != SetupStep.WELCOME,
            enter = fadeIn(animationSpec = tween(PageMotion.FADE_IN_MS)),
            exit = fadeOut(animationSpec = tween(PageMotion.FADE_OUT_MS)),
        ) {
            Row {
                HintSecondaryButton(
                    palette = palette,
                    text = stringResource(R.string.setup_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(12.dp))
            }
        }
        HintPrimaryButton(
            palette = palette,
            text = stringResource(
                if (state.step == SetupStep.FINISH) R.string.setup_get_started
                else R.string.setup_next,
            ),
            enabled = state.step != SetupStep.DISCLAIMER || state.disclaimerAgreed,
            onClick = onNext,
        )
    }
}

// ════════════════════════════════════════════════════════════════
// 0. 欢迎
// ════════════════════════════════════════════════════════════════

@Composable
private fun WelcomePage(palette: HintPalette) {
    val listState = rememberLazyListState()
    // 「主体居中」测量：首/尾卡片的窗口坐标差 = 内容真实高度（滚动不变量）
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }
    HintPage(
        listState = listState,
        onCollapsedChange = {},
    ) {
        item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }
        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(0)
                    .onGloballyPositioned { firstTopPx = it.positionInWindow().y.toInt() },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .entranceFade(delayMillis = 120, riseDp = 14f),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.setup_welcome_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.onCard,
                        modifier = Modifier.entranceFade(delayMillis = 220, riseDp = 12f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.setup_welcome_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.entranceFade(delayMillis = 320, riseDp = 10f),
                    )
                }
            }
        }

        item {
            HintCard(palette, modifier = Modifier.itemEntrance(1)) {
                HintSectionTitle(palette, stringResource(R.string.setup_welcome_feat_title))
                HintNoteRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_apps_24,
                    title = stringResource(R.string.setup_welcome_feat_apps),
                    body = stringResource(R.string.setup_welcome_feat_apps_desc),
                )
                HintNoteRow(
                    palette = palette,
                    iconRes = R.drawable.ic_settings_outline_24dp,
                    title = stringResource(R.string.setup_welcome_feat_system),
                    body = stringResource(R.string.setup_welcome_feat_system_desc),
                )
                HintNoteRow(
                    palette = palette,
                    iconRes = R.drawable.ic_outline_extension_24,
                    title = stringResource(R.string.setup_welcome_feat_auto),
                    body = stringResource(R.string.setup_welcome_feat_auto_desc),
                )
            }
        }

        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(2)
                    .onGloballyPositioned {
                        lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                    },
            ) {
                Text(
                    text = stringResource(R.string.setup_welcome_detail),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = palette.variant,
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 1. 免责声明（硬控 10 秒后才能勾选）
// ════════════════════════════════════════════════════════════════

@Composable
private fun DisclaimerPage(
    palette: HintPalette,
    agreed: Boolean,
    onAgreeChange: (Boolean) -> Unit,
) {
    var secondsLeft by remember { mutableIntStateOf(DISCLAIMER_SECONDS) }

    LaunchedEffect(Unit) {
        if (agreed) return@LaunchedEffect
        for (s in DISCLAIMER_SECONDS downTo 1) {
            secondsLeft = s
            delay(1000)
        }
        secondsLeft = 0
    }

    val unlocked = secondsLeft <= 0 || agreed

    val listState = rememberLazyListState()
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }
    HintPage(
        listState = listState,
        onCollapsedChange = {},
    ) {
        item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }
        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(0)
                    .onGloballyPositioned { firstTopPx = it.positionInWindow().y.toInt() },
            ) {
                Text(
                    text = stringResource(R.string.setup_disclaimer_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onCard,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildDisclaimerText(stringResource(R.string.setup_disclaimer_text)),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = palette.variant,
                )
            }
        }

        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(1)
                    .onGloballyPositioned {
                        lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                    },
            ) {
                if (!unlocked) {
                    Text(
                        text = stringResource(R.string.setup_disclaimer_hint, secondsLeft),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = unlocked) { onAgreeChange(!agreed) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = agreed,
                        enabled = unlocked,
                        onCheckedChange = onAgreeChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = palette.accent,
                            checkmarkColor = ensureReadable(palette.onAccent, palette.accent),
                            uncheckedColor = palette.variant.copy(alpha = 0.8f),
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.setup_disclaimer_agree),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onCard,
                    )
                }
            }
        }
    }
}

/**
 * 把免责声明里的 `<b>` / `<p>` 标记转成可渲染的 [AnnotatedString]。
 * 旧版走 HtmlCompat + TextView；Compose 的 Text 不认 Spanned，这里做个只认这两个标签的迷你解析。
 */
private fun buildDisclaimerText(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    while (i < raw.length) {
        when {
            raw.startsWith("<b>", i) -> { bold = true; i += 3 }
            raw.startsWith("</b>", i) -> { bold = false; i += 4 }
            raw.startsWith("<p>", i) -> {
                if (length > 0) append("\n\n")
                i += 3
            }
            raw.startsWith("</p>", i) -> { i += 4 }
            raw[i] == '<' -> {
                val end = raw.indexOf('>', i)
                i = if (end >= 0) end + 1 else raw.length
            }
            else -> {
                val next = raw.indexOf('<', i).let { if (it < 0) raw.length else it }
                val block = raw.substring(i, next)
                if (bold) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(block) }
                } else {
                    append(block)
                }
                i = next
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 2. 语言
// ════════════════════════════════════════════════════════════════

@Composable
private fun LanguagePage(
    palette: HintPalette,
    onSelectLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    val tags = ShizukuLocales.LOCALES
    val displayTags = ShizukuLocales.DISPLAY_LOCALES
    val currentTag = ShizukuSettings.getPreferences()
        .getString(ShizukuSettings.LANGUAGE, null) ?: "SYSTEM"
    val currentLocale = ShizukuSettings.getLocale()

    HintPage(
        listState = rememberLazyListState(),
        onCollapsedChange = {},
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            HintCard(palette, modifier = Modifier.itemEntrance(0)) {
                Text(
                    text = stringResource(R.string.setup_language_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(R.string.setup_language_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
                Text(
                    text = stringResource(R.string.setup_language_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
            }
        }

        items(tags.size) { index ->
            val tag = tags[index]
            val title: String
            val summary: String
            if (index == 0) {
                // 第一行 = 系统语言（和「调教设置」里语言那一项同一个措辞）
                title = context.getString(R.string.follow_system)
                summary = LocaleDelegate.systemLocale.getDisplayName(currentLocale)
            } else {
                val locale = java.util.Locale.forLanguageTag(displayTags[index])
                title = LanguageNames.display(context, displayTags[index])
                summary = if (!locale.script.isNullOrEmpty()) {
                    locale.getDisplayScript(currentLocale)
                } else {
                    locale.getDisplayName(currentLocale)
                }
            }
            LanguageRow(
                palette = palette,
                title = title,
                summary = summary.takeIf { it.isNotEmpty() && it != title },
                selected = tag == currentTag,
                onClick = { onSelectLanguage(tag) },
                modifier = Modifier.itemEntrance(index + 1),
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LanguageRow(
    palette: HintPalette,
    title: String,
    summary: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.accent,
                unselectedColor = palette.variant.copy(alpha = 0.8f),
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onCard,
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 3. 外观：MD3 / 玻璃 + 深色模式
// ════════════════════════════════════════════════════════════════

@Composable
private fun AppearancePage(
    palette: HintPalette,
    state: SetupUiState,
    onSelectUiStyle: (String) -> Unit,
    onSelectNightMode: (Int) -> Unit,
    onToggleBlackNight: (Boolean) -> Unit,
    onToggleSystemColor: (Boolean) -> Unit,
) {
    val glass = UiStyle.isGlass

    val listState = rememberLazyListState()
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }
    HintPage(
        listState = listState,
        onCollapsedChange = {},
    ) {
        item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }
        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(0)
                    .onGloballyPositioned { firstTopPx = it.positionInWindow().y.toInt() },
            ) {
                Text(
                    text = stringResource(R.string.setup_appearance_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(R.string.setup_appearance_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
                Text(
                    text = stringResource(R.string.setup_appearance_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Spacer(Modifier.height(4.dp))
                SetupChoiceCard(
                    palette = palette,
                    title = stringResource(R.string.ui_style_md3),
                    summary = stringResource(R.string.setup_style_md3_summary),
                    detail = null,
                    selected = !glass,
                    onClick = { onSelectUiStyle(ThemeHelper.UI_STYLE_MD3) },
                )
                SetupChoiceCard(
                    palette = palette,
                    title = stringResource(R.string.ui_style_glass),
                    summary = stringResource(R.string.setup_style_glass_summary),
                    detail = null,
                    selected = glass,
                    onClick = { onSelectUiStyle(ThemeHelper.UI_STYLE_GLASS) },
                )
            }
        }

        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(1)
                    .onGloballyPositioned {
                        lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                    },
            ) {
                Text(
                    text = stringResource(R.string.setup_dark_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(R.string.setup_dark_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Spacer(Modifier.height(2.dp))
                SetupRadioRow(
                    palette = palette,
                    text = stringResource(R.string.setup_mode_follow_system),
                    selected = state.nightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                    onClick = { onSelectNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) },
                )
                SetupRadioRow(
                    palette = palette,
                    text = stringResource(R.string.setup_mode_light),
                    selected = state.nightMode == AppCompatDelegate.MODE_NIGHT_NO,
                    onClick = { onSelectNightMode(AppCompatDelegate.MODE_NIGHT_NO) },
                )
                SetupRadioRow(
                    palette = palette,
                    text = stringResource(R.string.setup_mode_dark),
                    selected = state.nightMode == AppCompatDelegate.MODE_NIGHT_YES,
                    onClick = { onSelectNightMode(AppCompatDelegate.MODE_NIGHT_YES) },
                )
                Text(
                    text = stringResource(R.string.setup_dark_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                if (state.nightMode != AppCompatDelegate.MODE_NIGHT_NO) {
                    SetupSwitchRow(
                        palette = palette,
                        title = stringResource(R.string.settings_black_night_theme),
                        summary = stringResource(R.string.settings_black_night_theme_summary),
                        checked = state.blackNight,
                        onToggle = onToggleBlackNight,
                    )
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    SetupSwitchRow(
                        palette = palette,
                        title = stringResource(R.string.settings_use_system_color),
                        summary = null,
                        checked = state.systemColor,
                        onToggle = onToggleSystemColor,
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 4. 激活方式
// ════════════════════════════════════════════════════════════════

@Composable
private fun MethodPage(
    palette: HintPalette,
    selected: Int,
    onSelectMethod: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }
    HintPage(
        listState = listState,
        onCollapsedChange = {},
    ) {
        item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }
        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(0)
                    .onGloballyPositioned { firstTopPx = it.positionInWindow().y.toInt() },
            ) {
                Text(
                    text = stringResource(R.string.setup_start_method_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(R.string.setup_start_method_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
                Spacer(Modifier.height(4.dp))
                SetupChoiceCard(
                    palette = palette,
                    title = stringResource(R.string.setup_start_method_wadb),
                    summary = stringResource(R.string.setup_start_method_wadb_summary),
                    detail = stringResource(R.string.setup_start_method_wadb_detail),
                    selected = selected == ShizukuSettings.StartMethod.WIRELESS_ADB,
                    onClick = { onSelectMethod(ShizukuSettings.StartMethod.WIRELESS_ADB) },
                )
                SetupChoiceCard(
                    palette = palette,
                    title = stringResource(R.string.setup_start_method_root),
                    summary = stringResource(R.string.setup_start_method_root_summary),
                    detail = stringResource(R.string.setup_start_method_root_detail),
                    selected = selected == ShizukuSettings.StartMethod.ROOT,
                    onClick = { onSelectMethod(ShizukuSettings.StartMethod.ROOT) },
                )
                SetupChoiceCard(
                    palette = palette,
                    title = stringResource(R.string.setup_start_method_adb),
                    summary = stringResource(R.string.setup_start_method_adb_summary),
                    detail = stringResource(R.string.setup_start_method_adb_detail),
                    selected = selected == ShizukuSettings.StartMethod.COMPUTER_ADB,
                    onClick = { onSelectMethod(ShizukuSettings.StartMethod.COMPUTER_ADB) },
                )
            }
        }

        item {
            HintNote(
                palette = palette,
                iconRes = R.drawable.ic_outline_info_24,
                text = stringResource(R.string.setup_method_note),
                modifier = Modifier
                    .itemEntrance(1)
                    .onGloballyPositioned {
                        lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                    },
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 5. 激活（真做事的一步）
// ════════════════════════════════════════════════════════════════

@Composable
private fun ActivatePage(
    palette: HintPalette,
    state: SetupUiState,
    onStartRoot: () -> Unit,
    onStartWireless: () -> Unit,
    onStartPairing: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onViewCommand: () -> Unit,
    onActivateDhizuku: () -> Unit,
) {
    val listState = rememberLazyListState()
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }
    HintPage(
        listState = listState,
        onCollapsedChange = {},
    ) {
        item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }
        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(0)
                    .onGloballyPositioned { firstTopPx = it.positionInWindow().y.toInt() },
            ) {
                Text(
                    text = stringResource(R.string.setup_activate_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(R.string.setup_activate_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.variant,
                )
                Text(
                    text = stringResource(R.string.setup_activate_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Spacer(Modifier.height(6.dp))
                ServiceStatusRow(
                    palette = palette,
                    label = stringResource(R.string.activation_status_service),
                    value = stringResource(
                        if (state.shizukuRunning) R.string.setup_activate_running
                        else R.string.setup_activate_not_running,
                    ),
                    running = state.shizukuRunning,
                )
                ServiceStatusRow(
                    palette = palette,
                    label = stringResource(R.string.activation_status_dhizuku),
                    value = stringResource(
                        if (state.dhizukuActive) R.string.activation_status_activated
                        else R.string.activation_status_not_activated,
                    ),
                    running = state.dhizukuActive,
                )
            }
        }

        item {
            HintCard(palette, modifier = Modifier.itemEntrance(1)) {
                ActivateMethodSection(
                    palette = palette,
                    state = state,
                    onStartRoot = onStartRoot,
                    onStartWireless = onStartWireless,
                    onStartPairing = onStartPairing,
                    onOpenDeveloperOptions = onOpenDeveloperOptions,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onViewCommand = onViewCommand,
                )
            }
        }

        item {
            HintCard(palette, modifier = Modifier.itemEntrance(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dhizuku_24dp),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.activation_method_dhizuku),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onCard,
                    )
                }
                Text(
                    text = stringResource(R.string.activation_dhizuku_desc_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Text(
                    text = stringResource(R.string.activation_dhizuku_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.error,
                )
                Spacer(Modifier.height(4.dp))
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(
                        if (state.activatingDhizuku) R.string.setup_activate_dhizuku_running
                        else R.string.setup_activate_dhizuku,
                    ),
                    enabled = state.shizukuRunning && !state.activatingDhizuku,
                    onClick = onActivateDhizuku,
                )
            }
        }

        item {
            HintNote(
                palette = palette,
                iconRes = R.drawable.ic_outline_info_24,
                text = stringResource(
                    if (state.shizukuRunning) R.string.setup_activate_hint_done
                    else R.string.setup_activate_hint,
                ),
                modifier = Modifier
                    .itemEntrance(3)
                    .onGloballyPositioned {
                        lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                    },
            )
        }
    }
}

/** 按当前选择的方式，给出对应的操作区和按钮。 */
@Composable
private fun ActivateMethodSection(
    palette: HintPalette,
    state: SetupUiState,
    onStartRoot: () -> Unit,
    onStartWireless: () -> Unit,
    onStartPairing: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onViewCommand: () -> Unit,
) {
    when (state.preferredMethod) {
        ShizukuSettings.StartMethod.ROOT -> {
            MethodTitleRow(palette, R.drawable.ic_root_24dp, stringResource(R.string.setup_start_method_root))
            Text(
                text = stringResource(R.string.setup_start_method_root_detail),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
            Text(
                text = stringResource(R.string.setup_activate_root_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
            Spacer(Modifier.height(4.dp))
            HintPrimaryButton(
                palette = palette,
                text = stringResource(R.string.setup_activate_start_root),
                enabled = !state.shizukuRunning,
                onClick = onStartRoot,
            )
        }

        ShizukuSettings.StartMethod.WIRELESS_ADB -> {
            MethodTitleRow(palette, R.drawable.ic_wireless_adb_24dp, stringResource(R.string.setup_start_method_wadb))
            if (state.paired) {
                Text(
                    text = stringResource(R.string.setup_wadb_paired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onCard,
                )
                Text(
                    text = stringResource(R.string.setup_start_method_wadb_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.variant,
                )
                Spacer(Modifier.height(4.dp))
                HintPrimaryButton(
                    palette = palette,
                    text = stringResource(R.string.setup_activate_start_wadb),
                    enabled = !state.shizukuRunning,
                    onClick = onStartWireless,
                )
            } else {
                Text(
                    text = stringResource(R.string.setup_wadb_unpaired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onCard,
                )
                Spacer(Modifier.height(4.dp))
                HintSectionTitle(palette, stringResource(R.string.hint_section_steps))
                HintStep(
                    palette = palette,
                    index = 1,
                    title = stringResource(R.string.setup_wadb_step_open_t),
                    body = stringResource(R.string.setup_wadb_step_open_b),
                    showConnector = true,
                )
                HintStep(
                    palette = palette,
                    index = 2,
                    title = stringResource(R.string.setup_wadb_step_code_t),
                    body = stringResource(R.string.setup_wadb_step_code_b),
                    showConnector = true,
                )
                HintStep(
                    palette = palette,
                    index = 3,
                    title = stringResource(R.string.setup_wadb_step_input_t),
                    body = stringResource(R.string.setup_wadb_step_input_b),
                    showConnector = false,
                )
                if (!state.notificationsAllowed) {
                    Text(
                        text = stringResource(R.string.setup_wadb_need_notification),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintPrimaryButton(
                        palette = palette,
                        text = stringResource(R.string.setup_wadb_action_pair),
                        onClick = onStartPairing,
                    )
                    HintSecondaryButton(
                        palette = palette,
                        text = stringResource(R.string.setup_wadb_action_open_dev),
                        onClick = onOpenDeveloperOptions,
                    )
                    if (!state.notificationsAllowed) {
                        HintSecondaryButton(
                            palette = palette,
                            text = stringResource(R.string.notification_settings),
                            onClick = onOpenNotificationSettings,
                        )
                    }
                }
            }
        }

        else -> {
            MethodTitleRow(palette, R.drawable.ic_adb_24dp, stringResource(R.string.setup_start_method_adb))
            Text(
                text = stringResource(R.string.setup_start_method_adb_detail),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
            Text(
                text = stringResource(R.string.setup_activate_adb_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.variant,
            )
            Spacer(Modifier.height(4.dp))
            HintPrimaryButton(
                palette = palette,
                text = stringResource(R.string.setup_activate_view_command),
                onClick = onViewCommand,
            )
        }
    }
}

@Composable
private fun MethodTitleRow(palette: HintPalette, iconRes: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onCard,
        )
    }
}

/** 状态行：圆点 + 名称 + 值（服务 / 设备所有者）。 */
@Composable
private fun ServiceStatusRow(
    palette: HintPalette,
    label: String,
    value: String,
    running: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (running) palette.accent else palette.variant.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.variant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (running) palette.accent else palette.onCard,
        )
    }
}

// ════════════════════════════════════════════════════════════════
// 6. 完成
// ════════════════════════════════════════════════════════════════

@Composable
private fun FinishPage(palette: HintPalette, state: SetupUiState) {
    val methodText = stringResource(
        when (state.preferredMethod) {
            ShizukuSettings.StartMethod.ROOT -> R.string.setup_start_method_root
            ShizukuSettings.StartMethod.WIRELESS_ADB -> R.string.setup_start_method_wadb
            else -> R.string.setup_start_method_adb
        },
    )
    val serviceText = stringResource(
        if (state.shizukuRunning) R.string.setup_activate_running
        else R.string.setup_activate_not_running,
    )
    val dhizukuText = stringResource(
        if (state.dhizukuActive) R.string.activation_status_activated
        else R.string.activation_status_not_activated,
    )

    val listState = rememberLazyListState()
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }
    HintPage(
        listState = listState,
        onCollapsedChange = {},
    ) {
        item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }
        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(0)
                    .onGloballyPositioned { firstTopPx = it.positionInWindow().y.toInt() },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .entranceFade(delayMillis = 120, riseDp = 14f),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.setup_finish_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.onCard,
                        modifier = Modifier.entranceFade(delayMillis = 220, riseDp = 12f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.setup_finish_summary,
                            methodText,
                            serviceText,
                            dhizukuText,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.variant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.entranceFade(delayMillis = 320, riseDp = 10f),
                    )
                }
            }
        }

        item {
            HintCard(
                palette,
                modifier = Modifier
                    .itemEntrance(1)
                    .onGloballyPositioned {
                        lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                    },
            ) {
                Text(
                    text = stringResource(R.string.setup_finish_detail),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = palette.variant,
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 通用小件
// ════════════════════════════════════════════════════════════════

/**
 * 开场动画：元素淡入 + 轻升（配不同延迟 = 阶梯感）。
 * 只在元素进入组合时播一次；值在绘制阶段读，不触发重组。
 * riseDp 给负数 = 从上方落下（顶栏用）。
 */
@Composable
private fun Modifier.entranceFade(delayMillis: Int, riseDp: Float = 12f): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 460,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    val rise = with(LocalDensity.current) { riseDp.dp.toPx() }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * rise
    }
}

/**
 * 自适应顶部留白（「主体居中」核心）：
 * 由首/尾卡片的窗口坐标差量出内容真实高度 —— 能装下就把剩余空间上下平分
 * （主体自然落在屏幕中部，顶部不留大段空白）；装不下就只留 16dp 正常滚动。
 */
@Composable
private fun SetupAutoTopGap(listState: LazyListState, firstTopPx: Int, lastBottomPx: Int) {
    val availPx = listState.layoutInfo.viewportSize.height
    val contentPx = (lastBottomPx - firstTopPx).coerceAtLeast(0)
    val density = LocalDensity.current
    val minTopPx = with(density) { 16.dp.toPx() }
    val target = if (availPx > 0 && contentPx > 0) {
        with(density) { maxOf(minTopPx, (availPx - contentPx) / 2f).toDp() }
    } else {
        16.dp
    }
    val top by animateDpAsState(targetValue = target, animationSpec = tween(240), label = "autoTop")
    Spacer(Modifier.height(top))
}

/**
 * 「更新欢迎页」：给从老版本升级上来的主人看的一次性欢迎（新装用户看不到）。
 * 复用首次引导同一套组件、布局居中与开场动画。
 */
@Composable
fun UpdateWelcomeScreen(
    versionName: String,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val style = UiStyle.current
    val palette = remember(style) { resolveHintPalette(context) }

    val listState = rememberLazyListState()
    var firstTopPx by remember { mutableStateOf(0) }
    var lastBottomPx by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            HintPage(
                listState = listState,
                onCollapsedChange = {},
            ) {
                item { SetupAutoTopGap(listState, firstTopPx, lastBottomPx) }

                item {
                    HintCard(
                        palette,
                        modifier = Modifier
                            .itemEntrance(0)
                            .onGloballyPositioned {
                                firstTopPx = it.positionInWindow().y.toInt()
                            },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter = painterResource(R.mipmap.ic_launcher),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .entranceFade(delayMillis = 120, riseDp = 14f),
                            )
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = stringResource(R.string.update_welcome_title),
                                style = MaterialTheme.typography.headlineMedium,
                                color = palette.onCard,
                                modifier = Modifier.entranceFade(delayMillis = 220, riseDp = 12f),
                            )
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(palette.accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                                    .entranceFade(delayMillis = 300, riseDp = 10f),
                            ) {
                                Text(
                                    text = stringResource(R.string.update_welcome_badge, versionName),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.accent,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.update_welcome_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.variant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.entranceFade(delayMillis = 380, riseDp = 10f),
                            )
                        }
                    }
                }

                item {
                    HintCard(palette, modifier = Modifier.itemEntrance(1)) {
                        HintSectionTitle(palette, stringResource(R.string.update_welcome_highlights_title))
                        HintNoteRow(
                            palette = palette,
                            iconRes = R.drawable.ic_outline_dark_mode_24,
                            title = stringResource(R.string.update_welcome_h1_t),
                            body = stringResource(R.string.update_welcome_h1_b),
                        )
                        HintNoteRow(
                            palette = palette,
                            iconRes = R.drawable.ic_bolt_24dp,
                            title = stringResource(R.string.update_welcome_h2_t),
                            body = stringResource(R.string.update_welcome_h2_b),
                        )
                        HintNoteRow(
                            palette = palette,
                            iconRes = R.drawable.ic_root_24dp,
                            title = stringResource(R.string.update_welcome_h3_t),
                            body = stringResource(R.string.update_welcome_h3_b),
                        )
                        HintNoteRow(
                            palette = palette,
                            iconRes = R.drawable.ic_toolbox_24,
                            title = stringResource(R.string.update_welcome_h4_t),
                            body = stringResource(R.string.update_welcome_h4_b),
                        )
                    }
                }

                item {
                    HintCard(
                        palette,
                        modifier = Modifier
                            .itemEntrance(2)
                            .onGloballyPositioned {
                                lastBottomPx = it.positionInWindow().y.toInt() + it.size.height
                            },
                    ) {
                        HintPrimaryButton(
                            palette = palette,
                            text = stringResource(R.string.update_welcome_action),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onStart,
                        )
                    }
                }
            }
        }
    }
}

/** 选择卡：整张卡可点，单选圆点 + 标题 + 摘要（选中时展开详情），玻璃卡上加一圈强调色描边。 */
@Composable
private fun SetupChoiceCard(
    palette: HintPalette,
    title: String,
    summary: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        HintCard(palette) {
            Row(verticalAlignment = Alignment.Top) {
                RadioButton(
                    selected = selected,
                    onClick = onClick,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = palette.accent,
                        unselectedColor = palette.variant.copy(alpha = 0.8f),
                    ),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 10.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.onCard,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.variant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (detail != null) {
                        AnimatedVisibility(visible = selected) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.variant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = 1.6.dp,
                        color = palette.accent,
                        shape = RoundedCornerShape(palette.cardCorner),
                    ),
            )
        }
    }
}

/** 单选行（深色模式三选一）。 */
@Composable
private fun SetupRadioRow(
    palette: HintPalette,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.accent,
                unselectedColor = palette.variant.copy(alpha = 0.8f),
            ),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.onCard,
        )
    }
}

/** 开关行（黑色夜间 / 系统取色）。 */
@Composable
private fun SetupSwitchRow(
    palette: HintPalette,
    title: String,
    summary: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggle(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onCard,
            )
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
            onCheckedChange = onToggle,
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