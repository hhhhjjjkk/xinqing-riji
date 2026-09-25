package com.mooddiary.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收两类广播：
 * 1. [Reminder.ACTION_TICK] —— 到整点了，判断是否要发通知并续排下一次
 * 2. ACTION_BOOT_COMPLETED / ACTION_MY_PACKAGE_REPLACED —— 恢复已开启的提醒
 *
 * 用 goAsync + 协程：onReceive 在主线程，查数据库不能阻塞它。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Reminder.ACTION_TICK &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == Reminder.ACTION_TICK) {
                    Reminder.onTick(context)
                } else {
                    Reminder.rescheduleIfEnabled(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
