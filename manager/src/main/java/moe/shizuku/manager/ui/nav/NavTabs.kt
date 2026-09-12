package moe.shizuku.manager.ui.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

/**
 * 底栏入口的自定义：**显示哪些 + 什么顺序**，持久化在偏好设置里。
 *
 * 这里是运行时可观察状态（和 UiStyle 一个套路）：设置页改完写偏好 + 版本号 +1，
 * 主界面底栏随之重组 —— 不需要重建 Activity，也不会把用户从设置里踢出去。
 */
@Immutable
data class NavTabSpec(
    val key: String,
    @DrawableRes val destinationId: Int,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

object NavTabs {

    const val KEY_HOME = "home"
    const val KEY_APPS = "apps"
    const val KEY_MODULES = "modules"
    const val KEY_COMPUT = "comput"
    const val KEY_TOOLS = "tools"
    const val KEY_LOGS = "logs"
    const val KEY_SETTINGS = "settings"

    private const val PREF_ORDER = "nav_tab_order"
    private const val PREF_HIDDEN = "nav_tab_hidden"

    /** 可放进底栏的入口（只有顶层页面：次级页进底栏会把底栏自己藏起来） */
    val all: List<NavTabSpec> = listOf(
        NavTabSpec(
            KEY_HOME, R.id.home_fragment, R.string.overview, R.drawable.ic_outline_home_24,
            Icons.Outlined.Home, Icons.Filled.Home,
        ),
        NavTabSpec(
            KEY_APPS, R.id.apps_fragment, R.string.home_app_management_title,
            R.drawable.ic_outline_apps_24, Icons.Outlined.Apps, Icons.Filled.Apps,
        ),
        NavTabSpec(
            KEY_LOGS, R.id.logs_fragment, R.string.api_log_title,
            R.drawable.ic_outline_assignment_24, Icons.Outlined.Assignment, Icons.Filled.Assignment,
        ),
        NavTabSpec(
            KEY_MODULES, R.id.modules_fragment, R.string.modules_title,
            R.drawable.ic_outline_extension_24, Icons.Outlined.Extension, Icons.Filled.Extension,
        ),
        NavTabSpec(
            KEY_COMPUT, R.id.comput_fragment, R.string.comput_title,
            R.drawable.ic_terminal_24, Icons.Outlined.Terminal, Icons.Filled.Terminal,
        ),
        NavTabSpec(
            KEY_TOOLS, R.id.toolbox_fragment, R.string.toolbox_title,
            R.drawable.ic_toolbox_24, Icons.Outlined.Build, Icons.Filled.Build,
        ),
        NavTabSpec(
            KEY_SETTINGS, R.id.settings_fragment, R.string.settings_title,
            R.drawable.ic_outline_settings_24, Icons.Outlined.Settings, Icons.Filled.Settings,
        ),
    )

    val defaultKeys: List<String> = all.map { it.key }

    /**
     * 默认**只有这 4 个**在底栏：首页 / 被调教的小可爱们（应用）/ 工具箱 / 调教设置。
     *
     * 模块、控制台、日志默认收起来（它们的入口都在工具箱里），主人想要再自己去
     * 设置 → 底部栏 打开 —— 以前默认全开，底栏挤成一条，现在按用途收拢。
     */
    private val DEFAULT_VISIBLE = setOf(KEY_HOME, KEY_APPS, KEY_TOOLS, KEY_SETTINGS)

    /** 默认收起来的那批（没存过偏好时用） */
    private val defaultHidden: Set<String> get() = all.map { it.key }.filter { it !in DEFAULT_VISIBLE }.toSet()

    private const val PREF_DEFAULTS_VERSION = "nav_tab_defaults_version"

    /** 默认配置的版本号：+1 = 老安装升级上来时也切到新默认（之后主人的自定义不再动） */
    private const val DEFAULTS_VERSION = 2

    /**
     * 老版本默认「底栏全开」，现在默认只留 首页 / 应用 / 工具箱 / 设置。
     *
     * 只迁一次：装过老版本的主人这次启动后就是新默认，之后自己改的（顺序、显隐）不会再被覆盖。
     */
    private fun migrateDefaultsIfNeeded() {
        val prefs = prefs()
        if (prefs.getInt(PREF_DEFAULTS_VERSION, 1) >= DEFAULTS_VERSION) return
        prefs.edit()
            .putString(PREF_HIDDEN, defaultHidden.joinToString(","))
            .putInt(PREF_DEFAULTS_VERSION, DEFAULTS_VERSION)
            .apply()
    }

    init {
        migrateDefaultsIfNeeded()
    }

    private var version by mutableIntStateOf(0)

    private fun prefs() = ShizukuSettings.getPreferences()

    private fun split(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** 当前顺序（含被隐藏的）：偏好里没记录的（新增入口）接在末尾 */
    fun order(): List<NavTabSpec> {
        version // 订阅：改完自动重组
        val saved = split(prefs().getString(PREF_ORDER, null))
        val known = saved.mapNotNull { key -> all.firstOrNull { it.key == key } }
        return known + all.filter { spec -> known.none { it.key == spec.key } }
    }

    fun hiddenKeys(): Set<String> {
        version
        val saved = prefs().getString(PREF_HIDDEN, null)
        // 没存过 → 用默认（只显示首页 / 应用 / 工具箱 / 设置）
        if (saved == null) return defaultHidden
        return split(saved).toSet()
    }

    fun isEnabled(key: String): Boolean = key !in hiddenKeys()

    /** 底栏最终显示顺序：按自定义顺序 + 过滤隐藏项；全隐藏时兜底只留首页 */
    fun visible(): List<NavTabSpec> {
        val hidden = hiddenKeys()
        val list = order().filter { it.key !in hidden }
        return list.ifEmpty { all.filter { it.key == KEY_HOME } }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        val hidden = hiddenKeys().toMutableSet()
        // 至少留一个入口，否则底栏变空（兜底也会回到首页，但别让用户以为设置没生效）
        val visibleCount = all.count { it.key !in hidden }
        if (!enabled && visibleCount <= 1) return
        if (enabled) hidden.remove(key) else hidden.add(key)
        prefs().edit().putString(PREF_HIDDEN, hidden.joinToString(",")).apply()
        version++
    }

    /** 在当前顺序里把 key 上移/下移 delta 位 */
    fun move(key: String, delta: Int) {
        val keys = order().map { it.key }.toMutableList()
        val from = keys.indexOf(key)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, keys.size - 1)
        if (to == from) return
        keys.removeAt(from)
        keys.add(to, key)
        prefs().edit().putString(PREF_ORDER, keys.joinToString(",")).apply()
        version++
    }

    fun reset() {
        prefs().edit().remove(PREF_ORDER).remove(PREF_HIDDEN).apply()
        version++
    }

    fun isDefault(): Boolean = order().map { it.key } == defaultKeys && hiddenKeys() == defaultHidden
}
