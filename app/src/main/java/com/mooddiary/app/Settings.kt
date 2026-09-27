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

/** 主题强调色预设 */
enum class AccentColor {
    AMBER, BLUE, GREEN, PURPLE, PINK,
    CYAN, RED, INDIGO, TEAL, ORANGE, SLATE;

    fun label() = when (this) {
        AMBER -> "琥珀"; BLUE -> "静蓝"; GREEN -> "青绿"
        PURPLE -> "紫罗兰"; PINK -> "樱粉"
        CYAN -> "青碧"; RED -> "赤霞"; INDIGO -> "靛青"
        TEAL -> "松绿"; ORANGE -> "暖橙"; SLATE -> "雾灰"
    }
}

/** 光效强度 */
enum class GlowLevel { SOFT, NORMAL, STRONG;
    fun label() = when (this) { SOFT -> "柔和"; NORMAL -> "标准"; STRONG -> "明亮" }
    fun factor() = when (this) { SOFT -> 0.65f; NORMAL -> 1f; STRONG -> 1.45f }
}

/** 卡片圆角 */
enum class CornerLevel { SMALL, NORMAL, LARGE;
    fun label() = when (this) { SMALL -> "小"; NORMAL -> "标准"; LARGE -> "大" }
    fun dp() = when (this) { SMALL -> 12; NORMAL -> 22; LARGE -> 30 }
}

/** 界面字号 */
enum class FontLevel { SMALL, NORMAL, LARGE;
    fun label() = when (this) { SMALL -> "小"; NORMAL -> "标准"; LARGE -> "大" }
    fun scale() = when (this) { SMALL -> 0.88f; NORMAL -> 1f; LARGE -> 1.18f }
}

/** 启动默认页 */
enum class StartTab { CALENDAR, RECORDS, STATS, SETTINGS;

    fun label() = when (this) {
        CALENDAR -> "日历"; RECORDS -> "记录"; STATS -> "统计"; SETTINGS -> "设置"
    }
    fun index() = ordinal
}

/** 点击日历某天时的行为 */
enum class CalendarTapAction { OPEN_DAY_BOARD, QUICK_LOG_NOW }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: AccentColor = AccentColor.AMBER,
    val useDynamicColor: Boolean = false,   // Android 12+ 从壁纸取色
    val startTab: StartTab = StartTab.CALENDAR,
    val calendarShowNote: Boolean = false,  // 日历格子显示备注摘要
    val calendarTapAction: CalendarTapAction = CalendarTapAction.OPEN_DAY_BOARD,
    /** 长按按钮时的沉浸光感（发光并照亮邻近元素的轮廓） */
    val immersiveGlow: Boolean = true,
    val reminderEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStart: Int = 22,
    val quietEnd: Int = 8,
    val defaultMoodId: Int = 5,
    val weekStart: WeekStart = WeekStart.SUNDAY,
    /** 光效强度 */
    val glowLevel: GlowLevel = GlowLevel.NORMAL,
    /** 卡片圆角大小 */
    val cornerLevel: CornerLevel = CornerLevel.NORMAL,
    /** 界面字号 */
    val fontLevel: FontLevel = FontLevel.NORMAL,
    /** 深色模式下使用纯黑背景（OLED 更省电） */
    val pureBlack: Boolean = false,
    /** 日历格子上显示心情表情 */
    val calendarShowEmoji: Boolean = true
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
        accentColor = AccentColor.entries.getOrElse(prefs.getInt(KEY_ACCENT, 0)) { AccentColor.AMBER },
        useDynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
        startTab = StartTab.entries.getOrElse(prefs.getInt(KEY_START_TAB, 0)) { StartTab.CALENDAR },
        calendarShowNote = prefs.getBoolean(KEY_CAL_NOTE, false),
        calendarTapAction = CalendarTapAction.entries
            .getOrElse(prefs.getInt(KEY_CAL_TAP, 0)) { CalendarTapAction.OPEN_DAY_BOARD },
        immersiveGlow = prefs.getBoolean(KEY_GLOW, true),
        glowLevel = GlowLevel.entries.getOrElse(prefs.getInt(KEY_GLOW_LEVEL, 1)) { GlowLevel.NORMAL },
        cornerLevel = CornerLevel.entries.getOrElse(prefs.getInt(KEY_CORNER, 1)) { CornerLevel.NORMAL },
        fontLevel = FontLevel.entries.getOrElse(prefs.getInt(KEY_FONT, 1)) { FontLevel.NORMAL },
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
        calendarShowEmoji = prefs.getBoolean(KEY_CAL_EMOJI, true),
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
    fun setAccentColor(color: AccentColor) = commit { putInt(KEY_ACCENT, color.ordinal) }
    fun setUseDynamicColor(enabled: Boolean) = commit { putBoolean(KEY_DYNAMIC, enabled) }
    fun setStartTab(tab: StartTab) = commit { putInt(KEY_START_TAB, tab.ordinal) }
    fun setCalendarShowNote(enabled: Boolean) = commit { putBoolean(KEY_CAL_NOTE, enabled) }
    fun setCalendarTapAction(action: CalendarTapAction) = commit { putInt(KEY_CAL_TAP, action.ordinal) }
    fun setImmersiveGlow(enabled: Boolean) = commit { putBoolean(KEY_GLOW, enabled) }
    fun setGlowLevel(level: GlowLevel) = commit { putInt(KEY_GLOW_LEVEL, level.ordinal) }
    fun setCornerLevel(level: CornerLevel) = commit { putInt(KEY_CORNER, level.ordinal) }
    fun setFontLevel(level: FontLevel) = commit { putInt(KEY_FONT, level.ordinal) }
    fun setPureBlack(enabled: Boolean) = commit { putBoolean(KEY_PURE_BLACK, enabled) }
    fun setCalendarShowEmoji(enabled: Boolean) = commit { putBoolean(KEY_CAL_EMOJI, enabled) }
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
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_START_TAB = "start_tab"
        private const val KEY_CAL_NOTE = "calendar_show_note"
        private const val KEY_CAL_TAP = "calendar_tap_action"
        private const val KEY_GLOW = "immersive_glow"
        private const val KEY_GLOW_LEVEL = "glow_level"
        private const val KEY_CORNER = "corner_level"
        private const val KEY_FONT = "font_level"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_CAL_EMOJI = "calendar_show_emoji"
    }
}

@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
