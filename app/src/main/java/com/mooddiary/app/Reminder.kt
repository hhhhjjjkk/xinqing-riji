package com.mooddiary.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale

/**
 * 每小时心情提醒的调度与发送。
 *
 * 通知直接内嵌 5 个表情按钮，点一下就记录当前小时心情，不跳转 app。
 */
object Reminder {

    const val CHANNEL_ID = "mood_hourly_reminder"
    private const val REQ_ALARM = 9001
    /** 无精确闹钟权限时的触发窗口（15 分钟） */
    private const val WINDOW_MS = 15 * 60 * 1000L

    /** 整点触发广播 */
    const val ACTION_TICK = "com.mooddiary.app.ACTION_REMINDER_TICK"
    /** 在通知里点表情的快速记录广播 */
    const val ACTION_QUICK_MOOD = "com.mooddiary.app.ACTION_QUICK_MOOD"
    const val EXTRA_MOOD_ID = "extra_mood_id"
    const val EXTRA_HOUR = "extra_hour"

    /**
     * 通知里的表情按钮。按分值倒序，最多 5 个（RemoteViews 布局只有 5 个槽位）。
     * 跟随用户自定义的心情目录，而不是写死的 5 个内置心情。
     */
    private fun notificationMoods(): List<Pair<Int, String>> {
        // 正常情况下 moods 已由 MainActivity 载入自定义目录；
        // 万一为空则退回内置默认，保证通知至少有表情按钮可用
        val source = moods.ifEmpty { defaultMoods }
        return source.sortedByDescending { it.score }
            .take(5)
            .map { it.id to it.emoji }
    }

    /**
     * 开关状态的唯一真值在 SettingsStore 的 "app_settings"（key: reminder_enabled）。
     *
     * 此前这里另开一个 "settings" 文件存自己的 KEY_ENABLED，与设置页写入的
     * "app_settings" 是两份互不相干的真值：onTick 读到旧文件里的 false 就直接
     * return，于是无论是否加入电池优化白名单都不会响。现统一读取同一份配置。
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE)
            .getBoolean(SettingsStore.KEY_REMINDER, false)

    /** 只负责排程/取消闹钟；开关状态本身由 SettingsStore 持有 */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            ensureChannel(context)
            schedule(context)
        } else {
            cancel(context)
        }
    }

    fun rescheduleIfEnabled(context: Context) {
        if (isEnabled(context)) {
            ensureChannel(context)
            schedule(context)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "每小时心情提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "每小时提醒你记录当下的心情" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    // ---------- 调度 ----------

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_TICK }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQ_ALARM, intent, flags)
    }

    fun nextHourMillis(now: Calendar = Calendar.getInstance()): Long {
        val cal = now.clone() as Calendar
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 排下一次整点提醒。
     *
     * 关键修正（此前通知不响的根因）：
     * 1. 不能对同一个 PendingIntent 既设精确闹钟又设重复闹钟——
     *    后设的 setRepeating 会覆盖掉先设的 setExactAndAllowWhileIdle，
     *    而 setRepeating 自 Android 4.4 起是不精确的，在 Doze 下会被系统推迟到维护窗口，
     *    甚至整夜不触发。实际生效的其实是这个不可靠的重复闹钟，精确闹钟从未真正生效过。
     *
     * 2. 精确闹钟是一次性的，因此必须「链式」调度：本次触发后在 onTick 里再排下一次。
     *
     * 3. Android 12+ 若没有精确闹钟权限，改用 setWindow 的允许空闲触发（比 setRepeating 可靠得多），
     *    并暴露 canScheduleExactAlarms 供界面提示用户去授权。
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        val triggerAt = nextHourMillis()

        // 先清掉旧的，避免同一 PendingIntent 残留多个调度
        am.cancel(pi)

        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // 无精确闹钟权限：用带窗口的「允许空闲时触发」。
            // 比 setRepeating 可靠：系统会在窗口内唤醒，且 Doze 下仍有机会触发。
            am.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                WINDOW_MS,
                pi
            )
            return
        }

        // 有权限：单次精确闹钟（会在 Doze 下触发）。
        // 注意：不要在此之后再设 setRepeating，否则会把它覆盖掉。
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /** Android 12+ 是否已获准使用精确闹钟；未获准时界面应引导用户开启 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    // ---------- 通知 ----------

    fun onTick(context: Context) {
        if (!isEnabled(context)) return
        if (!hasNotificationPermission(context)) return

        val hour = LocalTime.now().hour

        if (QuietHours.isQuiet(SettingsStore(context).current(), hour)) {
            schedule(context)
            return
        }

        runCatching {
            MoodDatabase.get(context).dao()
                .findByDateHourSync(LocalDate.now().toString(), hour) != null
        }.getOrDefault(false).let { recorded ->
            if (recorded) { schedule(context); return }
        }

        sendNotification(context, hour)
        schedule(context)
    }

    fun sendNotification(context: Context, hour: Int) {
        ensureChannel(context)

        // 点通知整体仍可跳转 app（打开记录弹窗），但不是必须的
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HOUR, hour)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, hour, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 自定义布局：5 个表情按钮，点任意一个直接记录
        // 标题颜色跟随当前主题色
        val accentArgb = when (SettingsStore(context).current().accentColor) {
            AccentColor.AMBER -> 0xFFE48600.toInt()
            AccentColor.BLUE -> 0xFF2B54A8.toInt()
            AccentColor.GREEN -> 0xFF2E7D5B.toInt()
            AccentColor.PURPLE -> 0xFF7A4FA3.toInt()
            AccentColor.PINK -> 0xFFC2185B.toInt()
            AccentColor.CYAN -> 0xFF00707C.toInt()
            AccentColor.RED -> 0xFFB3261E.toInt()
            AccentColor.INDIGO -> 0xFF3F51B5.toInt()
            AccentColor.TEAL -> 0xFF00695C.toInt()
            AccentColor.ORANGE -> 0xFFBF5B00.toInt()
            AccentColor.SLATE -> 0xFF4A5C6A.toInt()
        }
        val views = RemoteViews(context.packageName, R.layout.notification_mood_chooser)
        views.setTextColor(R.id.notification_title, accentArgb)
        views.setTextViewText(
            R.id.notification_title,
            "现在心情怎么样？点一个表情，记录 ${String.format(Locale.CHINA, "%02d:00", hour)} 的心情"
        )
        notificationMoods().forEach { (moodId, _) ->
            val pending = PendingIntent.getBroadcast(
                context,
                moodId * 100 + hour,
                Intent(context, ReminderReceiver::class.java).apply {
                    action = ACTION_QUICK_MOOD
                    putExtra(EXTRA_MOOD_ID, moodId)
                    putExtra(EXTRA_HOUR, hour)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(
                context.resources.getIdentifier("btn_mood_$moodId", "id", context.packageName),
                pending
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mood)
            .setCustomContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(hour, notification)
    }

    /** 通知里点了表情：直接写库，不跳转 app */
    fun recordQuickMood(context: Context, hour: Int, moodId: Int) {
        val db = MoodDatabase.get(context)
        val dao = db.dao()
        val dateStr = LocalDate.now().toString()

        // 用事务保证原子性：有则更新，无则插入
        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                val existing = dao.findByDateHour(dateStr, hour)
                if (existing != null) {
                    dao.update(existing.copy(moodId = moodId, updatedAt = System.currentTimeMillis()))
                } else {
                    dao.insert(MoodEntry(date = dateStr, hour = hour, moodId = moodId))
                }
            }
        }

        // 取消通知：已记录，不再需要提醒
        context.getSystemService(NotificationManager::class.java)
            .cancel(hour)
    }

    // ---------- 电池优化 ----------

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 跳转到系统的电池优化设置页。
     * 逐级降级并给出提示，避免点了没反应：
     * 1. 专属白名单授权页（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）
     * 2. 系统电池优化列表页
     * 3. 应用详情页兜底
     * 4. 都不行就提示用户手动设置
     */
    fun openBatteryOptimizationSettings(context: Context) {
        fun tryStart(intent: Intent): Boolean = try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }

        val opened = if (Build.VERSION.SDK_INT < 23) {
            false
        } else {
            when {
                tryStart(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                ) -> true
                tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) -> true
                tryStart(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                ) -> true
                else -> false
            }
        }

        val msg = if (opened) "已打开系统设置，请允许后台运行"
        else "无法自动跳转，请手动在系统设置中找到本应用并允许后台运行"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
