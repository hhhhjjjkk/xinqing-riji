package com.mooddiary.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar

/**
 * 日历首列填充（paddingDays）回归测试。
 *
 * 曾经的事故：周一起始（MONDAY）模式下，padding 公式误用为
 * (dayOfWeek + 5) % 7 —— 这算出的是「周日起始」的列号，
 * 导致整月每一天都错位一天，日历上的星期与真实星期对不上。
 * 正确公式：MONDAY 模式下列号 = dayOfWeek - 1（周一=0 … 周日=6）。
 */
class CalendarPaddingTest {

    /** 与 MainActivity.CalendarPage 相同的填充计算 */
    private fun paddingDays(firstDay: LocalDate, weekStart: WeekStart): Int =
        if (weekStart == WeekStart.SUNDAY) {
            firstDay.dayOfWeek.value % 7
        } else {
            firstDay.dayOfWeek.value - 1
        }

    /**
     * 用 java.util.Calendar（标准库，独立实现）生成参考网格，
     * 与 App 的填充逻辑逐格比对。
     */
    private fun assertGridMatchesStandard(y: Int, m: Int, weekStart: WeekStart) {
        val ym = YearMonth.of(y, m)
        val firstDay = ym.atDay(1)
        val pad = paddingDays(firstDay, weekStart)

        val app = mutableListOf<String>()
        repeat(pad) { app.add("") }
        for (d in 1..ym.lengthOfMonth()) app.add(d.toString())
        while (app.size % 7 != 0) app.add("")

        val cal = Calendar.getInstance().apply {
            set(y, m - 1, 1)
            setFirstDayOfWeek(
                if (weekStart == WeekStart.SUNDAY) Calendar.SUNDAY else Calendar.MONDAY
            )
        }
        val std = mutableListOf<String>()
        // 从当月第一天所在周的第一天开始
        val start = firstDay.minus(((firstDay.dayOfWeek.value + 6) % 7).toLong())
            .let {
                // 调整为与 weekStart 对齐的本周第一天
                val offset = when (weekStart) {
                    WeekStart.SUNDAY -> (firstDay.dayOfWeek.value % 7).toLong()
                    else -> (firstDay.dayOfWeek.value - 1).toLong()
                }
                firstDay.minus(offset)
            }
        var cur = start
        while (cur <= ym.atEndOfMonth() || cur.dayOfWeek.value != (
            if (weekStart == WeekStart.SUNDAY) Calendar.SUNDAY else Calendar.MONDAY
            )
        ) {
            std.add(if (cur.monthValue == m) cur.dayOfMonth.toString() else "")
            cur = cur.plusDays(1)
        }
        while (std.size < app.size) std.add("")
        assertEquals("错位：$y-$m $weekStart", app, std.take(app.size))
    }

    @Test
    fun `周日起始的网格与真实日历一致`() {
        // 覆盖各年各月，包括 1 号恰好是周日的月份（padding=0）
        assertGridMatchesStandard(2026, 9, WeekStart.SUNDAY)  // 1号=周二
        assertGridMatchesStandard(2026, 11, WeekStart.SUNDAY) // 1号=周日
        assertGridMatchesStandard(2024, 2, WeekStart.SUNDAY)  // 闰年
    }

    @Test
    fun `周一起始的网格与真实日历一致`() {
        assertGridMatchesStandard(2026, 9, WeekStart.MONDAY)  // 1号=周二
        assertGridMatchesStandard(2026, 10, WeekStart.MONDAY) // 1号=周四
        assertGridMatchesStandard(2025, 2, WeekStart.MONDAY)  // 1号=周六
    }

    @Test
    fun `2024至2026全部月份两种周起始均不错位`() {
        for (y in 2024..2026) {
            for (m in 1..12) {
                assertGridMatchesStandard(y, m, WeekStart.SUNDAY)
                assertGridMatchesStandard(y, m, WeekStart.MONDAY)
            }
        }
    }
}
