import 'package:flutter/material.dart';

import '../models.dart';
import '../press_bounce.dart';

/// 记录页：全部心情记录的时间倒序列表。
class RecordsPage extends StatelessWidget {
  const RecordsPage({super.key, required this.entries, required this.open});

  final List<MoodEntry> entries;
  final ValueChanged<MoodEntry> open;

  @override
  Widget build(BuildContext context) {
    if (entries.isEmpty) {
      return const EmptyState(
        title: '还没有记录',
        subtitle: '点击右下角 +，或从日历选一天按小时记录',
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: entries.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (context, i) {
        final e = entries[i];
        final m = moodOf(e.moodId);
        final scheme = Theme.of(context).colorScheme;
        return PressBounce(
          pressedScale: 0.97,
          child: GestureDetector(
            onTap: () => open(e),
            child: Card(
              elevation: 0,
              color: scheme.surfaceContainerLow,
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16)),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    // 左侧心情色竖条：一眼识别心情类别
                    Container(
                      width: 4,
                      height: 40,
                      decoration: BoxDecoration(
                        color: m.color,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Text(m.emoji, style: const TextStyle(fontSize: 30)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '${e.date.substring(5)} ${e.hour.toString().padLeft(2, '0')}:00',
                            style: const TextStyle(fontWeight: FontWeight.bold),
                          ),
                          Text(
                            e.note.isEmpty ? m.label : e.note,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style:
                                TextStyle(color: scheme.onSurfaceVariant),
                          ),
                        ],
                      ),
                    ),
                    Icon(Icons.edit, color: m.color, size: 20),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// 空状态：图标置于强调色柔和圆底中
class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.title, required this.subtitle});

  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      children: [
        SizedBox(height: 70),
        Container(
          width: 96,
          height: 96,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: scheme.primary.withValues(alpha: 0.12),
          ),
          child: Center(
            child: Text('✦',
                style: TextStyle(fontSize: 42, color: scheme.primary)),
          ),
        ),
        const SizedBox(height: 16),
        Text(title,
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Text(
            subtitle,
            textAlign: TextAlign.center,
            style: TextStyle(color: scheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}
