import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';
import 'settings_store.dart';

const _palette = [
  0xFFFFB300, 0xFF43A047, 0xFF78909C, 0xFF42A5F5, 0xFFEF5350,
  0xFFE48600, 0xFF2B54A8, 0xFF2E7D5B, 0xFF7A4FA3, 0xFFC2185B,
  0xFF00707C, 0xFFB3261E, 0xFF3F51B5, 0xFF00695C, 0xFFBF5B00,
  0xFF4A5C6A, 0xFFFF8CA8, 0xFF5FC99A, 0xFFC4A3E8, 0xFFAEC6FF,
];

/// 心情标签管理：增删改名称/表情/颜色/分值，保存或恢复默认。
Future<void> showMoodEditor(BuildContext context) {
  return showDialog(
    context: context,
    builder: (ctx) => const _MoodEditorDialog(),
  );
}

class _MoodEditorDialog extends StatefulWidget {
  const _MoodEditorDialog();

  @override
  State<_MoodEditorDialog> createState() => _MoodEditorDialogState();
}

class _MoodEditorDialogState extends State<_MoodEditorDialog> {
  late List<_EditableMood> _list;

  @override
  void initState() {
    super.initState();
    _list = [
      for (final m in moods)
        _EditableMood(
          id: m.id,
          label: m.label,
          emoji: m.emoji,
          color: m.color,
          score: m.score,
        ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('心情标签'),
      content: SizedBox(
        width: double.maxFinite,
        height: 380,
        child: ListView.builder(
          itemCount: _list.length,
          itemBuilder: (context, i) {
            final m = _list[i];
            return ListTile(
              leading: Text(m.emoji, style: const TextStyle(fontSize: 24)),
              title: Text(m.label),
              subtitle: Text('分值 ${m.score}（用于趋势统计）',
                  style: const TextStyle(fontSize: 11)),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 16,
                    height: 16,
                    decoration: BoxDecoration(
                        color: m.color, shape: BoxShape.circle),
                  ),
                  IconButton(
                    icon: const Icon(Icons.edit, size: 18),
                    onPressed: () => _edit(i),
                  ),
                  IconButton(
                    icon: const Icon(Icons.delete_outline, size: 18),
                    onPressed: () =>
                        setState(() => _list.removeAt(i)),
                  ),
                ],
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton.icon(
          onPressed: () => setState(() {
            _list.add(_EditableMood(
              id: nextMoodId(),
              label: '新心情',
              emoji: '🙂',
              color: Color(_palette[_list.length % _palette.length]),
              score: 3,
            ));
          }),
          icon: const Icon(Icons.add),
          label: const Text('添加心情'),
        ),
        TextButton(
          onPressed: () {
            SharedPreferences.getInstance().then((p) {
              MoodCatalogStorage.reset(p);
              moodCatalog.apply(kDefaultMoods);
            });
            Navigator.of(context).pop();
          },
          child: const Text('恢复默认'),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () {
            SharedPreferences.getInstance().then((p) {
              MoodCatalogStorage.save(
                  p,
                  _list
                      .map((m) => Mood(
                          id: m.id,
                          label: m.label,
                          emoji: m.emoji,
                          color: m.color,
                          score: m.score))
                      .toList());
              moodCatalog.apply(_list
                  .map((m) => Mood(
                      id: m.id,
                      label: m.label,
                      emoji: m.emoji,
                      color: m.color,
                      score: m.score))
                  .toList());
            });
            Navigator.of(context).pop();
          },
          child: const Text('保存'),
        ),
      ],
    );
  }

  Future<void> _edit(int index) async {
    final m = _list[index];
    final label = TextEditingController(text: m.label);
    final emoji = TextEditingController(text: m.emoji);
    var color = m.color;
    var score = m.score;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('编辑心情'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: label,
                decoration: const InputDecoration(labelText: '名称'),
              ),
              TextField(
                controller: emoji,
                decoration: const InputDecoration(labelText: '表情'),
              ),
              const SizedBox(height: 8),
              const Text('颜色',
                  style: TextStyle(fontSize: 12)),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final c in _palette)
                    GestureDetector(
                      onTap: () => setLocal(() => color = Color(c)),
                      child: Container(
                        width: 24,
                        height: 24,
                        decoration: BoxDecoration(
                          color: Color(c),
                          shape: BoxShape.circle,
                          border: color.toARGB32() == c
                              ? Border.all(
                                  color: Theme.of(ctx).colorScheme.onSurface,
                                  width: 2)
                              : null,
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 8),
              const Text('分值（用于趋势统计）',
                  style: TextStyle(fontSize: 12)),
              DropdownButton<int>(
                value: score,
                isExpanded: true,
                items: [
                  for (var s = 1; s <= 5; s++)
                    DropdownMenuItem(value: s, child: Text('$s 分')),
                ],
                onChanged: (v) => setLocal(() => score = v ?? 3),
              ),
            ],
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(ctx).pop(false),
                child: const Text('取消')),
            FilledButton(
                onPressed: () => Navigator.of(ctx).pop(true),
                child: const Text('确定')),
          ],
        ),
      ),
    );

    if (ok == true) {
      setState(() {
        _list[index] = _EditableMood(
          id: m.id,
          label: label.text.trim().isEmpty ? m.label : label.text.trim(),
          emoji: emoji.text.trim().isEmpty ? m.emoji : emoji.text.trim(),
          color: color,
          score: score,
        );
      });
    }
  }
}

class _EditableMood {
  _EditableMood({
    required this.id,
    required this.label,
    required this.emoji,
    required this.color,
    required this.score,
  });

  final int id;
  String label;
  String emoji;
  Color color;
  int score;
}
