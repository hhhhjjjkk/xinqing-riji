package com.mooddiary.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 每小时心情提醒：开关状态、通知渠道与 WorkManager 调度
 */
object Reminder {
    const val CHANNEL_ID = "mood_hourly_reminder"
    private const val WORK_NAME = "mood_hourly_reminder"
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "hourly_reminder"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            ensureChannel(context)
            // 首次延迟到下一个整点，之后每小时触发一次
            val initialDelayMinutes = (60 - LocalTime.now().minute).toLong()
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "每小时心情提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每小时提醒你记录当下的心情"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * 每小时检查一次：本小时还没记录心情就发通知提醒，点通知直达记录弹窗
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!Reminder.hasNotificationPermission(context)) return Result.success()

        val hour = LocalTime.now().hour

        // 免打扰时段内不提醒（跨天时段如 22→8 也能正确处理）
        if (QuietHours.isQuiet(SettingsStore(context).settings.value, hour)) {
            return Result.success()
        }

        // 本小时已记录过心情则不打扰
        val recorded = MoodDatabase.get(context).dao().findByDateHour(LocalDate.now().toString(), hour) != null
        if (recorded) return Result.success()

        Reminder.ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HOUR, hour)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, hour, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, Reminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mood)
            .setContentTitle("现在心情怎么样？")
            .setContentText("点一下，记录 ${String.format(Locale.CHINA, "%02d:00", hour)} 这一小时的心情")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(hour, notification)
        return Result.success()
    }
}
