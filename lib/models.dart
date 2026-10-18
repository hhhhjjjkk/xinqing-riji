import 'package:flutter/material.dart';

/// 备注长度上限，防止超长文本整段进内存与数据库
const int kMaxNoteLength = 500;

/// 单条心情记录（与原 Room 版 mood_entries 表结构一一对应）
class MoodEntry {
  const MoodEntry({
    required this.id,
    required this.date,
    required this.hour,
    required this.moodId,
    this.note = '',
    required this.updatedAt,
  });

  final int id;
  /// yyyy-MM-dd
  final String date;
  /// 0..23
  final int hour;
  final int moodId;
  final String note;
  final int updatedAt;

  MoodEntry copyWith({int? moodId}) => MoodEntry(
        id: id,
        date: date,
        hour: hour,
        moodId: moodId ?? this.moodId,
        note: note,
        updatedAt: updatedAt,
      );

  Map<String, Object?> toMap() => {
        'id': id,
        'date': date,
        'hour': hour,
        'moodId': moodId,
        'note': note,
        'updatedAt': updatedAt,
      };

  static MoodEntry fromMap(Map<String, Object?> m) => MoodEntry(
        id: m['id'] as int,
        date: m['date'] as String,
        hour: m['hour'] as int,
        moodId: m['moodId'] as int,
        note: (m['note'] as String?) ?? '',
        updatedAt: (m['updatedAt'] as int?) ?? 0,
      );
}

/// 保存结果：目标 (日期, 小时) 已被另一条记录占用时返回 [conflict]，
/// 由 UI 明确询问用户是否覆盖——绝不静默删掉已有记录。
sealed class SaveOutcome {
  const SaveOutcome();
}

class Saved extends SaveOutcome {
  const Saved();
}

class Conflict extends SaveOutcome {
  const Conflict(this.existing);
  final MoodEntry existing;
}

class Mood {
  const Mood({
    required this.id,
    required this.label,
    required this.emoji,
    required this.color,
    required this.score,
  });

  final int id;
  final String label;
  final String emoji;
  final Color color;
  /// 1..5，用于趋势统计
  final int score;

  Mood copyWith({String? label, String? emoji, Color? color, int? score}) =>
      Mood(
        id: id,
        label: label ?? this.label,
        emoji: emoji ?? this.emoji,
        color: color ?? this.color,
        score: score ?? this.score,
      );
}

/// 内置默认心情（用户未自定义时使用）
final List<Mood> kDefaultMoods = [
  Mood(id: 5, label: '开心', emoji: '😄', color: Color(0xFFFFB300), score: 5),
  Mood(id: 4, label: '平静', emoji: '😌', color: Color(0xFF43A047), score: 4),
  Mood(id: 3, label: '一般', emoji: '😐', color: Color(0xFF78909C), score: 3),
  Mood(id: 2, label: '低落', emoji: '😔', color: Color(0xFF42A5F5), score: 2),
  Mood(id: 1, label: '生气', emoji: '😡', color: Color(0xFFEF5350), score: 1),
];

/// 按 id 取心情的纯函数：历史记录可能引用已被删除/重建的心情 id，
/// 按「默认目录中的分值就近匹配」回退；目录为空再回退到占位项，绝不抛异常。
Mood moodOfIn(int id, List<Mood> catalog, List<Mood> defaults) {
  for (final m in catalog) {
    if (m.id == id) return m;
  }
  final original = defaults.where((m) => m.id == id).toList();
  if (original.isNotEmpty) {
    final score = original.first.score;
    Mood? best;
    int bestDist = 1 << 30;
    for (final m in catalog) {
      final d = (m.score - score).abs();
      if (d < bestDist) {
        bestDist = d;
        best = m;
      }
    }
    if (best != null) return best;
  }
  if (catalog.isNotEmpty) return catalog.first;
  return Mood(id: 0, label: '未知', emoji: '•', color: Colors.grey, score: 3);
}

/// 生成一个未被占用的心情 id
int nextMoodIdOf(List<Mood> moods) =>
    moods.isEmpty ? 1 : moods.map((m) => m.id).reduce((a, b) => a > b ? a : b) + 1;

// ───────────────────────── 设置模型 ─────────────────────────

enum ThemeModeSetting { system, light, dark }

enum WeekStart { sunday, monday }

/// 主题强调色预设
enum AccentColor {
  amber('琥珀'),
  blue('静蓝'),
  green('青绿'),
  purple('紫罗兰'),
  pink('樱粉'),
  cyan('青碧'),
  red('赤霞'),
  indigo('靛青'),
  teal('松绿'),
  orange('暖橙'),
  slate('雾灰');

  const AccentColor(this.label);
  final String label;
}

enum GlowLevel { soft, normal, strong }

extension GlowLevelX on GlowLevel {
  String get label => switch (this) {
        GlowLevel.soft => '柔和',
        GlowLevel.normal => '标准',
        GlowLevel.strong => '明亮',
      };
  double get factor => switch (this) {
        GlowLevel.soft => 0.65,
        GlowLevel.normal => 1.0,
        GlowLevel.strong => 1.45,
      };
}

enum CornerLevel { small, normal, large }

extension CornerLevelX on CornerLevel {
  String get label => switch (this) {
        CornerLevel.small => '小',
        CornerLevel.normal => '标准',
        CornerLevel.large => '大',
      };
  double get dp => switch (this) {
        CornerLevel.small => 12,
        CornerLevel.normal => 22,
        CornerLevel.large => 30,
      };
}

enum FontLevel { small, normal, large }

extension FontLevelX on FontLevel {
  String get label => switch (this) {
        FontLevel.small => '小',
        FontLevel.normal => '标准',
        FontLevel.large => '大',
      };
  double get scale => switch (this) {
        FontLevel.small => 0.88,
        FontLevel.normal => 1.0,
        FontLevel.large => 1.18,
      };
}

enum StartTab {
  calendar('日历'),
  records('记录'),
  stats('统计'),
  settings('设置');

  const StartTab(this.label);
  final String label;
  int get pos => values.indexOf(this);
}

enum CalendarTapAction { openDayBoard, quickLogNow }

class AppSettings {
  const AppSettings({
    this.themeMode = ThemeModeSetting.system,
    this.accentColor = AccentColor.amber,
    this.useDynamicColor = false,
    this.startTab = StartTab.calendar,
    this.calendarShowNote = false,
    this.calendarTapAction = CalendarTapAction.openDayBoard,
    this.reminderEnabled = false,
    this.quietHoursEnabled = false,
    this.quietStart = 22,
    this.quietEnd = 8,
    this.defaultMoodId = 5,
    this.weekStart = WeekStart.sunday,
    this.notifyMoodIds = const [],
    this.glowLevel = GlowLevel.normal,
    this.cornerLevel = CornerLevel.normal,
    this.fontLevel = FontLevel.normal,
    this.pureBlack = false,
    this.calendarShowEmoji = true,
  });

  final ThemeModeSetting themeMode;
  final AccentColor accentColor;
  final bool useDynamicColor;
  final StartTab startTab;
  final bool calendarShowNote;
  final CalendarTapAction calendarTapAction;
  final bool reminderEnabled;
  final bool quietHoursEnabled;
  final int quietStart;
  final int quietEnd;
  final int defaultMoodId;
  final WeekStart weekStart;
  /// 通知里显示的心情 id（按显示顺序）。为空表示「自动」：取分值最高的若干个。
  final List<int> notifyMoodIds;
  final GlowLevel glowLevel;
  final CornerLevel cornerLevel;
  final FontLevel fontLevel;
  final bool pureBlack;
  final bool calendarShowEmoji;

  /// 通知里最多显示的心情数（折叠通知内容区物理上只放得下一行 5 个）
  static const int notifyMoodMax = 5;

  AppSettings copyWith({
    ThemeModeSetting? themeMode,
    AccentColor? accentColor,
    bool? useDynamicColor,
    StartTab? startTab,
    bool? calendarShowNote,
    CalendarTapAction? calendarTapAction,
    bool? reminderEnabled,
    bool? quietHoursEnabled,
    int? quietStart,
    int? quietEnd,
    int? defaultMoodId,
    WeekStart? weekStart,
    List<int>? notifyMoodIds,
    GlowLevel? glowLevel,
    CornerLevel? cornerLevel,
    FontLevel? fontLevel,
    bool? pureBlack,
    bool? calendarShowEmoji,
  }) =>
      AppSettings(
        themeMode: themeMode ?? this.themeMode,
        accentColor: accentColor ?? this.accentColor,
        useDynamicColor: useDynamicColor ?? this.useDynamicColor,
        startTab: startTab ?? this.startTab,
        calendarShowNote: calendarShowNote ?? this.calendarShowNote,
        calendarTapAction: calendarTapAction ?? this.calendarTapAction,
        reminderEnabled: reminderEnabled ?? this.reminderEnabled,
        quietHoursEnabled: quietHoursEnabled ?? this.quietHoursEnabled,
        quietStart: quietStart ?? this.quietStart,
        quietEnd: quietEnd ?? this.quietEnd,
        defaultMoodId: defaultMoodId ?? this.defaultMoodId,
        weekStart: weekStart ?? this.weekStart,
        notifyMoodIds: notifyMoodIds ?? this.notifyMoodIds,
        glowLevel: glowLevel ?? this.glowLevel,
        cornerLevel: cornerLevel ?? this.cornerLevel,
        fontLevel: fontLevel ?? this.fontLevel,
        pureBlack: pureBlack ?? this.pureBlack,
        calendarShowEmoji: calendarShowEmoji ?? this.calendarShowEmoji,
      );
}

class QuietHours {
  static bool isQuiet(int start, int end, int hour) {
    if (start <= end) return hour >= start && hour < end;
    return hour >= start || hour < end;
  }

  static bool isQuietNow(AppSettings s, [int? hour]) {
    if (!s.quietHoursEnabled) return false;
    final h = hour ?? DateTime.now().hour;
    return isQuiet(s.quietStart, s.quietEnd, h);
  }
}

// ───────────────────────── 当月统计 ─────────────────────────

/// 当月统计结果（与原版口径一致：全页唯一口径 = 每天只取最新一条）
class MonthStats {
  MonthStats({
    required this.inMonth,
    required this.latestPerDay,
    required this.latestByDayMap,
    required this.dailyScores,
    required this.moodCounts,
    required this.entryCounts,
    required this.entryTotal,
    required this.dayTotal,
  });

  final List<MoodEntry> inMonth;
  final List<MoodEntry> latestPerDay;
  final Map<String, MoodEntry> latestByDayMap;
  /// 每天的均分（按日升序），用于趋势图
  final List<MapEntry<DateTime, double>> dailyScores;
  /// 每种心情的天数（每天取最新一条）
  final Map<int, int> moodCounts;
  /// 每种心情的记录次数（当月全部记录）
  final Map<int, int> entryCounts;
  final int entryTotal;
  final int dayTotal;
}

/// 单次遍历统计当月数据（与原版同一口径）
MonthStats computeMonthStats(List<MoodEntry> entries, DateTime month) {
  final inMonth = <MoodEntry>[];
  final latestByDay = <String, MoodEntry>{};
  final entryCounts = <int, int>{};
  for (final e in entries) {
    final d = DateTime.tryParse(e.date);
    if (d == null) continue;
    if (d.year != month.year || d.month != month.month) continue;
    inMonth.add(e);
    entryCounts[e.moodId] = (entryCounts[e.moodId] ?? 0) + 1;
    final prev = latestByDay[e.date];
    if (prev == null || e.hour > prev.hour) latestByDay[e.date] = e;
  }

  final latestList = latestByDay.values.toList()
    ..sort((a, b) => a.date.compareTo(b.date));

  final perDayCounts = <int, int>{};
  for (final e in latestList) {
    perDayCounts[e.moodId] = (perDayCounts[e.moodId] ?? 0) + 1;
  }

  final moodLookup = <int, int>{};
  final daily = latestList.map((e) {
    final d = DateTime.parse(e.date);
    final score = moodLookup.putIfAbsent(
        e.moodId, () => moodOf(e.moodId).score);
    return MapEntry(d, score.toDouble());
  }).toList();

  return MonthStats(
    inMonth: inMonth,
    latestPerDay: latestList,
    latestByDayMap: latestByDay,
    dailyScores: daily,
    moodCounts: perDayCounts,
    entryCounts: entryCounts,
    entryTotal: inMonth.length,
    dayTotal: latestList.length,
  );
}

/// 按最大余数法把计数分配成整数百分比，保证各项之和精确为 100。
Map<int, int> integerPercent(Map<int, int> counts, int total) {
  if (total <= 0) return {for (final k in counts.keys) k: 0};
  final exact = counts.map((k, c) => MapEntry(k, c * 100.0 / total));
  final floors = exact.map((k, v) => MapEntry(k, v.floor()));
  var used = floors.values.fold(0, (a, b) => a + b);
  final remainder = 100 - used;
  if (remainder <= 0) return floors;
  final order = exact.keys.toList()
    ..sort((a, b) => ((exact[b]! - exact[b]!.floor())
            .compareTo(exact[a]! - exact[a]!.floor())));
  final out = Map<int, int>.from(floors);
  for (var i = 0; i < remainder && i < order.length; i++) {
    final k = order[i];
    out[k] = (out[k] ?? 0) + 1;
  }
  return out;
}

// ───────────────────────── 心情目录（可观察） ─────────────────────────

/// 心情目录的全局可观察状态。
/// 页面通过 Provider 读取；moodOf 从当前目录查表（O(1) 索引）。
class MoodCatalog extends ChangeNotifier {
  MoodCatalog(this._moods) : _index = {for (final m in _moods) m.id: m};

  List<Mood> _moods;
  Map<int, Mood> _index;
  int epoch = 0;

  List<Mood> get moods => _moods;

  Mood moodOf(int id) {
    final hit = _index[id];
    if (hit != null) return hit;
    return moodOfIn(id, _moods, kDefaultMoods);
  }

  void apply(List<Mood> next) {
    if (next.isEmpty) return;
    _moods = next;
    _index = {for (final m in next) m.id: m};
    epoch++;
    notifyListeners();
  }

  int nextMoodId() => nextMoodIdOf(_moods);
}

// 全局取心情（页面里大量调用，避免到处传 catalog）
// 通过 main 里初始化的全局引用；测试时可指向内存目录。
late MoodCatalog moodCatalog;

Mood moodOf(int id) => moodCatalog.moodOf(id);

List<Mood> get moods => moodCatalog.moods;
int get moodEpoch => moodCatalog.epoch;
int nextMoodId() => moodCatalog.nextMoodId();
