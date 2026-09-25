package com.mooddiary.app

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class WeekStart { SUNDAY, MONDAY }

/**
 * 应用设置。全部存在本机 SharedPreferences，无需权限、不联网。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reminderEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStart: Int = 22,   // 22:00
    val quietEnd: Int = 8,      // 次日 8:00
    val defaultMoodId: Int = 5, // 默认「开心」
    val weekStart: WeekStart = WeekStart.SUNDAY
)

/**
 * 提醒的免打扰判断。用整数比较而非 LocalTime，便于单测。
 */
object QuietHours {
    /** 跨天时段（如 22→8）要区别于同天时段（如 1→6） */
    fun isQuiet(start: Int, end: Int, hour: Int): Boolean =
        if (start <= end) hour in start until end   // 同一天内
        else hour >= start || hour < end            // 跨越午夜

    fun isQuiet(settings: AppSettings, hour: Int = LocalTime.now().hour): Boolean =
        settings.quietHoursEnabled && isQuiet(settings.quietStart, settings.quietEnd, hour)
}

/**
 * 设置的读写入口。用 StateFlow 暴露，UI 改动后立刻刷新。
 * 提醒开关与 Reminder 的 WorkManager 调度保持同步。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        themeMode = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM },
        reminderEnabled = prefs.getBoolean(KEY_REMINDER, false),
        quietHoursEnabled = prefs.getBoolean(KEY_QUIET_ENABLED, false),
        quietStart = prefs.getInt(KEY_QUIET_START, 22),
        quietEnd = prefs.getInt(KEY_QUIET_END, 8),
        defaultMoodId = prefs.getInt(KEY_DEFAULT_MOOD, 5),
        weekStart = WeekStart.entries.getOrElse(prefs.getInt(KEY_WEEK_START, 0)) { WeekStart.SUNDAY }
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block)
        _settings.value = load()
    }

    fun setThemeMode(mode: ThemeMode) = edit { putInt(KEY_THEME, mode.ordinal) }

    fun setReminderEnabled(enabled: Boolean) {
        edit { putBoolean(KEY_REMINDER, enabled) }
        // 与 Reminder 的 WorkManager 调度同步
        Reminder.setEnabled(appContext, enabled)
    }

    fun setQuietHoursEnabled(enabled: Boolean) = edit { putBoolean(KEY_QUIET_ENABLED, enabled) }

    fun setQuietRange(start: Int, end: Int) {
        require(start in 0..23 && end in 0..23) { "小时必须在 0..23" }
        edit {
            putInt(KEY_QUIET_START, start)
            putInt(KEY_QUIET_END, end)
        }
    }

    fun setDefaultMood(moodId: Int) = edit { putInt(KEY_DEFAULT_MOOD, moodId) }

    fun setWeekStart(weekStart: WeekStart) = edit { putInt(KEY_WEEK_START, weekStart.ordinal) }

    /** 应用启动时把已持久化的提醒开关同步给 WorkManager（如进程重启后） */
    fun syncReminder() {
        val enabled = _settings.value.reminderEnabled
        if (enabled != Reminder.isEnabled(appContext)) {
            Reminder.setEnabled(appContext, enabled)
        }
    }

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_REMINDER = "reminder_enabled"
        private const val KEY_QUIET_ENABLED = "quiet_enabled"
        private const val KEY_QUIET_START = "quiet_start"
        private const val KEY_QUIET_END = "quiet_end"
        private const val KEY_DEFAULT_MOOD = "default_mood"
        private const val KEY_WEEK_START = "week_start"
    }
}

/** 根据设置决定深色模式（供 MoodDiaryTheme 使用） */
@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
