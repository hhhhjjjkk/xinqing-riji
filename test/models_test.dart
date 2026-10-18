import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mood_diary/models.dart';

void main() {
  test('moodOfIn：精确命中 / 按分值就近回退 / 空目录占位', () {
    // 精确命中
    final hit = moodOfIn(5, kDefaultMoods, kDefaultMoods);
    expect(hit.label, '开心');

    // id=7 不存在，默认目录中也无 7 → 回退占位（目录非空取第一项）
    final fallback = moodOfIn(7, kDefaultMoods, kDefaultMoods);
    expect(fallback, same(kDefaultMoods.first));

    // 自定义目录里分值 2 与默认 id=2（低落）就近匹配
    final custom = [
      const Mood(id: 10, label: '开心', emoji: '😄', color: Color(0xFFFFB300), score: 5),
      const Mood(id: 11, label: '低落', emoji: '😔', color: Color(0xFF42A5F5), score: 2),
    ];
    expect(moodOfIn(2, custom, kDefaultMoods).id, 11);
  });

  test('integerPercent：最大余数法保证总和为 100', () {
    // 3 等分：33.3% × 3 → 33/33/34
    final p1 = integerPercent({1: 1, 2: 1, 3: 1}, 3);
    expect(p1.values.fold(0, (a, b) => a + b), 100);

    final p2 = integerPercent({1: 7, 2: 3}, 10);
    expect(p2[1], 70);
    expect(p2[2], 30);

    // total=0：全部为 0
    final p3 = integerPercent({1: 5}, 0);
    expect(p3[1], 0);
  });

  test('computeMonthStats：每天取最新一条的唯一口径', () {
    moodCatalog = MoodCatalog(kDefaultMoods);
    final entries = [
      const MoodEntry(id: 1, date: '2026-01-01', hour: 8, moodId: 2, updatedAt: 0),
      const MoodEntry(id: 2, date: '2026-01-01', hour: 20, moodId: 5, updatedAt: 0),
      const MoodEntry(id: 3, date: '2026-02-01', hour: 9, moodId: 5, updatedAt: 0),
    ];
    final stats = computeMonthStats(entries, DateTime(2026, 1, 1));
    expect(stats.inMonth.length, 2);
    expect(stats.latestPerDay.length, 1);
    expect(stats.latestPerDay.first.id, 2); // 当天 20 点那条
    expect(stats.entryTotal, 2);
    expect(stats.dayTotal, 1);
  });

  test('QuietHours 跨天区间', () {
    expect(QuietHours.isQuiet(22, 8, 23), isTrue);
    expect(QuietHours.isQuiet(22, 8, 7), isTrue);
    expect(QuietHours.isQuiet(22, 8, 12), isFalse);
    expect(QuietHours.isQuiet(12, 14, 13), isTrue);
  });
}
