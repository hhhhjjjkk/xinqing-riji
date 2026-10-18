import 'package:flutter/material.dart';

import '../models.dart';
import '../press_bounce.dart';

/// 日历页：月视图网格 + 心情色块 + 图例。
class CalendarPage extends StatelessWidget {
  const CalendarPage({
    super.key,
    required this.month,
    required this.entries,
    required this.weekStart,
    required this.showNote,
    required this.showEmoji,
    required this.setMonth,
    required this.open,
  });

  final DateTime month;
  final List<MoodEntry> entries;
  final WeekStart weekStart;
  final bool showNote;
  final bool showEmoji;
  final ValueChanged<DateTime> setMonth;
  final ValueChanged<DateTime> open;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final latestByDay = <String, MoodEntry>{};
    for (final e in entries) {
      final prev = latestByDay[e.date];
      if (prev == null || e.hour > prev.hour) latestByDay[e.date] = e;
    }

    final weekLabels = weekStart == WeekStart.sunday
        ? const ['日', '一', '二', '三', '四', '五', '六']
        : const ['一', '二', '三', '四', '五', '六', '日'];

    final first = DateTime(month.year, month.month, 1);
    final daysInMonth = DateTime(month.year, month.month + 1, 0).day;
    // 周日起始：weekday 周日=1 → 列号 (value - 1) % 7；
    // 周一起始：周一=0 列 … 周日=6 列
    final paddingDays = weekStart == WeekStart.sunday
        ? first.weekday % 7
        : first.weekday - 1;
    final total = paddingDays + daysInMonth;
    final rows = (total + 6) ~/ 7;

    final now = DateTime.now();

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              TextButton(
                  onPressed: () => setMonth(
                      DateTime(month.year, month.month - 1, 1)),
                  child: const Text('‹ 上月')),
              Text('${month.year}年${month.month}月',
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.bold)),
              TextButton(
                  onPressed: () =>
                      setMonth(DateTime(month.year, month.month + 1, 1)),
                  child: const Text('下月 ›')),
            ],
          ),
          Center(
            child: TextButton(
              onPressed: () {
                final t = DateTime.now();
                setMonth(DateTime(t.year, t.month, 1));
              },
              child: const Text('回到今天'),
            ),
          ),
          Row(
            children: [
              for (final l in weekLabels)
                Expanded(
                  child: Text(l,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          color: scheme.primary,
                          fontWeight: FontWeight.bold)),
                ),
            ],
          ),
          const SizedBox(height: 4),
          for (var row = 0; row < rows; row++)
            Row(
              children: [
                for (var col = 0; col < 7; col++)
                  Expanded(
                    child: AspectRatio(
                      aspectRatio: 0.82,
                      child: Padding(
                        padding: const EdgeInsets.all(2),
                        child: Builder(builder: (context) {
                          final day = row * 7 + col - paddingDays + 1;
                          if (day < 1 || day > daysInMonth) {
                            return const SizedBox.shrink();
                          }
                          final d = DateTime(month.year, month.month, day);
                          return CalendarCell(
                            date: d,
                            entry: latestByDay[_fmtKey(d)],
                            today: _isSameDay(d, now),
                            showNote: showNote,
                            showEmoji: showEmoji,
                            onTap: () => open(d),
                          );
                        }),
                      ),
                    ),
                  ),
              ],
            ),
          const SizedBox(height: 10),
          // 提示与图例：淡底卡片
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              color: scheme.surfaceContainerHighest.withValues(alpha: 0.35),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('点击某天 → 按小时记录心情',
                    style: TextStyle(
                        fontSize: 12, color: scheme.onSurfaceVariant)),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  children: [
                    for (final m in moods)
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            width: 10,
                            height: 10,
                            decoration:
                                BoxDecoration(color: m.color, shape: BoxShape.circle),
                          ),
                          const SizedBox(width: 4),
                          Text('${m.emoji}${m.label}',
                              style: TextStyle(
                                  fontSize: 12,
                                  color: scheme.onSurfaceVariant)),
                        ],
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static String _fmtKey(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static bool _isSameDay(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month && a.day == b.day;
}

/// 单个日历格子
class CalendarCell extends StatelessWidget {
  const CalendarCell({
    super.key,
    required this.date,
    required this.entry,
    required this.today,
    required this.showNote,
    required this.showEmoji,
    required this.onTap,
  });

  final DateTime date;
  final MoodEntry? entry;
  final bool today;
  final bool showNote;
  final bool showEmoji;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final mood = entry == null ? null : moodOf(entry!.moodId);
    final bg = mood?.color ?? scheme.surfaceContainerHighest;
    final darkBg = bg.computeLuminance() < 0.55;
    final textColor = (mood != null && darkBg)
        ? Colors.white
        : scheme.onSurface;
    final isOutlinedToday = today;

    return PressBounce(
      pressedScale: 0.90,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(12),
            color: mood == null
                ? scheme.surfaceContainerHighest.withValues(alpha: 0.45)
                : bg,
            border: isOutlinedToday
                ? Border.all(color: scheme.primary, width: 2)
                : null,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                date.day.toString(),
                style: TextStyle(
                  color: (mood == null && !today)
                      ? scheme.onSurfaceVariant
                      : textColor,
                  fontWeight: today ? FontWeight.bold : FontWeight.normal,
                ),
              ),
              if (mood != null && showEmoji)
                Text(mood.emoji, style: const TextStyle(fontSize: 15)),
              if (today) ...[
                const SizedBox(height: 2),
                Container(
                  width: 5,
                  height: 5,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: mood != null ? textColor : scheme.primary,
                  ),
                ),
              ],
              if (showNote && entry != null && entry!.note.trim().isNotEmpty)
                Text(
                  entry!.note.trim(),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 6,
                      color: textColor.withValues(alpha: 0.85)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
