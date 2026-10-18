import 'package:flutter/material.dart';

import 'models.dart';
import 'press_bounce.dart';

String _fmtDate(DateTime d) =>
    '${d.year}年${d.month.toString().padLeft(2, '0')}月${d.day.toString().padLeft(2, '0')}日';

String _fmtHour(int h) => '${h.toString().padLeft(2, '0')}:00';

/// 记录/编辑心情弹窗。
/// remember 的 key 必须包含日期与小时：连续打开两个无记录的时段时，
/// 输入状态不能残留（与 Kotlin 版同因）。
Future<void> showMoodDialog(
  BuildContext context, {
  required DateTime date,
  required int hour,
  required MoodEntry? entry,
  required int defaultMoodId,
  required Future<void> Function(DateTime, int, int, String) onSave,
  required VoidCallback onDelete,
}) {
  return showDialog(
    context: context,
    builder: (ctx) => _MoodDialog(
      date: date,
      hour: hour,
      entry: entry,
      defaultMoodId: defaultMoodId,
      onSave: onSave,
      onDelete: onDelete,
    ),
  );
}

class _MoodDialog extends StatefulWidget {
  const _MoodDialog({
    required this.date,
    required this.hour,
    required this.entry,
    required this.defaultMoodId,
    required this.onSave,
    required this.onDelete,
  });

  final DateTime date;
  final int hour;
  final MoodEntry? entry;
  final int defaultMoodId;
  final Future<void> Function(DateTime, int, int, String) onSave;
  final VoidCallback onDelete;

  @override
  State<_MoodDialog> createState() => _MoodDialogState();
}

class _MoodDialogState extends State<_MoodDialog> {
  late DateTime _d;
  late int _selected;
  late TextEditingController _note;

  @override
  void initState() {
    super.initState();
    _d = widget.date;
    _selected = widget.entry?.moodId ?? widget.defaultMoodId;
    _note = TextEditingController(text: widget.entry?.note ?? '');
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(widget.entry == null ? '记录心情' : '编辑心情'),
      content: SizedBox(
        width: double.maxFinite,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              OutlinedButton(
                onPressed: () async {
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: _d,
                    firstDate: DateTime(2000),
                    lastDate: DateTime(2100),
                  );
                  if (picked != null) setState(() => _d = picked);
                },
                child: Text('日期：${_fmtDate(_d)}'),
              ),
              const SizedBox(height: 8),
              Text(
                '时间：${_fmtHour(widget.hour)}-${widget.hour.toString().padLeft(2, '0')}:59',
                style: TextStyle(color: scheme.onSurfaceVariant),
              ),
              const SizedBox(height: 10),
              const Text('心情是？'),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final m in moods)
                    PressBounce(
                      pressedScale: 0.88,
                      child: GestureDetector(
                        onTap: () => setState(() => _selected = m.id),
                        child: Container(
                          constraints: const BoxConstraints(minWidth: 60),
                          padding: const EdgeInsets.all(6),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(14),
                            color: _selected == m.id
                                ? m.color.withValues(alpha: 0.22)
                                : null,
                            border: _selected == m.id
                                ? Border.all(color: m.color, width: 2)
                                : null,
                          ),
                          child: Column(
                            children: [
                              Text(m.emoji,
                                  style: TextStyle(
                                      fontSize: _selected == m.id ? 28 : 25)),
                              Text(
                                m.label,
                                style: TextStyle(
                                  fontSize: 10,
                                  color: _selected == m.id
                                      ? m.color
                                      : scheme.onSurfaceVariant,
                                  fontWeight: _selected == m.id
                                      ? FontWeight.bold
                                      : FontWeight.normal,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _note,
                maxLength: kMaxNoteLength,
                minLines: 3,
                decoration: const InputDecoration(
                  labelText: '写点备注（可选）',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        if (widget.entry != null)
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
              widget.onDelete();
            },
            child: const Icon(Icons.delete),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () {
            Navigator.of(context).pop();
            widget.onSave(_d, widget.hour, _selected, _note.text);
          },
          child: const Text('保存'),
        ),
      ],
    );
  }
}

/// 小时选择面板：某一天 24 个小时格子，点一个小时记录那个时刻的心情。
Future<void> showHourMoodSheet(
  BuildContext context, {
  required DateTime date,
  required List<MoodEntry> dayEntries,
  required void Function(int hour, MoodEntry? entry) onPick,
}) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(
          left: 16, right: 16, bottom: MediaQuery.of(ctx).padding.bottom + 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${_fmtDate(date)} 的心情',
              style: Theme.of(ctx)
                  .textTheme
                  .titleLarge
                  ?.copyWith(fontWeight: FontWeight.bold)),
          Text('点一个小时，记录那个时刻的心情',
              style: Theme.of(ctx)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: Theme.of(ctx).colorScheme.onSurfaceVariant)),
          const SizedBox(height: 12),
          SizedBox(
            height: 440,
            child: GridView.count(
              crossAxisCount: 4,
              mainAxisSpacing: 8,
              crossAxisSpacing: 8,
              childAspectRatio: 0.98,
              children: [
                for (var h = 0; h < 24; h++)
                  _HourCell(
                    hour: h,
                    entry:
                        dayEntries.where((e) => e.hour == h).firstOrNull,
                    onPick: onPick,
                  ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _HourCell extends StatelessWidget {
  const _HourCell({required this.hour, required this.entry, required this.onPick});

  final int hour;
  final MoodEntry? entry;
  final void Function(int, MoodEntry?) onPick;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final mood = entry == null ? null : moodOf(entry!.moodId);
    final bg = mood?.color ?? scheme.surfaceContainerHighest;
    final onColor = (mood != null && bg.computeLuminance() < 0.55)
        ? Colors.white
        : scheme.onSurface;
    return PressBounce(
      pressedScale: 0.90,
      child: GestureDetector(
        onTap: () => onPick(hour, entry),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(_fmtHour(hour),
                  style: TextStyle(
                      fontSize: 11,
                      color: mood != null ? onColor : scheme.onSurfaceVariant)),
              const SizedBox(height: 2),
              Text(mood?.emoji ?? '＋', style: const TextStyle(fontSize: 20)),
              Text(mood?.label ?? '未记录',
                  style: TextStyle(
                      fontSize: 10,
                      color: mood != null ? onColor : scheme.onSurfaceVariant)),
            ],
          ),
        ),
      ),
    );
  }
}

/// 目标时段已被占用时的确认弹窗——用户明确选择后才覆盖
Future<void> showConflictDialog(
  BuildContext context, {
  required DateTime date,
  required int hour,
  required MoodEntry conflict,
  required VoidCallback onCancel,
  required VoidCallback onOverwrite,
}) {
  final m = moodOf(conflict.moodId);
  final slot =
      '${date.year}年${date.month.toString().padLeft(2, '0')}月${date.day.toString().padLeft(2, '0')}日 ${_fmtHour(hour)}';
  return showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('该时段已有记录'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('$slot 已经记过一条心情：'),
          const SizedBox(height: 8),
          Text(
            '${m.emoji} ${m.label}' +
                (conflict.note.isEmpty ? '' : ' · ${conflict.note}'),
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Text(
            '继续保存会删除上面这条原有记录。',
            style: TextStyle(
              fontSize: 12,
              color: Theme.of(ctx).colorScheme.error,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.of(ctx).pop();
            onCancel();
          },
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () {
            Navigator.of(ctx).pop();
            onOverwrite();
          },
          child: const Text('覆盖'),
        ),
      ],
    ),
  );
}
