package com.mooddiary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 整点调度时间的计算：不依赖 Android，可在 JVM 上直接验证 */
class ReminderScheduleTest {

    private fun cal(hour: Int, minute: Int, second: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `整点触发时间对齐到下一个整点`() {
        val t = Reminder.nextHourMillis(cal(10, 30))
        val out = Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(11, out.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, out.get(Calendar.MINUTE))
        assertEquals(0, out.get(Calendar.SECOND))
        assertEquals(0, out.get(Calendar.MILLISECOND))
    }

    @Test
    fun `正点时跳到下一小时而不是立刻触发`() {
        val t = Reminder.nextHourMillis(cal(10, 0))
        val out = Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(11, out.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `23点后跨到第二天0点`() {
        val now = cal(23, 45)
        val before = now.get(Calendar.DAY_OF_MONTH)
        val out = Calendar.getInstance().apply { timeInMillis = Reminder.nextHourMillis(now) }
        assertEquals(0, out.get(Calendar.HOUR_OF_DAY))
        assertTrue("应跨到下一天", out.get(Calendar.DAY_OF_MONTH) != before ||
            out.get(Calendar.MONTH) != now.get(Calendar.MONTH))
    }

    @Test
    fun `入参不会被修改`() {
        val now = cal(10, 30)
        val hourBefore = now.get(Calendar.HOUR_OF_DAY)
        Reminder.nextHourMillis(now)
        assertEquals("nextHourMillis 不应改动传入的 Calendar", hourBefore, now.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `触发时间总是在当前时间之后`() {
        repeat(24) { h ->
            val now = cal(h, 59)
            assertTrue(Reminder.nextHourMillis(now) > now.timeInMillis)
        }
    }
}
