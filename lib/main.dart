import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'database.dart';
import 'dialogs.dart';
import 'glass_nav_bar.dart';
import 'models.dart';
import 'pages/calendar_page.dart';
import 'pages/records_page.dart';
import 'pages/settings_page.dart';
import 'pages/stats_page.dart';
import 'press_bounce.dart';
import 'reminder.dart';
import 'settings_store.dart';
import 'theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final prefs = await SharedPreferences.getInstance();
  sharedPrefsInstance = prefs;

  // 心情目录：载入用户自定义（未自定义则沿用内置默认）
  moodCatalog = MoodCatalog(MoodCatalogStorage.load(prefs) ?? kDefaultMoods);

  final store = SettingsStore(prefs);
  final repo = MoodRepository();

  runApp(MoodDiaryApp(settingsStore: store, repo: repo));
}

class MoodDiaryApp extends StatelessWidget {
  const MoodDiaryApp({super.key, required this.settingsStore, required this.repo});

  final SettingsStore settingsStore;
  final MoodRepository repo;

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: settingsStore),
        ChangeNotifierProvider.value(value: repo),
        ChangeNotifierProvider.value(value: moodCatalog),
      ],
      child: DynamicColorBuilder(
        builder: (lightDynamic, darkDynamic) {
          return Consumer<SettingsStore>(
            builder: (context, store, _) {
              final s = store.settings;
              return MaterialApp(
                title: '心情日记',
                debugShowCheckedModeBanner: false,
                localizationsDelegates: const [
                  GlobalMaterialLocalizations.delegate,
                  GlobalWidgetsLocalizations.delegate,
                  GlobalCupertinoLocalizations.delegate,
                ],
                supportedLocales: const [Locale('zh', 'CN')],
                locale: const Locale('zh', 'CN'),
                theme: buildMoodTheme(
                    dark: false, settings: s, dynamicScheme: lightDynamic),
                darkTheme: buildMoodTheme(
                    dark: true, settings: s, dynamicScheme: darkDynamic),
                themeMode: switch (s.themeMode) {
                  ThemeModeSetting.system => ThemeMode.system,
                  ThemeModeSetting.light => ThemeMode.light,
                  ThemeModeSetting.dark => ThemeMode.dark,
                },
                home: const _Boot(),
              );
            },
          );
        },
      ),
    );
  }
}

/// 启动装配：加载记录 → 数据修复 → 提醒同步 → 进入主界面
class _Boot extends StatefulWidget {
  const _Boot();

  @override
  State<_Boot> createState() => _BootState();
}

class _BootState extends State<_Boot> {
  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    final repo = context.read<MoodRepository>();
    final store = context.read<SettingsStore>();
    await repo.load();
    // 一次性数据修复：失配心情 id 永久改写为当前目录就近项（与 Kotlin 版一致）
    await repo.repairUnknownMoods();
    // 提醒调度同步
    await ReminderService.instance
        .syncFromSettings(store.settings, moodCatalog.moods);
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const _HomeShell()));
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Center(
        child: CircularProgressIndicator(color: scheme.primary),
      ),
    );
  }
}

class _HomeShell extends StatefulWidget {
  const _HomeShell();

  @override
  State<_HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<_HomeShell> {
  late final PageController _pager;
  String? _glassInitError;
  DateTime _month = _yearMonth(DateTime.now());

  static DateTime _yearMonth(DateTime d) => DateTime(d.year, d.month, 1);

  @override
  void initState() {
    super.initState();
    final startTab = context.read<SettingsStore>().settings.startTab.pos;
    _pager = PageController(initialPage: startTab);
    // 着色器初始化：失败则导航栏降级渲染
    LiquidGlassWidgets.initialize().catchError((_) {
      if (mounted) setState(() => _glassInitError = 'liquid glass init failed');
    });
    // 通知点击 → 打开当前小时记录弹窗
    ReminderService.instance.onOpenHour = (h) {
      _openEdit(DateTime.now(), h);
    };
  }

  @override
  void dispose() {
    _pager.dispose();
    ReminderService.instance.onOpenHour = null;
    super.dispose();
  }

  void _openEdit(DateTime date, int hour) {
    final repo = context.read<MoodRepository>();
    final store = context.read<SettingsStore>();
    final entry = repo.findByDateHour(_fmtDate(date), hour);
    showMoodDialog(
      context,
      date: date,
      hour: hour,
      entry: entry,
      defaultMoodId: store.settings.defaultMoodId,
      onSave: (d, h, m, n) => _save(d, h, m, n, entry),
      onDelete: entry == null
          ? () {}
          : () {
              context.read<MoodRepository>().delete(entry);
            },
    );
  }

  Future<void> _save(DateTime date, int hour, int moodId, String note,
      MoodEntry? old) async {
    final repo = context.read<MoodRepository>();
    final r = await repo.save(date, hour, moodId, note, old);
    if (r is Conflict && mounted) {
      showConflictDialog(
        context,
        date: date,
        hour: hour,
        conflict: r.existing,
        onCancel: () {},
        onOverwrite: () async {
          await repo.overwrite(date, hour, moodId, note, old, r.existing);
        },
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<SettingsStore>();
    final s = store.settings;
    final repo = context.watch<MoodRepository>();
    final scheme = Theme.of(context).colorScheme;

    final bgSurface = scheme.surface;
    final bgTop = scheme.primary
        .withValues(alpha: bgSurface.computeLuminance() < 0.5 ? 0.14 : 0.09)
        .computeComposite(bgSurface);

    final currentPage =
        _pager.hasClients ? (_pager.page ?? 0).round() : s.startTab.pos;

    return Scaffold(
      // 透明容器：让底层那条连续渐变透出来
      backgroundColor: Colors.transparent,
      appBar: AppBar(
        centerTitle: true,
        title: const Text('心情日记', style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: Colors.transparent,
        actions: [
          // 设置页不显示提醒开关（与原版一致）
          if (currentPage != 3) const _ReminderToggle(),
        ],
      ),
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(bgTop), bgSurface, bgSurface],
            stops: const [0.0, 0.4, 1.0],
          ),
        ),
        child: PageView(
          controller: _pager,
          children: [
            CalendarPage(
              month: _month,
              entries: repo.entries,
              weekStart: s.weekStart,
              showNote: s.calendarShowNote,
              showEmoji: s.calendarShowEmoji,
              setMonth: (m) => setState(() => _month = _yearMonth(m)),
              open: (d) {
                if (s.calendarTapAction == CalendarTapAction.quickLogNow) {
                  _openEdit(d, DateTime.now().hour);
                } else {
                  _openDayBoard(d);
                }
              },
            ),
            RecordsPage(
              entries: repo.entries,
              open: (e) {
                final parts = e.date.split('-');
                final d = DateTime(int.parse(parts[0]), int.parse(parts[1]),
                    int.parse(parts[2]));
                _openEdit(d, e.hour);
              },
            ),
            StatsPage(
              month: _month,
              entries: repo.entries,
              setMonth: (m) => setState(() => _month = _yearMonth(m)),
            ),
            SettingsPage(recordCount: repo.entries.length),
          ],
        ),
      ),
      // 添加按钮：Q 弹按压（按下膨胀 ×1.30、bouncy 回弹）
      floatingActionButton: BouncyFab(
        onTap: () => _openEdit(DateTime.now(), DateTime.now().hour),
      ),
      // 液态玻璃底部导航栏：Flutter 引擎渲染，全 Android 版本真玻璃
      bottomNavigationBar: SizedBox(
        height: 72,
        child: GlassNavBar(
          selectedIndex: currentPage,
          onSelect: (page) {
            if (!_pager.hasClients) return;
            _pager.animateToPage(
              page,
              duration: const Duration(milliseconds: 320),
              curve: Curves.easeOutCubic,
            );
          },
          onScrub: (page, fraction) {
            if (_pager.hasClients) {
              _pager.position.jumpTo(_targetScroll(page, fraction));
            }
          },
          onScrubEnd: (page) {
            if (_pager.hasClients) {
              _pager.animateToPage(
                page,
                duration: const Duration(milliseconds: 320),
                curve: Curves.easeOutCubic,
              );
            }
          },
          glassInitError: _glassInitError,
        ),
      ),
    );
  }

  void _openDayBoard(DateTime d) {
    final repo = context.read<MoodRepository>();
    final dayEntries =
        repo.entries.where((e) => e.date == _fmtDate(d)).toList();
    showHourMoodSheet(
      context,
      date: d,
      dayEntries: dayEntries,
      onPick: (h, e) {
        Navigator.of(context).pop();
        _openEdit(d, h);
      },
    );
  }

  double _targetScroll(int page, double fraction) {
    final viewport = _pager.position.viewportDimension;
    return (page - fraction) * viewport;
  }

  static String _fmtDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}

/// 顶栏提醒开关（与设置页同一真值）
class _ReminderToggle extends StatelessWidget {
  const _ReminderToggle();

  @override
  Widget build(BuildContext context) {
    final store = context.watch<SettingsStore>();
    final enabled = store.settings.reminderEnabled;
    return IconButton(
      onPressed: () async {
        if (enabled) {
          store.setReminderEnabled(false);
          if (context.mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('已关闭每小时心情提醒')));
          }
        } else {
          final granted =
              await ReminderService.instance.requestPermission();
          if (!granted) {
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                  content: Text('需要通知权限才能提醒你记录心情')));
            }
            return;
          }
          store.setReminderEnabled(true);
          if (context.mounted) {
            ScaffoldMessenger.of(context)
                .showSnackBar(const SnackBar(content: Text('已开启每小时心情提醒')));
          }
        }
      },
      icon: Icon(
        enabled ? Icons.notifications : Icons.notifications_off,
        color: enabled
            ? Theme.of(context).colorScheme.primary
            : Theme.of(context).colorScheme.onSurfaceVariant,
      ),
      tooltip: enabled ? '关闭每小时提醒' : '开启每小时提醒',
    );
  }
}

extension on Color {
  /// primary 淡染 compositeOver bg 的等效色（与 Kotlin compositeOver 一致）
  int computeComposite(Color bg) {
    final a = alpha;
    final r = ((red * a + bg.red * (1 - a))).round().clamp(0, 255);
    final g = ((green * a + bg.green * (1 - a))).round().clamp(0, 255);
    final b = ((blue * a + bg.blue * (1 - a))).round().clamp(0, 255);
    return (255 << 24) | (r << 16) | (g << 8) | b;
  }
}
