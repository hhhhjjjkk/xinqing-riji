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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale

/**
 * 每小时心情提醒的调度与发送。
 *
 * 调度为什么用 AlarmManager 而不是 WorkManager 周期任务：
 * WorkManager 的周期任务在系统 Doze 模式下会被推迟，国产 ROM 的电池优化
 * 更是直接不执行，实测表现为「只有 app 在后台才提醒」。
 * 这里改用 setExactAndAllowWhileIdle 精确闹钟 + setRepeating 兜底，
 * 配合开机广播恢复调度，并引导用户把本应用加入电池优化白名单。
 */
object Reminder {

    const val CHANNEL_ID = "mood_hourly_reminder"
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "hourly_reminder"
    private const val REQ_ALARM = 9001

    /** 触发时把当前小时通过广播带出来，接收端再决定是否通知 */
    const val ACTION_TICK = "com.mooddiary.app.ACTION_REMINDER_TICK"

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

    /** 开机 / 包更新后由广播调用，恢复已开启的提醒 */
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
            ).apply {
                description = "每小时提醒你记录当下的心情"
            }
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
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TICK
        }
        // FLAG_IMMUTABLE 必须加，Android 12+ 强制要求
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQ_ALARM, intent, flags)
    }

    /** 下一个整点的时间戳 */
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

        // Android 12+ 需要先获得精确闹钟权限，否则会抛 SecurityException
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.cancel(pi) // 没有权限就退回普通重复闹钟
            am.setRepeating(
                AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_HOUR, pi
            )
            return
        }

        // 精确闹钟：Doze 下也能在整点触发（allowWhileIdle）
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        // 同时挂一个重复闹钟兜底：万一单次闹钟因进程被杀而丢失，
        // 系统会按小时再次唤起。两者触发到同一 PendingIntent，不会重复通知
        // （接收端按小时去重）。
        am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_HOUR, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    // ---------- 发送通知 ----------

    /** 由广播调用：判断免打扰/已记录后决定是否通知，并续排下一次 */
    fun onTick(context: Context) {
        if (!isEnabled(context)) return
        if (!hasNotificationPermission(context)) return

        val hour = LocalTime.now().hour

        // 免打扰时段内不提醒（跨天时段如 22→8 也能正确处理）
        if (QuietHours.isQuiet(SettingsStore(context).settings.value, hour)) {
            schedule(context)
            return
        }

        // 本小时已记录过心情则不打扰
        runCatching {
            MoodDatabase.get(context).dao()
                .findByDateHourSync(LocalDate.now().toString(), hour) != null
        }.getOrDefault(false).let { recorded ->
            if (recorded) {
                schedule(context)
                return
            }
        }

        sendNotification(context, hour)
        schedule(context) // 续排下一个整点
    }

    private fun sendNotification(context: Context, hour: Int) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HOUR, hour)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, hour, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mood)
            .setContentTitle("现在心情怎么样？")
            .setContentText("点一下，记录 ${String.format(Locale.CHINA, "%02d:00", hour)} 这一小时的心情")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(hour, notification)
    }

    // ---------- 电池优化 ----------

    /**
     * 是否已被加入电池优化白名单。
     * 返回 true 表示「不需要再引导」（即已忽略优化，或系统不支持）。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳转到系统的「忽略电池优化」设置页（部分 ROM 会落到电池设置页） */
    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 23) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure {
                    // 有些 ROM 不支持上面的 Action，退回通用电池设置页
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
