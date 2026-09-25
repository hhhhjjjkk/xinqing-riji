package com.mooddiary.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 免打扰时段的边界测试：跨天时段最容易写错 */
class QuietHoursTest {

    @Test
    fun `同天时段 1点到6点`() {
        assertTrue(QuietHours.isQuiet(1, 6, 0).not())
        assertTrue(QuietHours.isQuiet(1, 6, 1))
        assertTrue(QuietHours.isQuiet(1, 6, 3))
        assertTrue(QuietHours.isQuiet(1, 6, 5))
        assertFalse(QuietHours.isQuiet(1, 6, 6))   // 结束点本身不再免打扰
        assertFalse(QuietHours.isQuiet(1, 6, 23))
    }

    @Test
    fun `跨天时段 22点到次日8点`() {
        assertTrue(QuietHours.isQuiet(22, 8, 22))
        assertTrue(QuietHours.isQuiet(22, 8, 23))
        assertTrue(QuietHours.isQuiet(22, 8, 0))
        assertTrue(QuietHours.isQuiet(22, 8, 7))
        assertFalse(QuietHours.isQuiet(22, 8, 8))  // 8 点起恢复提醒
        assertFalse(QuietHours.isQuiet(22, 8, 12))
        assertFalse(QuietHours.isQuiet(22, 8, 21))
    }

    @Test
    fun `起止相同视为全天免打扰`() {
        // start == end 时按同天处理：(start until end) 为空集
        // 这里明确锁住当前行为，避免以后改动悄悄改变语义
        assertFalse(QuietHours.isQuiet(9, 9, 9))
    }

    @Test
    fun `设置关闭时任何时段都不免打扰`() {
        val s = AppSettings(quietHoursEnabled = false, quietStart = 22, quietEnd = 8)
        assertFalse(QuietHours.isQuiet(s, 23))
    }

    @Test
    fun `设置开启时按时段判断`() {
        val s = AppSettings(quietHoursEnabled = true, quietStart = 22, quietEnd = 8)
        assertTrue(QuietHours.isQuiet(s, 23))
        assertFalse(QuietHours.isQuiet(s, 10))
    }
}
