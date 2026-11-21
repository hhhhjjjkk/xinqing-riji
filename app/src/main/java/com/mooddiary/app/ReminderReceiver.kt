package com.mooddiary.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "MoodReminder"

/**
 * 接收三类广播：
 * 1. [Reminder.ACTION_TICK] —— 到整点了，判断是否要发通知并续排下一次
 * 2. BOOT_COMPLETED / MY_PACKAGE_REPLACED —— 恢复已开启的提醒
 * 3. ACTION_QUICK_MOOD —— 用户在通知里点了某个表情，直接记录当前小时心情
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Reminder.ACTION_TICK -> handleTick(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Reminder.rescheduleIfEnabled(context)
            Reminder.ACTION_QUICK_MOOD -> handleQuickMood(context, intent)
        }
    }

    private fun handleTick(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val what = Reminder.onTick(context)
                android.util.Log.i(TAG, "整点触发：$what")
            } catch (e: Throwable) {
                // 任何异常都不能静默消失——记入日志，便于用 logcat 定位
                android.util.Log.e(TAG, "整点触发异常", e)
            } finally { pending.finish() }
        }
    }

    /** 在通知里点了表情：直接写库 + 取消通知，不跳转 app */
    private fun handleQuickMood(context: Context, intent: Intent) {
        val moodId = intent.getIntExtra(Reminder.EXTRA_MOOD_ID, 0)
        val hour = intent.getIntExtra(Reminder.EXTRA_HOUR, -1)
        val dateStr = intent.getStringExtra(Reminder.EXTRA_DATE)
        if (moodId == 0) return

        // 点击时刻必须在 onReceive 当场取：若挪到 IO 协程里再取，
        // 协程调度可能跨过整点/分钟边界（如 15:59:59 点击、16:00:01 才执行），
        // 记录就落到下一小时。当场取最接近真实点击瞬间。
        val nowDate = java.time.LocalDate.now()
        val nowTime = java.time.LocalTime.now()
        val nowHour = nowTime.hour
        val nowMinute = nowTime.minute

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 记录的时刻 = 用户点击的时刻（上面当场捕获的），
                // 而不是通知发送的时刻。
                // （EXTRA_HOUR/EXTRA_DATE 仍是发送时刻，仅供下方取消通知定位用）
                Reminder.recordQuickMood(context, nowHour, nowMinute, moodId, nowDate.toString())
                // 取消那条通知：必须用「发送时刻」算出的通知 id 才能取消到。
                // 极旧版本的通知没有这两个 extra（hour=-1）：跳过取消，
                // 但记录照常写——绝不能因为缺定位信息就不记录。
                if (dateStr != null && hour >= 0) {
                    Reminder.cancelQuickNotification(context, hour, dateStr)
                }
            } catch (e: Throwable) {
                // 与 handleTick 同样的保护：数据库异常绝不能崩掉接收器进程
                android.util.Log.e(TAG, "快速记录异常", e)
            } finally { pending.finish() }
        }
    }
}
