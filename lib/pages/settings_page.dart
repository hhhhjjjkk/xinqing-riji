import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../database.dart';
import '../models.dart';
import '../mood_editor.dart';
import '../press_bounce.dart';
import '../reminder.dart';
import '../settings_store.dart';
import '../theme.dart';

/// 设置页：外观 / 个性化 / 启动与操作 / 提醒 / 记录 / 日历 / 数据 / 关于。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.recordCount});

  final int recordCount;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _clearConfirm = false;

  @override
  Widget build(BuildContext context) {
    final store = context.watch<SettingsStore>();
    final s = store.settings;
    final scheme = Theme.of(context).colorScheme;
    final corner = s.cornerLevel.dp;

    Widget section(String title, List<Widget> children) => _Section(
          title: title,
          corner: corner,
          scheme: scheme,
          children: children,
        );

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        section('外观', [
          _ChoiceRow<ThemeModeSetting>(
            label: '主题模式',
            value: s.themeMode,
            options: const {
              ThemeModeSetting.system: '跟随系统',
              ThemeModeSetting.light: '浅色',
              ThemeModeSetting.dark: '深色',
            },
            onChanged: store.setThemeMode,
          ),
          _SwitchRow(
            label: '深色纯黑背景',
            desc: 'OLED 更省电（仅深色模式生效）',
            value: s.pureBlack,
            onChanged: store.setPureBlack,
          ),
          _ChoiceRow<FontLevel>(
            label: '界面字号',
            value: s.fontLevel,
            options: const {
              FontLevel.small: '小',
              FontLevel.normal: '标准',
              FontLevel.large: '大',
            },
            onChanged: store.setFontLevel,
          ),
          _ChoiceRow<CornerLevel>(
            label: '卡片圆角',
            value: s.cornerLevel,
            options: const {
              CornerLevel.small: '小',
              CornerLevel.normal: '标准',
              CornerLevel.large: '大',
            },
            onChanged: store.setCornerLevel,
          ),
        ]),

        section('个性化', [
          // 强调色：色块网格
          _SectionLabel('强调色'),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              for (final a in AccentColor.values)
                _AccentDot(
                  scheme: kAccents[a]!,
                  selected: s.accentColor == a,
                  label: a.label,
                  onTap: () => store.setAccentColor(a),
                ),
            ],
          ),
          _SwitchRow(
            label: '动态取色',
            desc: 'Android 12+ 从壁纸取色',
            value: s.useDynamicColor,
            onChanged: store.setUseDynamicColor,
          ),
          _ChoiceRow<GlowLevel>(
            label: '光效强度',
            value: s.glowLevel,
            options: const {
              GlowLevel.soft: '柔和',
              GlowLevel.normal: '标准',
              GlowLevel.strong: '明亮',
            },
            onChanged: store.setGlowLevel,
          ),
        ]),

        section('启动与操作', [
          _ChoiceRow<StartTab>(
            label: '启动默认页',
            value: s.startTab,
            options: {
              for (final t in StartTab.values) t: t.label,
            },
            onChanged: store.setStartTab,
          ),
          _ChoiceRow<CalendarTapAction>(
            label: '点击日历某天时',
            value: s.calendarTapAction,
            options: const {
              CalendarTapAction.openDayBoard: '展开 24 小时',
              CalendarTapAction.quickLogNow: '直接记录此刻',
            },
            onChanged: store.setCalendarTapAction,
          ),
          _ChoiceRow<int>(
            label: '默认心情',
            value: s.defaultMoodId,
            options: {
              for (final m in moods) m.id: '${m.emoji} ${m.label}',
            },
            onChanged: store.setDefaultMood,
          ),
        ]),

        section('提醒', [
          _SwitchRow(
            label: '每小时心情提醒',
            desc: '整点通知你记录当下心情',
            value: s.reminderEnabled,
            onChanged: (v) async {
              if (v) {
                final granted = await ReminderService.instance.requestPermission();
                if (!granted) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                        content: Text('需要通知权限才能提醒你记录心情')));
                  }
                  return;
                }
                store.setReminderEnabled(true);
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('已开启每小时心情提醒')));
                }
              } else {
                store.setReminderEnabled(false);
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('已关闭每小时心情提醒')));
                }
              }
            },
          ),
          _SwitchRow(
            label: '免打扰时段',
            desc:
                '${s.quietStart.toString().padLeft(2, '0')}:00 - ${s.quietEnd.toString().padLeft(2, '0')}:00 不提醒',
            value: s.quietHoursEnabled,
            onChanged: store.setQuietHoursEnabled,
          ),
          if (s.quietHoursEnabled)
            Row(
              children: [
                Expanded(
                  child: _HourPicker(
                    label: '开始',
                    value: s.quietStart,
                    onChanged: (v) =>
                        store.setQuietRange(v, s.quietEnd),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _HourPicker(
                    label: '结束',
                    value: s.quietEnd,
                    onChanged: (v) =>
                        store.setQuietRange(s.quietStart, v),
                  ),
                ),
              ],
            ),
          TextButton.icon(
            onPressed: () async {
              await ReminderService.instance.init();
              await ReminderService.instance.testNotification(moods);
            },
            icon: const Icon(Icons.notifications_active),
            label: const Text('立即弹出测试通知'),
          ),
          Text(
            '通知里点击表情可快速记录（最多 3 个，取分值最高）；'
            '点击通知本体直接打开当前小时的记录弹窗。',
            style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
          ),
        ]),

        section('记录', [
          TextButton.icon(
            onPressed: () => showMoodEditor(context),
            icon: const Icon(Icons.label),
            label: Text('管理心情标签（${moods.length} 个）'),
          ),
        ]),

        section('日历', [
          _ChoiceRow<WeekStart>(
            label: '一周开始于',
            value: s.weekStart,
            options: const {WeekStart.sunday: '周日', WeekStart.monday: '周一'},
            onChanged: store.setWeekStart,
          ),
          _SwitchRow(
            label: '日历格子显示表情',
            value: s.calendarShowEmoji,
            onChanged: store.setCalendarShowEmoji,
          ),
          _SwitchRow(
            label: '日历格子显示备注',
            desc: '在色块里显示备注摘要',
            value: s.calendarShowNote,
            onChanged: store.setCalendarShowNote,
          ),
        ]),

        section('数据', [
          Text('共 ${widget.recordCount} 条心情记录',
              style: TextStyle(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 8),
          if (!_clearConfirm)
            OutlinedButton.icon(
              onPressed: () => setState(() => _clearConfirm = true),
              icon: const Icon(Icons.delete_forever),
              label: const Text('清空所有记录'),
              style: OutlinedButton.styleFrom(
                  foregroundColor: scheme.error),
            )
          else
            Row(
              children: [
                Expanded(
                  child: Text('将删除全部 ${widget.recordCount} 条记录，且无法恢复。',
                      style: TextStyle(color: scheme.error, fontSize: 12)),
                ),
                TextButton(
                    onPressed: () => setState(() => _clearConfirm = false),
                    child: const Text('取消')),
                TextButton(
                    onPressed: () {
                      setState(() => _clearConfirm = false);
                      context
                          .read<MoodRepository>()
                          .deleteAll()
                          .then((_) {
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('已清空全部记录')));
                        }
                      });
                    },
                    child: Text('确认清空',
                        style: TextStyle(color: scheme.error))),
              ],
            ),
        ]),

        section('关于', [
          const Text('心情日记',
              style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          Text('v5.0.0 · 纯 Flutter 重写版',
              style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
          Text('按小时记录心情 · 液态玻璃导航栏（全版本可用）',
              style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
          const SizedBox(height: 8),
          Text(
            '上游 liquid_glass_widgets：MIT License · '
            'Copyright 2025 Tim Lehmann / 2026 Sebastian Degenaar',
            style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
          ),
        ]),
      ],
    );
  }
}

// ───────────────────────── 通用控件 ─────────────────────────

class _Section extends StatelessWidget {
  const _Section({
    required this.title,
    required this.corner,
    required this.scheme,
    required this.children,
  });

  final String title;
  final double corner;
  final ColorScheme scheme;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 8),
            child: Text(title,
                style: TextStyle(
                    fontWeight: FontWeight.bold, color: scheme.primary)),
          ),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(corner),
              color: scheme.surfaceContainerLow,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var i = 0; i < children.length; i++) ...[
                  if (i > 0) const SizedBox(height: 12),
                  children[i],
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);
  final String text;

  @override
  Widget build(BuildContext context) =>
      Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(text));
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.label,
    this.desc,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final String? desc;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label),
              if (desc != null)
                Text(desc!,
                    style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.onSurfaceVariant)),
            ],
          ),
        ),
        Switch(value: value, onChanged: onChanged),
      ],
    );
  }
}

class _ChoiceRow<T> extends StatelessWidget {
  const _ChoiceRow({
    required this.label,
    required this.value,
    required this.options,
    required this.onChanged,
  });

  final String label;
  final T value;
  final Map<T, String> options;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Text(label),
        const Spacer(),
        DropdownButton<T>(
          value: value,
          underline: const SizedBox.shrink(),
          items: [
            for (final e in options.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
          onChanged: (v) => v != null ? onChanged(v) : null,
        ),
      ],
    );
  }
}

class _AccentDot extends StatelessWidget {
  const _AccentDot({
    required this.scheme,
    required this.selected,
    required this.label,
    required this.onTap,
  });

  final AccentScheme scheme;
  final bool selected;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = Color(scheme.lightPrimary);
    return PressBounce(
      pressedScale: 0.85,
      child: GestureDetector(
        onTap: onTap,
        child: Column(
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color,
                border: selected
                    ? Border.all(
                        color: Theme.of(context).colorScheme.onSurface,
                        width: 2.5)
                    : null,
              ),
            ),
            const SizedBox(height: 3),
            Text(label, style: const TextStyle(fontSize: 11)),
          ],
        ),
      ),
    );
  }
}

class _HourPicker extends StatelessWidget {
  const _HourPicker({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Text('$label '),
        Expanded(
          child: DropdownButton<int>(
            value: value,
            isExpanded: true,
            underline: const SizedBox.shrink(),
            items: [
              for (var h = 0; h < 24; h++)
                DropdownMenuItem(
                    value: h,
                    child: Text('${h.toString().padLeft(2, '0')}:00')),
            ],
            onChanged: (v) => v != null ? onChanged(v) : null,
          ),
        ),
      ],
    );
  }
}
