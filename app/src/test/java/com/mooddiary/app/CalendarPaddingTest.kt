package com.mooddiary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

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

    /** 生成 App 的网格（含首尾空位），并断言每个日期都落在正确的星期列上 */
    private fun assertGridMatchesStandard(y: Int, m: Int, weekStart: WeekStart) {
        val ym = YearMonth.of(y, m)
        val firstDay = ym.atDay(1)
        val pad = paddingDays(firstDay, weekStart)

        val app = mutableListOf<String>()
        repeat(pad) { app.add("") }
        for (d in 1..ym.lengthOfMonth()) app.add(d.toString())
        while (app.size % 7 != 0) app.add("")

        // 列对应的星期（表头）
        val dowOfCol: List<DayOfWeek> =
            if (weekStart == WeekStart.SUNDAY)
                listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                       DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
            else
                DayOfWeek.entries.toList()   // 周一起始：一到日

        // 逐格断言：非空格子的日期，其真实星期必须等于所在列的表头星期
        for ((idx, cell) in app.withIndex()) {
            if (cell.isBlank()) continue
            val day = cell.toInt()
            val date = ym.atDay(day)
            val col = idx % 7
            assertEquals(
                "$y-$m 第 $day 日应落在「${dowOfCol[col]}」列",
                dowOfCol[col], date.dayOfWeek
            )
        }
        // 空格只能出现在首尾，不能夹在日期中间
        val firstReal = app.indexOfFirst { it.isNotBlank() }
        val lastReal = app.indexOfLast { it.isNotBlank() }
        assertTrue(
            "$y-$m $weekStart 网格中间出现空位",
            app.subList(firstReal, lastReal + 1).none { it.isBlank() }
        )
        // 首列填充数不能为负，也不能 ≥ 7
        assertTrue(pad in 0..6)
    }

    @Test
    fun `周日起始的网格与真实日历一致`() {
        assertGridMatchesStandard(2026, 9, WeekStart.SUNDAY)   // 1号=周二
        assertGridMatchesStandard(2026, 11, WeekStart.SUNDAY)  // 1号=周日（padding=0）
        assertGridMatchesStandard(2024, 2, WeekStart.SUNDAY)   // 闰年
    }

    @Test
    fun `周一起始的网格与真实日历一致`() {
        assertGridMatchesStandard(2026, 9, WeekStart.MONDAY)   // 1号=周二
        assertGridMatchesStandard(2026, 10, WeekStart.MONDAY)  // 1号=周四
        assertGridMatchesStandard(2025, 2, WeekStart.MONDAY)   // 1号=周六
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
