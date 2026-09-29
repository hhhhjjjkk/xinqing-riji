package com.mooddiary.app

import androidx.compose.ui.graphics.Color
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
    fun `分布按实际记录次数统计`() {
        // 26 日：10 点开心(5)、20 点生气(1)；27 日：开心(5)
        // 分布应按「记录次数」：开心 2 次、生气 1 次（共 3 条）
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 26), 10, 5),
            entry(LocalDate.of(2026, 9, 26), 20, 1),
            entry(LocalDate.of(2026, 9, 27), 8, 5)
        )
        val st = compute(entries)

        assertEquals(3, st.entryTotal)            // 共 3 条
        assertEquals(2, st.dayTotal)              // 共 2 天
        assertEquals(2, st.entryCounts[5])        // 开心 2 次
        assertEquals(1, st.entryCounts[1])        // 生气 1 次
        assertEquals(2, st.moodCounts[5])         // 开心占 2 天（每天最新一条口径另存）
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
        assertEquals(4, st.entryTotal)
        assertEquals(4, st.entryCounts.values.sum())
    }

    /** 整数百分比之和必须精确为 100（避免逐项取整导致的 99%） */
    @Test
    fun `整数百分比之和精确为一百`() {
        // 三等分：逐项 toInt 会得到 33+33+33=99
        val p = integerPercent(mapOf(5 to 1, 4 to 1, 3 to 1), 3)
        assertEquals(100, p.values.sum())

        // 7 条分成 3/2/2
        val p2 = integerPercent(mapOf(5 to 3, 4 to 2, 3 to 2), 7)
        assertEquals(100, p2.values.sum())

        // 单条
        val p3 = integerPercent(mapOf(5 to 1), 1)
        assertEquals(100, p3.values.sum())
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
    fun `趋势日均分与分布使用同一口径`() {
        // 同一天：10 点开心(5)、20 点生气(1)。当天最新一条是生气(1)，
        // 全页统一口径后，趋势分应为 1 而不是均值 3，
        // 这样趋势、分布、横幅三个数字之间才不会互相矛盾。
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 26), 10, 5),
            entry(LocalDate.of(2026, 9, 26), 20, 1)
        )
        val st = compute(entries)
        assertEquals(1, st.dailyScores.size)
        assertEquals(1.0f, st.dailyScores.first().second, 0.001f)
    }
}


class MoodFallbackTest {

    /** 失配 id 的兜底不能把所有记录归到同一个心情 */
    @Test
    fun `失配的历史id按分值就近回退而不是全部归到同一个`() {
        val custom = listOf(
            Mood(6, "甲", "🙂", Color(0xFF26A69A), 4),
            Mood(7, "乙", "🙂", Color(0xFF7E57C2), 3),
            Mood(8, "丙", "🙂", Color(0xFFEC407A), 2)
        )

        val mapped = listOf(5, 4, 3, 2, 1).map { moodOfIn(it, custom, defaultMoods) }

        // 旧逻辑会把 5 个全部归到乙（score==3 的那个）→ 分布 100%
        // 新逻辑按分值就近，至少应映射到 2 个以上不同的心情
        assertTrue("兜底把所有历史归到了同一个心情：${mapped.map { it.label }}",
            mapped.map { it.id }.distinct().size >= 2)
    }

    /** 正常命中不受影响 */
    @Test
    fun `目录中存在的id直接命中`() {
        val custom = listOf(Mood(9, "甲", "🙂", Color(0xFF26A69A), 5))
        assertEquals("甲", moodOfIn(9, custom, defaultMoods).label)
    }

    /** 目录为空时不抛异常 */
    @Test
    fun `目录为空时返回占位而不崩溃`() {
        val m = moodOfIn(5, emptyList(), defaultMoods)
        assertEquals("未知", m.label)
    }
}


class MoodDataRepairTest {

    /**
     * 一次性修复的映射必须**确定性**：同样的失配数据，
     * 无论跑多少次、哪个版本跑，得到的修正结果都相同。
     * （此前兜底规则逐版变化，导致统计数值随版本漂移。）
     */
    @Test
    fun `失配id的修复映射是确定性的`() {
        val catalog = listOf(
            Mood(6, "甲", "🙂", Color(0xFF26A69A), 4),
            Mood(7, "乙", "🙂", Color(0xFF7E57C2), 3),
            Mood(8, "丙", "🙂", Color(0xFFEC407A), 2)
        )
        val broken = listOf(5, 4, 3, 2, 1).map { id ->
            MoodEntry(date = "2026-09-26", hour = id, moodId = id)
        }

        // 修复 = moodOfIn(id, catalog).id
        val repaired = broken.map { e -> moodOfIn(e.moodId, catalog, defaultMoods).id }

        // 同一输入再算一遍，结果必须完全一致（幂等且确定）
        val again = broken.map { e -> moodOfIn(e.moodId, catalog, defaultMoods).id }
        assertEquals(repaired, again)

        // 修复后的 id 必须都存在于当前目录（数据不再失配）
        repaired.forEach { id ->
            assertTrue(catalog.any { it.id == id })
        }
    }

    /** 修复必须分散，不得把全部记录归到同一个心情 */
    @Test
    fun `修复后不得全部归到同一个心情`() {
        val catalog = listOf(
            Mood(6, "甲", "🙂", Color(0xFF26A69A), 4),
            Mood(7, "乙", "🙂", Color(0xFF7E57C2), 3),
            Mood(8, "丙", "🙂", Color(0xFFEC407A), 2)
        )
        val repaired = listOf(5, 4, 3, 2, 1).map { id -> moodOfIn(id, catalog, defaultMoods).id }
        assertTrue("修复把所有记录归到了同一项：${repaired.distinct()}",
            repaired.distinct().size >= 2)
    }
}
