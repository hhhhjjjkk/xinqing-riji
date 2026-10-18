import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';

/// 按索引取枚举值，越界回退默认（Kotlin List.getOrElse 的等价物）
T enumAt<T>(List<T> values, int? index, T def) =>
    (index != null && index >= 0 && index < values.length) ? values[index] : def;

/// 设置的读写入口（键名与原 Kotlin 版保持一致，便于将来互通）。
/// 用 ChangeNotifier 承载：任何地方改了偏好都能自动通知所有观察者。
class SettingsStore extends ChangeNotifier {
  SettingsStore(this._prefs) {
    _settings = _read();
  }

  final SharedPreferences _prefs;
  late AppSettings _settings;

  AppSettings get settings => _settings;

  AppSettings _read() => AppSettings(
        themeMode:
            enumAt(ThemeModeSetting.values, _prefs.getInt('theme_mode') ?? 0, ThemeModeSetting.system),
        accentColor:
            enumAt(AccentColor.values, _prefs.getInt('accent_color') ?? 0, AccentColor.amber),
        useDynamicColor: _prefs.getBool('dynamic_color') ?? false,
        startTab: enumAt(StartTab.values, _prefs.getInt('start_tab') ?? 0, StartTab.calendar),
        calendarShowNote: _prefs.getBool('calendar_show_note') ?? false,
        calendarTapAction: enumAt(CalendarTapAction.values, _prefs.getInt('calendar_tap_action') ?? 0, CalendarTapAction.openDayBoard),
        reminderEnabled: _prefs.getBool('reminder_enabled') ?? false,
        quietHoursEnabled: _prefs.getBool('quiet_enabled') ?? false,
        quietStart: _prefs.getInt('quiet_start') ?? 22,
        quietEnd: _prefs.getInt('quiet_end') ?? 8,
        defaultMoodId: _prefs.getInt('default_mood') ?? 5,
        weekStart: enumAt(WeekStart.values, _prefs.getInt('week_start') ?? 0, WeekStart.sunday),
        notifyMoodIds: (_prefs.getString('notify_mood_ids') ?? '')
            .split(',')
            .map((s) => int.tryParse(s.trim()))
            .whereType<int>()
            .take(AppSettings.notifyMoodMax)
            .toList(),
        glowLevel: enumAt(GlowLevel.values, _prefs.getInt('glow_level') ?? 1, GlowLevel.normal),
        cornerLevel: enumAt(CornerLevel.values, _prefs.getInt('corner_level') ?? 1, CornerLevel.normal),
        fontLevel: enumAt(FontLevel.values, _prefs.getInt('font_level') ?? 1, FontLevel.normal),
        pureBlack: _prefs.getBool('pure_black') ?? false,
        calendarShowEmoji: _prefs.getBool('calendar_show_emoji') ?? true,
      );

  void _commit(void Function() block) {
    block();
    _settings = _read();
    notifyListeners();
  }

  void setThemeMode(ThemeModeSetting mode) => _commit(() => _prefs.setInt('theme_mode', mode.index));
  void setAccentColor(AccentColor color) => _commit(() => _prefs.setInt('accent_color', color.index));
  void setUseDynamicColor(bool enabled) => _commit(() => _prefs.setBool('dynamic_color', enabled));
  void setStartTab(StartTab tab) => _commit(() => _prefs.setInt('start_tab', tab.index));
  void setCalendarShowNote(bool enabled) => _commit(() => _prefs.setBool('calendar_show_note', enabled));
  void setCalendarTapAction(CalendarTapAction action) =>
      _commit(() => _prefs.setInt('calendar_tap_action', action.index));
  void setGlowLevel(GlowLevel level) => _commit(() => _prefs.setInt('glow_level', level.index));
  void setCornerLevel(CornerLevel level) => _commit(() => _prefs.setInt('corner_level', level.index));
  void setFontLevel(FontLevel level) => _commit(() => _prefs.setInt('font_level', level.index));
  void setPureBlack(bool enabled) => _commit(() => _prefs.setBool('pure_black', enabled));
  void setCalendarShowEmoji(bool enabled) => _commit(() => _prefs.setBool('calendar_show_emoji', enabled));
  void setQuietHoursEnabled(bool enabled) => _commit(() => _prefs.setBool('quiet_enabled', enabled));
  void setDefaultMood(int moodId) => _commit(() => _prefs.setInt('default_mood', moodId));
  void setWeekStart(WeekStart ws) => _commit(() => _prefs.setInt('week_start', ws.index));

  void setReminderEnabled(bool enabled) {
    _commit(() => _prefs.setBool('reminder_enabled', enabled));
    // 调度同步由 main 层监听 settings.reminderEnabled 完成
  }

  void setQuietRange(int start, int end) {
    assert(start >= 0 && start <= 23 && end >= 0 && end <= 23);
    _commit(() {
      _prefs.setInt('quiet_start', start);
      _prefs.setInt('quiet_end', end);
    });
  }

  /// 设置通知里显示哪些心情（按传入顺序）；传空列表表示恢复「自动」
  void setNotifyMoodIds(List<int> ids) =>
      _commit(() => _prefs.setString('notify_mood_ids', ids.join(',')));
}

/// 心情目录的持久化。
/// 极简文本格式，每条一行，字段 \u0001 分隔：id,label,emoji,colorArgb,score
class MoodCatalogStorage {
  static const _key = 'mood_catalog_v1';
  static const _sep = '\u0001';

  /// 读取自定义目录；未自定义过返回 null（由调用方沿用内置默认）
  static List<Mood>? load(SharedPreferences prefs) {
    final raw = prefs.getString(_key);
    if (raw == null || raw.trim().isEmpty) return null;
    final list = <Mood>[];
    for (final line in raw.split('\n')) {
      final f = line.split(_sep);
      if (f.length < 5) continue;
      final id = int.tryParse(f[0]);
      final score = int.tryParse(f[4]);
      if (id == null || score == null) continue;
      // 颜色解析失败时退回该心情的分值色，而不是丢掉这一行
      final color = _decodeColor(f[3]) ?? _scoreColor(score);
      list.add(Mood(id: id, label: f[1], emoji: f[2], color: color, score: score));
    }
    return list.isEmpty ? null : list;
  }

  static void save(SharedPreferences prefs, List<Mood> list) {
    prefs.setString(
        _key,
        list
            .map((m) => [
                  m.id.toString(),
                  m.label,
                  m.emoji,
                  // 存 ARGB 的 32 位十进制（可能为负），与 Kotlin 版格式互通
                  (m.color.toARGB32()).toString(),
                  m.score.toString(),
                ].join(_sep))
            .join('\n'));
  }

  static void reset(SharedPreferences prefs) => prefs.remove(_key);

  /// 新格式：32 位 ARGB 十进制（可能为负）。
  /// 旧格式（Kotlin 版）：Color.value 的 ULong 文本，可能溢出 signed64；
  /// 用 BigInt 兜住，再取高 32 位还原 ARGB。
  static Color? _decodeColor(String raw) {
    var v = int.tryParse(raw);
    if (v != null) return Color(v & 0xFFFFFFFF);
    final big = BigInt.tryParse(raw);
    if (big == null) return null;
    final argb = (big >> 32).toUnsigned(32).toInt();
    if (argb == 0 && big != BigInt.zero) return null;
    return Color(argb);
  }

  static Color _scoreColor(int score) => switch (score.clamp(1, 5)) {
        5 => const Color(0xFFFFB300),
        4 => const Color(0xFF43A047),
        3 => const Color(0xFF78909C),
        2 => const Color(0xFF42A5F5),
        _ => const Color(0xFFEF5350),
      };
}

/// 深色主题判定
bool shouldUseDarkTheme(ThemeModeSetting mode, Brightness platform) =>
    switch (mode) {
      ThemeModeSetting.system => platform == Brightness.dark,
      ThemeModeSetting.light => false,
      ThemeModeSetting.dark => true,
    };
