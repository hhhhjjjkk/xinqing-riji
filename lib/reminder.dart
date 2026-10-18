import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:timezone/data/latest.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;

import 'database.dart';
import 'models.dart';

/// 每小时心情提醒（flutter_local_notifications 实现）。
///
/// 与 Kotlin 版（AlarmManager + RemoteViews 自定义通知）的差异：
/// - 调度改为 24 个「每日重复」系统通知（每小时一个），
///   免打扰时段的小时在调度时直接排除；
/// - 通知内嵌的表情按钮改为 Android 通知 actions（最多 3 个，
///   取分值最高的 3 个心情），点击直接写库不打开 app；
/// - 点击通知本体跳转 app，由 app 打开当前小时的记录弹窗。
class ReminderService {
  ReminderService._();
  static final instance = ReminderService._();

  static const _channelId = 'mood_hourly_reminder';
  static const _channelName = '每小时心情提醒';

  final _plugin = FlutterLocalNotificationsPlugin();
  bool _inited = false;

  /// 全局通知点击回调（app 前台/后台点击）：payload = 'hour:<h>'
  void Function(int hour)? onOpenHour;

  /// 通知 actions 点击（后台 isolate）：payload = 'mood:<moodId>:<hour>'

  Future<void> init() async {
    if (_inited) return;
    tzdata.initializeTimeZones();
    final settings = InitializationSettings(
      android: const AndroidInitializationSettings('@mipmap/ic_launcher'),
    );
    await _plugin.initialize(
      settings,
      onDidReceiveNotificationResponse: (resp) {
        final p = resp.payload ?? '';
        if (p.startsWith('hour:')) {
          final h = int.tryParse(p.substring(5));
          if (h != null) onOpenHour?.call(h);
        }
      },
      onDidReceiveBackgroundNotificationResponse: notificationBackgroundHandler,
    );
    _inited = true;
  }

  static Future<void> notificationBackgroundHandler(NotificationResponse resp) async {
    final p = resp.payload ?? '';
    if (p.startsWith('mood:')) {
      final parts = p.split(':');
      if (parts.length >= 3) {
        final moodId = int.tryParse(parts[1]);
        final hour = int.tryParse(parts[2]);
        if (moodId != null && hour != null) {
          await MoodRepository.recordQuickMood(hour, moodId);
        }
      }
    }
  }

  bool hasNotificationPermission() => _notifGranted;

  bool _notifGranted = false;

  /// 请求通知权限（Android 13+），返回是否已授予
  Future<bool> requestPermission() async {
    final granted = await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
    _notifGranted = granted ?? true;
    return _notifGranted;
  }

  Future<void> _checkPermission() async {
    final granted = await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.areNotificationsEnabled();
    _notifGranted = granted ?? true;
  }

  /// 通知里要显示的心情（最多 [AppSettings.notifyMoodMax] 个）。
  /// 优先按用户指定顺序；否则自动取分值最高的若干个。
  List<Mood> notificationMoods(AppSettings settings, List<Mood> catalog) {
    final byId = {for (final m in catalog) m.id: m};
    final picked = settings.notifyMoodIds
        .map((id) => byId[id])
        .whereType<Mood>()
        .take(3) // Android actions 上限 3 个
        .toList();
    if (picked.isNotEmpty) return picked;
    final sorted = [...catalog]..sort((a, b) => b.score.compareTo(a.score));
    return sorted.take(3).toList();
  }

  /// 重新调度全部 24 小时提醒（免打扰时段的小时跳过）。
  Future<void> reschedule(AppSettings settings, List<Mood> catalog) async {
    await init();
    await cancelAll();
    if (!settings.reminderEnabled) return;
    await _checkPermission();
    if (!_notifGranted) return;

    final actions = notificationMoods(settings, catalog)
        .map((m) => AndroidNotificationAction(
              'mood_${m.id}',
              '${m.emoji} ${m.label}',
              showsUserInterface: false,
              cancelNotification: false,
            ))
        .toList();

    for (var h = 0; h < 24; h++) {
      // 免打扰时段在调度时直接排除（效果与原版 tick 时静默一致）
      if (QuietHours.isQuietNow(settings, h) ||
          (settings.quietHoursEnabled &&
              QuietHours.isQuiet(settings.quietStart, settings.quietEnd, h))) {
        continue;
      }
      await _plugin.zonedSchedule(
        h, // 通知 id = 小时（每日重复语义下天然唯一）
        '记一下现在的心情',
        '点这里按小时记录 · 长按通知可直接快速记录',
        _nextInstanceOf(h),
        NotificationDetails(
          android: AndroidNotificationDetails(
            _channelId,
            _channelName,
            channelDescription: '每小时提醒你记录当下心情',
            importance: Importance.defaultImportance,
            priority: Priority.defaultPriority,
            styleInformation: const BigTextStyleInformation(''),
            actions: actions.isEmpty ? null : actions,
          ),
        ),
        androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
        matchDateTimeComponents: DateTimeComponents.time,
        payload: 'hour:$h',
      );
    }
  }

  tz.TZDateTime _nextInstanceOf(int hour) {
    final now = tz.TZDateTime.now(tz.local);
    var scheduled = tz.TZDateTime(tz.local, now.year, now.month, now.day, hour);
    if (!scheduled.isAfter(now)) {
      scheduled = scheduled.add(const Duration(days: 1));
    }
    return scheduled;
  }

  Future<void> cancelAll() async {
    await init();
    await _plugin.cancelAll();
  }

  /// 立即弹一条测试通知（诊断用）
  Future<void> testNotification(List<Mood> catalog) async {
    await init();
    await _checkPermission();
    await _plugin.show(
      99999,
      '测试通知 ✅',
      '通知渠道工作正常，提醒功能已就绪',
      const NotificationDetails(
        android: AndroidNotificationDetails(
          _channelId,
          _channelName,
          importance: Importance.defaultImportance,
          priority: Priority.defaultPriority,
        ),
      ),
      payload: 'hour:${DateTime.now().hour}',
    );
  }

  /// 启动时同步提醒开关（与 Kotlin 版 syncReminder 等价）
  Future<void> syncFromSettings(AppSettings settings, List<Mood> catalog) async {
    await reschedule(settings, catalog);
  }
}

/// 设置页需要读写原始 prefs 的场合（提醒可靠性说明等）可用
SharedPreferences? sharedPrefsInstance;
