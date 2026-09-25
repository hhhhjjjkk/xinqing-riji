package com.mooddiary.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            try { Reminder.onTick(context) }
            finally { pending.finish() }
        }
    }

    /** 在通知里点了表情：直接写库 + 取消通知，不跳转 app */
    private fun handleQuickMood(context: Context, intent: Intent) {
        val moodId = intent.getIntExtra(Reminder.EXTRA_MOOD_ID, 0)
        val hour = intent.getIntExtra(Reminder.EXTRA_HOUR, -1)
        if (moodId == 0 || hour < 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminder.recordQuickMood(context, hour, moodId)
            } finally {
                pending.finish()
            }
        }
    }
}
