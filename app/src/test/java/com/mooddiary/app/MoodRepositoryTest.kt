package com.mooddiary.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 数据层回归测试。
 *
 * v3 起记录精确到**分钟**，且 `(date, hour)` 不再是唯一约束——
 * 同一小时内可以记多条（修复「一个小时内只能添加一次心情不合理」）。
 * 冲突判定因此改为精确到分钟：只有目标时刻已被**另一条**记录占用才冲突。
 */
class MoodRepositoryTest {

    private lateinit var repo: MoodRepository

    @Before
    fun setUp() {
        repo = MoodRepository(FakeMoodStore())
    }

    private val d1: LocalDate = LocalDate.of(2026, 9, 1)
    private val d2: LocalDate = LocalDate.of(2026, 9, 2)

    @Test
    fun `普通保存成功写入`() = runTest {
        val r = repo.save(d1, 14, 30, 5, "原始记录", null)
        assertTrue(r is SaveOutcome.Saved)
        assertEquals(1, repo.observeAll().first().size)
    }

    /** 核心回归：改日期撞上已有记录时必须报冲突，且不得删除那条记录。 */
    @Test
    fun `改日期撞到已有记录时返回冲突且不删除原记录`() = runTest {
        repo.save(d1, 14, 30, 5, "原始记录", null)
        repo.save(d2, 14, 30, 2, "另一天的记录", null)

        val original = repo.findByDateHour(d1, 14).first()
        // 模拟用户在编辑弹窗里把 9/1 那条的日期改成 9/2
        val outcome = repo.save(d2, 14, 30, 1, "改过的记录", original)

        assertTrue("应返回冲突而不是静默覆盖", outcome is SaveOutcome.Conflict)
        assertEquals(d2, LocalDate.parse((outcome as SaveOutcome.Conflict).existing.date))

        val all = repo.observeAll().first()
        assertEquals("冲突时不得删除任何记录", 2, all.size)
        assertNotNull("9/2 原有记录必须还在", all.firstOrNull { it.note == "另一天的记录" })
        assertNotNull("9/1 原记录必须还在", all.firstOrNull { it.note == "原始记录" })
    }

    /** 用户确认覆盖后，才允许替换目标时段的记录。 */
    @Test
    fun `确认覆盖后才替换目标记录`() = runTest {
        repo.save(d1, 14, 30, 5, "原始记录", null)
        repo.save(d2, 14, 30, 2, "另一天的记录", null)

        val original = repo.findByDateHour(d1, 14).first()
        val conflict = repo.findByDateHour(d2, 14).first()
        repo.overwrite(d2, 14, 30, 1, "改过的记录", original, conflict)

        // 被编辑的那条从 9/1 移动到 9/2，原来的 9/2 记录被删除，因此只剩 1 条
        val all = repo.observeAll().first()
        assertEquals("被覆盖的记录应被删除且原记录已移走", 1, all.size)
        assertNull("被覆盖的记录应消失", all.firstOrNull { it.note == "另一天的记录" })
        assertNull("原记录不应留在原日期", all.firstOrNull { it.note == "原始记录" })
        assertEquals("改过的记录", all.first { it.date == d2.toString() && it.hour == 14 }.note)
        assertTrue("原日期时段应被腾空", repo.findByDateHour(d1, 14).isEmpty())
    }

    /** 编辑自身记录（同一天同一小时同一分钟）不应被判定为冲突。 */
    @Test
    fun `编辑自身不产生冲突`() = runTest {
        repo.save(d1, 14, 30, 5, "原始记录", null)
        val original = repo.findByDateHour(d1, 14).first()

        val outcome = repo.save(d1, 14, 30, 1, "只是改了心情", original)

        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(1, repo.observeAll().first().size)
        assertEquals("只是改了心情", repo.findByDateHour(d1, 14).first().note)
    }

    /** 只改小时不改日期同样要走冲突检测（同一分钟才算撞车）。 */
    @Test
    fun `改小时撞到同一时刻也报冲突`() = runTest {
        repo.save(d1, 10, 0, 5, "十点的", null)
        repo.save(d1, 11, 0, 3, "十一点的", null)

        val ten = repo.findByDateHour(d1, 10).first()
        val outcome = repo.save(d1, 11, 0, 1, "挪到十一点", ten)

        assertTrue(outcome is SaveOutcome.Conflict)
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test
    fun `备注超长会被截断`() = runTest {
        repo.save(d1, 14, 0, 5, "x".repeat(MAX_NOTE_LENGTH + 200), null)
        assertEquals(MAX_NOTE_LENGTH, repo.findByDateHour(d1, 14).first().note.length)
    }

    @Test
    fun `删除记录`() = runTest {
        repo.save(d1, 14, 0, 5, "要删掉的", null)
        repo.delete(repo.findByDateHour(d1, 14).first())
        assertTrue(repo.observeAll().first().isEmpty())
    }

    @Test
    fun `一天可以记录满 24 小时`() = runTest {
        (0..23).forEach { h -> repo.save(d1, h, 0, 3, "", null) }
        assertEquals(24, repo.observeAll().first().size)
    }

    // ===== v3 新增：同一小时可记多条 =====

    /**
     * 用户反馈的核心：「一个小时内只能添加一次心情不合理」。
     * 同一小时内不同分钟必须能各自成条，互不冲突、互不覆盖。
     */
    @Test
    fun `同一小时内不同分钟可以各记一条`() = runTest {
        val r1 = repo.save(d1, 14, 5, 5, "十四点零五开心", null)
        val r2 = repo.save(d1, 14, 50, 1, "十四点五十低落", null)

        assertTrue(r1 is SaveOutcome.Saved)
        assertTrue(r2 is SaveOutcome.Saved)
        val all = repo.observeAll().first()
        assertEquals("同一小时的两条都要保留", 2, all.size)
        assertEquals(5, all.first { it.minute == 5 }.minute)
        assertEquals(50, all.first { it.minute == 50 }.minute)
    }

    /** 同一分钟才算撞车：目标时刻已被另一条占用时报冲突。 */
    @Test
    fun `同一分钟重复记录才报冲突`() = runTest {
        repo.save(d1, 14, 30, 5, "先记的", null)
        val outcome = repo.save(d1, 14, 30, 1, "同分钟又记的", null)
        assertTrue("同一分钟应报冲突", outcome is SaveOutcome.Conflict)
        assertEquals(1, repo.observeAll().first().size)
    }

    /** 编辑同小时其中一条，不得影响另一条。 */
    @Test
    fun `编辑一条不影响同小时的另一条`() = runTest {
        repo.save(d1, 14, 5, 5, "第一条", null)
        repo.save(d1, 14, 50, 1, "第二条", null)

        val first = repo.findByDateHour(d1, 14).first { it.minute == 5 }
        repo.save(d1, 14, 5, 3, "第一条改过了", first)

        val all = repo.observeAll().first()
        assertEquals(2, all.size)
        assertEquals("第一条改过了", all.first { it.minute == 5 }.note)
        assertEquals("第二条不受影响", "第二条", all.first { it.minute == 50 }.note)
    }

    /** 删除同小时其中一条，不得牵连另一条。 */
    @Test
    fun `删除一条不影响同小时的另一条`() = runTest {
        repo.save(d1, 14, 5, 5, "第一条", null)
        repo.save(d1, 14, 50, 1, "第二条", null)

        repo.delete(repo.findByDateHour(d1, 14).first { it.minute == 5 })

        val all = repo.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("第二条", all.first().note)
    }

    /** 覆盖时只删冲突的那一条，同小时其它分钟不受牵连。 */
    @Test
    fun `覆盖只删冲突的那一条`() = runTest {
        repo.save(d1, 14, 5, 5, "别动我", null)
        repo.save(d1, 14, 30, 5, "要被覆盖", null)

        val target = repo.findByDateHour(d1, 14).first { it.minute == 30 }
        repo.overwrite(d1, 14, 30, 1, "覆盖后的", null, target)

        val all = repo.observeAll().first()
        assertEquals(2, all.size)
        assertNotNull("同小时的另一条必须保留", all.firstOrNull { it.note == "别动我" })
    }

    /** 排序：同一天按小时、分钟倒序，便于按时间回看。 */
    @Test
    fun `排序按小时与分钟倒序`() = runTest {
        repo.save(d1, 9, 0, 5, "九点", null)
        repo.save(d1, 14, 5, 5, "十四点零五", null)
        repo.save(d1, 14, 50, 5, "十四点五十", null)

        val all = repo.observeAll().first()
        assertEquals(listOf(50, 5, 0), all.map { it.minute })
    }
}
