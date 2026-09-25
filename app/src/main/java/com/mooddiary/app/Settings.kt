package com.mooddiary.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import java.time.LocalTime

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class WeekStart { SUNDAY, MONDAY }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reminderEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStart: Int = 22,
    val quietEnd: Int = 8,
    val defaultMoodId: Int = 5,
    val weekStart: WeekStart = WeekStart.SUNDAY
)

object QuietHours {
    fun isQuiet(start: Int, end: Int, hour: Int): Boolean =
        if (start <= end) hour in start until end
        else hour >= start || hour < end

    fun isQuiet(settings: AppSettings, hour: Int = LocalTime.now().hour): Boolean =
        settings.quietHoursEnabled && isQuiet(settings.quietStart, settings.quietEnd, hour)
}

/**
 * 设置的读写入口。
 *
 * 用 callbackFlow + SharedPreferences 监听器暴露设置流，
 * 这样任何地方改了偏好都能自动通知所有观察者——比手动 MutableStateFlow 更可靠。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val settings: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.onStart { emit(read()) }.distinctUntilChanged()

    fun current(): AppSettings = read()

    private fun read(): AppSettings = AppSettings(
        themeMode = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM },
        reminderEnabled = prefs.getBoolean(KEY_REMINDER, false),
        quietHoursEnabled = prefs.getBoolean(KEY_QUIET_ENABLED, false),
        quietStart = prefs.getInt(KEY_QUIET_START, 22),
        quietEnd = prefs.getInt(KEY_QUIET_END, 8),
        defaultMoodId = prefs.getInt(KEY_DEFAULT_MOOD, 5),
        weekStart = WeekStart.entries.getOrElse(prefs.getInt(KEY_WEEK_START, 0)) { WeekStart.SUNDAY }
    )

    private fun commit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
    }

    fun setThemeMode(mode: ThemeMode) = commit { putInt(KEY_THEME, mode.ordinal) }
    fun setReminderEnabled(enabled: Boolean) {
        commit { putBoolean(KEY_REMINDER, enabled) }
        Reminder.setEnabled(appContext, enabled)
    }
    fun setQuietHoursEnabled(enabled: Boolean) = commit { putBoolean(KEY_QUIET_ENABLED, enabled) }
    fun setQuietRange(start: Int, end: Int) {
        require(start in 0..23 && end in 0..23) { "hour must be 0..23" }
        commit { putInt(KEY_QUIET_START, start); putInt(KEY_QUIET_END, end) }
    }
    fun setDefaultMood(moodId: Int) = commit { putInt(KEY_DEFAULT_MOOD, moodId) }
    fun setWeekStart(weekStart: WeekStart) = commit { putInt(KEY_WEEK_START, weekStart.ordinal) }

    fun syncReminder() {
        if (read().reminderEnabled != Reminder.isEnabled(appContext)) {
            Reminder.setEnabled(appContext, read().reminderEnabled)
        }
    }

    companion object {
        const val PREFS = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_REMINDER = "reminder_enabled"
        private const val KEY_QUIET_ENABLED = "quiet_enabled"
        private const val KEY_QUIET_START = "quiet_start"
        private const val KEY_QUIET_END = "quiet_end"
        private const val KEY_DEFAULT_MOOD = "default_mood"
        private const val KEY_WEEK_START = "week_start"
    }
}

@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
