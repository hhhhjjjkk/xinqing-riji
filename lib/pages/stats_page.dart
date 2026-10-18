import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models.dart';
import 'records_page.dart' show EmptyState;

/// 统计页：月份切换 + 主色横幅 + 趋势折线 + 分布条 + 心情热力条。
class StatsPage extends StatelessWidget {
  const StatsPage({
    super.key,
    required this.month,
    required this.entries,
    required this.setMonth,
  });

  final DateTime month;
  final List<MoodEntry> entries;
  final ValueChanged<DateTime> setMonth;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // moodEpoch 变化时（自定义心情标签后）立即重算
    final stats = computeMonthStats(entries, month);
    final inMonth = stats.inMonth;
    final days = stats.latestPerDay;
    final accent = scheme.primary;
    final onAccent = scheme.onPrimary;

    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // —— 月份切换 + 主色横幅 ——
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                TextButton(
                    onPressed: () =>
                        setMonth(DateTime(month.year, month.month - 1, 1)),
                    child: const Text('‹ 上月')),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 18, vertical: 6),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(999),
                    color: accent.withValues(alpha: 0.14),
                  ),
                  child: Text('${month.year} 年 ${month.month} 月',
                      style: Theme.of(context)
                          .textTheme
                          .titleLarge
                          ?.copyWith(
                              fontWeight: FontWeight.bold, color: accent)),
                ),
                TextButton(
                    onPressed: () =>
                        setMonth(DateTime(month.year, month.month + 1, 1)),
                    child: const Text('下月 ›')),
              ],
            ),
          ),
          if (days.isEmpty) ...[
            const SizedBox(height: 40),
            const EmptyState(
              title: '本月还没有心情记录',
              subtitle: '按小时记下心情后，这里会展示你的情绪分布',
            ),
            const SizedBox(height: 40),
          ] else ...[
            Builder(builder: (context) {
              final avg =
                  days.map((e) => moodOf(e.moodId).score).fold(0.0, (a, b) => a + b) /
                      days.length;
              // 主要情绪按记录次数取（与分布、横幅同一口径）
              Mood? topMood;
              int? topCount;
              stats.entryCounts.forEach((id, c) {
                if (topCount == null || c > topCount!) {
                  topCount = c;
                  topMood = moodOf(id);
                }
              });

              return Container(
                margin: const EdgeInsets.symmetric(horizontal: 16),
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(24),
                  // 斜向高光：左上更亮、右下略暗
                  gradient: LinearGradient(
                    colors: [
                      Color.lerp(accent, Colors.white, 0.22)!,
                      accent,
                      Color.lerp(accent, Colors.black, 0.14)!,
                    ],
                  ),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '${topMood?.emoji ?? '•'} 主要情绪是${topMood?.label ?? '—'}',
                            style: TextStyle(
                                color: onAccent,
                                fontWeight: FontWeight.bold,
                                fontSize: 18),
                          ),
                          const SizedBox(height: 6),
                          Text('共记录 ${inMonth.length} 条 · ${days.length} 天',
                              style: TextStyle(
                                  color: onAccent.withValues(alpha: 0.85),
                                  fontSize: 13)),
                          Text(
                              '平均 ${avg.isNaN ? '0.0' : avg.toStringAsFixed(1)} / 5 分',
                              style: TextStyle(
                                  color: onAccent.withValues(alpha: 0.85),
                                  fontSize: 13)),
                        ],
                      ),
                    ),
                    Text(topMood?.emoji ?? '•',
                        style: const TextStyle(fontSize: 52)),
                  ],
                ),
              );
            }),
            const SizedBox(height: 20),
            // —— 情绪趋势（按日均分的时间曲线） ——
            if (stats.dailyScores.length >= 2) ...[
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 16),
                child: Text('情绪趋势',
                    style:
                        TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              ),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 16),
                child: Text('按日平均分，越高代表状态越好',
                    style: TextStyle(fontSize: 12)),
              ),
              const SizedBox(height: 12),
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 16),
                height: 150,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(20),
                  color: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
                ),
                child: CustomPaint(
                  size: Size.infinite,
                  painter: MoodTrendChartPainter(
                    points: stats.dailyScores,
                    accent: accent,
                  ),
                ),
              ),
              const SizedBox(height: 20),
            ],
            // —— 情绪分布（按实际记录次数；百分比最大余数法凑整 100） ——
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Text('情绪分布',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            ),
            const SizedBox(height: 12),
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 16),
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(20),
                color: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
              ),
              child: Column(
                children: [
                  for (final m in moods) ...[
                    Builder(builder: (context) {
                      final count = stats.entryCounts[m.id] ?? 0;
                      final pct = integerPercent(stats.entryCounts,
                              stats.entryTotal)[m.id] ??
                          0;
                      return Row(
                        children: [
                          SizedBox(
                              width: 34,
                              child: Text(m.emoji,
                                  style: const TextStyle(fontSize: 22))),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(m.label,
                                        style: const TextStyle(
                                            fontWeight: FontWeight.w500)),
                                    const SizedBox(width: 6),
                                    Text('$pct%',
                                        style: TextStyle(
                                            color: scheme.primary,
                                            fontWeight: FontWeight.bold)),
                                  ],
                                ),
                                const SizedBox(height: 4),
                                // 0% 完全不画，避免进度条残留
                                Stack(children: [
                                  Container(
                                    height: 8,
                                    decoration: BoxDecoration(
                                      color: scheme.surfaceContainerHighest
                                          .withValues(alpha: 0.55),
                                      borderRadius: BorderRadius.circular(999),
                                    ),
                                  ),
                                  if (count > 0 && pct > 0)
                                    FractionallySizedBox(
                                      widthFactor: (pct / 100).clamp(0.0, 1.0),
                                      child: Container(
                                        height: 8,
                                        decoration: BoxDecoration(
                                          color: m.color,
                                          borderRadius:
                                              BorderRadius.circular(999),
                                        ),
                                      ),
                                    ),
                                ]),
                              ],
                            ),
                          ),
                          SizedBox(
                            width: 44,
                            child: Text('$count 次',
                                textAlign: TextAlign.right,
                                style: TextStyle(
                                    fontSize: 12,
                                    color: scheme.onSurfaceVariant)),
                          ),
                        ],
                      );
                    }),
                    const SizedBox(height: 14),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 22),
            // —— 心情热力条 ——
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Text('本月心情热力条',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            ),
            const SizedBox(height: 10),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (var day = 1;
                      day <= DateTime(month.year, month.month + 1, 0).day;
                      day++)
                    Builder(builder: (context) {
                      final d = DateTime(month.year, month.month, day);
                      final e = stats.latestByDayMap[_fmtKey(d)];
                      final mood = e == null ? null : moodOf(e.moodId);
                      final now = DateTime.now();
                      final isToday = d.year == now.year &&
                          d.month == now.month &&
                          d.day == now.day;
                      return Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(12),
                          color: mood?.color ??
                              scheme.surfaceContainerHighest.withValues(alpha: 0.4),
                          border: isToday
                              ? Border.all(color: accent, width: 2)
                              : null,
                        ),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text(day.toString(),
                                style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                    color: mood != null &&
                                            mood.color.computeLuminance() < 0.55
                                        ? Colors.white
                                        : scheme.onSurfaceVariant
                                            .withValues(alpha: 0.55))),
                            if (mood != null)
                              Text(mood.emoji,
                                  style: const TextStyle(fontSize: 10)),
                          ],
                        ),
                      );
                    }),
                ],
              ),
            ),
            const SizedBox(height: 30),
          ],
        ],
      ),
    );
  }

  static String _fmtKey(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}

/// 情绪趋势图：按日均分绘制折线 + 渐变填充（纯 CustomPainter，不引入图表库）。
class MoodTrendChartPainter extends CustomPainter {
  MoodTrendChartPainter({required this.points, required this.accent});

  final List<MapEntry<DateTime, double>> points;
  final Color accent;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.length < 2) return;
    final w = size.width;
    final h = size.height;
    double yOf(double score) {
      final t = ((score - 1) / 4).clamp(0.0, 1.0);
      return h - t * h;
    }

    // 参考线：5 / 3 / 1 分
    final refPaint = Paint()
      ..color = accent.withValues(alpha: 0.10)
      ..strokeWidth = 1;
    for (final lv in [5.0, 3.0, 1.0]) {
      final y = yOf(lv);
      canvas.drawLine(Offset(0, y), Offset(w, y), refPaint);
    }

    final stepX = w / (points.length - 1);
    final pts = [
      for (var i = 0; i < points.length; i++)
        Offset(i * stepX, yOf(points[i].value))
    ];

    // 渐变填充
    final fillPath = Path()
      ..moveTo(pts.first.dx, h);
    for (final p in pts.skip(1)) {
      fillPath.lineTo(p.dx, p.dy);
    }
    fillPath.lineTo(pts.last.dx, h);
    fillPath.close();
    canvas.drawPath(
        fillPath,
        Paint()
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [accent.withValues(alpha: 0.28), Colors.transparent],
          ).createShader(Offset.zero & size));

    // 折线
    final linePath = Path()..moveTo(pts.first.dx, pts.first.dy);
    for (final p in pts.skip(1)) {
      linePath.lineTo(p.dx, p.dy);
    }
    canvas.drawPath(
        linePath,
        Paint()
          ..color = accent
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.5
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round);

    // 数据点（点多时只画首尾与峰值）
    const dotR = 3.0;
    final showAll = points.length <= 16;
    final maxScore = points.map((e) => e.value).reduce(math.max);
    for (var i = 0; i < pts.length; i++) {
      final isPeak = points[i].value >= maxScore - 0.01;
      if (showAll || i == 0 || i == pts.length - 1 || isPeak) {
        canvas.drawCircle(pts[i], dotR, Paint()..color = accent);
        canvas.drawCircle(
            pts[i], dotR * 0.45, Paint()..color = Colors.white.withValues(alpha: 0.9));
      }
    }
  }

  @override
  bool shouldRepaint(covariant MoodTrendChartPainter old) =>
      old.accent != accent ||
      old.points.length != points.length ||
      old.points.toString() != points.toString();
}
