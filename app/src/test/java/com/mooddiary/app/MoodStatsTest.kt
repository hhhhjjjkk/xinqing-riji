package com.mooddiary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页的口径回归测试。
 *
 * 此前的缺陷：分布百分比以「每天最新一条」为分母，
 * 而分子用的是「全部记录」的计数，两者口径不一致，导致百分比失真；
 * 另外 0% 的进度条被强制画成 1% 宽度，出现残留。
 */
class MoodStatsTest {

    private val month: YearMonth = YearMonth.of(2026, 9)

    private fun entry(date: LocalDate, hour: Int, moodId: Int) =
        MoodEntry(date = date.toString(), hour = hour, moodId = moodId)

    private fun compute(entries: List<MoodEntry>) = computeMonthStats(entries, month)

    @Test
    fun `分布分子与分母使用同一口径`() {
        // 26 日：10 点记录开心(5)，20 点记录生气(1) → 当天最新为生气
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 26), 10, 5),
            entry(LocalDate.of(2026, 9, 26), 20, 1)
        )
        val st = compute(entries)

        assertEquals(1, st.latestPerDay.size)                 // 一天只算一条
        assertEquals(1, st.moodCounts[1])                     // 生气 1 天
        assertEquals(null, st.moodCounts[5])                  // 被覆盖的开心不参与分布
        assertTrue(st.moodCounts.values.sum() == st.latestPerDay.size)
    }

    @Test
    fun `所有占比之和为百分之百`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 1), 8, 5),
            entry(LocalDate.of(2026, 9, 2), 9, 4),
            entry(LocalDate.of(2026, 9, 3), 10, 5),
            entry(LocalDate.of(2026, 9, 4), 11, 3)
        )
        val st = compute(entries)
        assertEquals(4, st.latestPerDay.size)
        assertEquals(4, st.moodCounts.values.sum())
    }

    @Test
    fun `跨月记录不计入当月统计`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 8, 31), 10, 5),   // 上月
            entry(LocalDate.of(2026, 9, 15), 10, 4)
        )
        val st = compute(entries)
        assertEquals(1, st.inMonth.size)
        assertEquals(1, st.latestPerDay.size)
    }

    @Test
    fun `无记录时各项为空且不产生除零`() {
        val st = compute(emptyList())
        assertEquals(0, st.inMonth.size)
        assertEquals(0, st.latestPerDay.size)
        assertEquals(0, st.dailyScores.size)
        assertEquals(0, st.moodCounts.size)
    }

    @Test
    fun `日均分使用当天全部记录而非仅最后一条`() {
        // 同一天：开心(5) 与 生气(1) → 日均分 3.0
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 26), 10, 5),
            entry(LocalDate.of(2026, 9, 26), 20, 1)
        )
        val st = compute(entries)
        assertEquals(1, st.dailyScores.size)
        assertEquals(3.0f, st.dailyScores.first().second, 0.001f)
    }
}
