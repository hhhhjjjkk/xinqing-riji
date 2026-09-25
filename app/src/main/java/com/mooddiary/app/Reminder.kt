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
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "hourly_reminder"
    private const val REQ_ALARM = 9001

    /** 整点触发广播 */
    const val ACTION_TICK = "com.mooddiary.app.ACTION_REMINDER_TICK"
    /** 在通知里点表情的快速记录广播 */
    const val ACTION_QUICK_MOOD = "com.mooddiary.app.ACTION_QUICK_MOOD"
    const val EXTRA_MOOD_ID = "extra_mood_id"
    const val EXTRA_HOUR = "extra_hour"

    /** 心情列表，供通知 RemoteViews 使用（和 moods 保持一致） */
    private val NOTIFICATION_MOODS = listOf(
        5 to "😄", 4 to "😌", 3 to "😐", 2 to "😔", 1 to "😡"
    )

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
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

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        val triggerAt = nextHourMillis()

        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.cancel(pi)
            am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_HOUR, pi)
            return
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_HOUR, pi)
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
        val views = RemoteViews(context.packageName, R.layout.notification_mood_chooser)
        views.setTextViewText(
            R.id.notification_title,
            "现在心情怎么样？点一个表情，记录 ${String.format(Locale.CHINA, "%02d:00", hour)} 的心情"
        )
        NOTIFICATION_MOODS.forEach { (moodId, _) ->
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

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 23) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
        }
    }
}
