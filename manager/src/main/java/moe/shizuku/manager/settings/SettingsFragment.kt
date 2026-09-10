package moe.shizuku.manager.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.updatePadding
import androidx.preference.*
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.AUTO_UPDATE
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.ShizukuSettings.WATCHDOG_ENABLED
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.CrashLog
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.update.AutoUpdateScheduler
import moe.shizuku.manager.update.DownloadProgressDialog
import moe.shizuku.manager.update.UpdateChecker
import moe.shizuku.manager.watchdog.WatchdogService
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.manager.ShizukuLocales
import rikka.widget.borderview.BorderRecyclerView
import java.util.*
import moe.shizuku.manager.ShizukuSettings.LANGUAGE as KEY_LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE as KEY_NIGHT_MODE

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var languagePreference: ListPreference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var startupPreference: PreferenceCategory
    private lateinit var translationPreference: Preference
    private lateinit var translationContributorsPreference: Preference
    private lateinit var useSystemColorPreference: TwoStatePreference
    private lateinit var themeColorPreference: Preference
    private lateinit var uiStylePreference: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        languagePreference = findPreference(KEY_LANGUAGE)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        startOnBootPreference = findPreference(KEEP_START_ON_BOOT)!!
        startupPreference = findPreference("startup")!!
        translationPreference = findPreference("translation")!!
        translationContributorsPreference = findPreference("translation_contributors")!!
        useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!
        themeColorPreference = findPreference("theme_color")!!
        themeColorPreference.isVisible = !ThemeHelper.isUsingSystemColor()
        themeColorPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

        uiStylePreference = findPreference("ui_style")!!
        uiStylePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

        findPreference<Preference>("show_logs_tab")!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                activity?.recreate()
                true
            }

        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)

        startOnBootPreference.isChecked = context.packageManager.isComponentEnabled(componentName)
        startOnBootPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    context.packageManager.setComponentEnabled(componentName, newValue)
                    context.packageManager.isComponentEnabled(componentName) == newValue
                } else false
            }

        val watchdogPreference = findPreference<TwoStatePreference>(WATCHDOG_ENABLED)!!
        watchdogPreference.isChecked = ShizukuSettings.isWatchdogEnabled()
        watchdogPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    if (newValue) {
                        WatchdogService.start(context)
                    } else {
                        WatchdogService.stop(context)
                    }
                    true
                } else false
            }

        val autoUpdatePreference = findPreference<TwoStatePreference>(AUTO_UPDATE)!!
        autoUpdatePreference.isChecked = ShizukuSettings.isAutoUpdateEnabled()
        autoUpdatePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    if (newValue) {
                        AutoUpdateScheduler.schedule(context)
                    } else {
                        AutoUpdateScheduler.cancel(context)
                    }
                    true
                } else false
            }

        languagePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is String) {
                    val locale: Locale = if ("SYSTEM" == newValue) {
                        LocaleDelegate.systemLocale
                    } else {
                        Locale.forLanguageTag(newValue)
                    }
                    LocaleDelegate.defaultLocale = locale
                    activity?.recreate()
                }
                true
            }

        setupLocalePreference()

        nightModePreference.value = ShizukuSettings.getNightMode()
        nightModePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        activity?.recreate()
                    }
                }
                true
            }
        if (ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            blackNightThemePreference.isChecked = ThemeHelper.isBlackNightTheme(context)
            blackNightThemePreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
                    if (ResourceUtils.isNightMode(context.resources.configuration)) {
                        activity?.recreate()
                    }
                    true
                }
        } else {
            blackNightThemePreference.isVisible = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            useSystemColorPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                    if (value is Boolean) {
                        themeColorPreference.isVisible = !value
                        if (ThemeHelper.isUsingSystemColor() != value) {
                            activity?.recreate()
                        }
                    }
                    true
                }
        } else {
            useSystemColorPreference.isVisible = false
        }

        translationPreference.summary =
            context.getString(R.string.settings_translation_summary, context.getString(R.string.app_name))
        translationPreference.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
            true
        }

        val contributors = context.getString(R.string.translation_contributors).toHtml().toString()
        if (contributors.isNotBlank()) {
            translationContributorsPreference.summary = contributors
        } else {
            translationContributorsPreference.isVisible = false
        }

        crashLogsPreference = findPreference("crash_logs")!!
        updateCrashLogsSummary()
        crashLogsPreference.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { showCrashLogsDialog(); true }

        findPreference<Preference>("api_log")!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                // 跳主界面「日志」Tab（原独立 ApiLogActivity）
                context?.let {
                    it.startActivity(MainActivity.tabIntent(it, MainActivity.TAB_LOGS))
                }
                true
            }

        val checkUpdatePreference = findPreference<Preference>("check_update")!!
        checkUpdatePreference.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
            Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
            UpdateChecker.checkForUpdate(context) { info ->
                if (info != null) {
                    // Android 13+: notifications (progress bar) need runtime permission
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10086)
                    }
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_available_title)
                        .setMessage(context.getString(R.string.update_dialog_message, info.tagName, info.body))
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            startDownloadWithDialog(context, info)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    Toast.makeText(context, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
    }

    /**
     * Shows an in-app progress dialog (bar + percentage + size + speed)
     * alongside the notification progress while downloading.
     */
    private fun startDownloadWithDialog(context: Context, info: UpdateChecker.ReleaseInfo) {
        val progressDialog = DownloadProgressDialog.show(
            context,
            title = context.getString(R.string.update_downloading),
            version = info.tagName,
            onCancel = { UpdateChecker.cancelDownload() }
        )
        progressDialog.setState(context.getString(R.string.update_connecting))

        UpdateChecker.downloadAndInstall(context, info, object : UpdateChecker.DownloadListener {
            override fun onProgress(downloaded: Long, total: Long, speedBps: Long) {
                if (progressDialog.isShowing) progressDialog.update(downloaded, total, speedBps)
            }

            override fun onRetry(attempt: Int, max: Int) {
                if (progressDialog.isShowing) {
                    progressDialog.setState(context.getString(R.string.update_retrying, attempt, max))
                }
            }

            override fun onComplete(apkFile: java.io.File) {
                progressDialog.dismiss()
            }

            override fun onFailed(reason: String) {
                progressDialog.dismiss()
            }

            override fun onCancelled() {
                progressDialog.dismiss()
                Toast.makeText(context, R.string.update_download_cancelled, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private lateinit var crashLogsPreference: Preference

    private fun updateCrashLogsSummary() {
        val count = CrashLog.files().size
        crashLogsPreference.summary = if (count == 0) {
            getString(R.string.settings_crash_logs_summary)
        } else {
            getString(R.string.settings_crash_logs_count, count)
        }
    }

    private fun showCrashLogsDialog() {
        val context = requireContext()
        val files = CrashLog.files()
        if (files.isEmpty()) {
            Toast.makeText(context, R.string.settings_crash_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = files.map { displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_crash_logs)
            .setItems(labels) { _, which -> showCrashLogContent(files[which]) }
            .setNegativeButton(R.string.settings_crash_logs_delete_all) { _, _ ->
                CrashLog.deleteAll()
                updateCrashLogsSummary()
                Toast.makeText(context, R.string.settings_crash_logs_deleted, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCrashLogContent(file: java.io.File) {
        val context = requireContext()
        val content = file.readText()

        val padding = (context.resources.displayMetrics.density * 16).toInt()
        val textView = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = content
        }
        val scrollView = ScrollView(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(textView)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(displayName(file))
            .setView(scrollView)
            .setNeutralButton(R.string.settings_crash_logs_share) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                try {
                    startActivity(Intent.createChooser(intent, file.name))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setPositiveButton(R.string.settings_crash_logs_delete) { _, _ ->
                file.delete()
                updateCrashLogsSummary()
                Toast.makeText(context, R.string.settings_crash_logs_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displayName(file: java.io.File): String {
        // crash-yyyyMMdd-HHmmss.txt -> yyyy-MM-dd HH:mm:ss
        return try {
            val name = file.name.removePrefix("crash-").removeSuffix(".txt")
            val sdf = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            out.format(sdf.parse(name)!!)
        } catch (e: Exception) {
            file.name
        }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)
        // 亚克力悬浮导航遮挡避让：末项可完整滚到胶囊上方
        recyclerView.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.ksu_content_bottom_padding))

        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin = recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin).toInt()
            lp.leftMargin = lp.rightMargin
        }

        return recyclerView
    }

    private fun setupLocalePreference() {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(locale)
            else
                locale.getDisplayName(locale)

            val localizedLocaleName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(currentLocale)
            else
                locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName<small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }
            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }
            else -> {
                ""
            }
        }
    }
}