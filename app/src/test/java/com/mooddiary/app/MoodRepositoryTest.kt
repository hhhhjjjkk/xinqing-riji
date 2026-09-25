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
 * 针对「改日期静默删除他人记录」这一历史 Bug 的回归测试。
 *
 * 历史实现：DAO 用 OnConflictStrategy.REPLACE + (date, hour) 唯一索引，
 * 而编辑弹窗允许改日期。把 9/1 14:00 的记录改成 9/2 14:00 时，
 * SQLite 会先删除 9/2 那条再插入，用户数据被无声抹掉。
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
        val r = repo.save(d1, 14, 5, "原始记录", null)
        assertTrue(r is SaveOutcome.Saved)
        assertEquals(1, repo.observeAll().first().size)
    }

    /** 核心回归：改日期撞上已有记录时必须报冲突，且不得删除那条记录。 */
    @Test
    fun `改日期撞到已有记录时返回冲突且不删除原记录`() = runTest {
        repo.save(d1, 14, 5, "原始记录", null)
        repo.save(d2, 14, 2, "另一天的记录", null)

        val original = repo.findByDateHour(d1, 14)!!
        // 模拟用户在编辑弹窗里把 9/1 那条的日期改成 9/2
        val outcome = repo.save(d2, 14, 1, "改过的记录", original)

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
        repo.save(d1, 14, 5, "原始记录", null)
        repo.save(d2, 14, 2, "另一天的记录", null)

        val original = repo.findByDateHour(d1, 14)!!
        val conflict = repo.findByDateHour(d2, 14)!!
        repo.overwrite(d2, 14, 1, "改过的记录", original, conflict)

        // 被编辑的那条从 9/1 移动到 9/2，原来的 9/2 记录被删除，因此只剩 1 条
        val all = repo.observeAll().first()
        assertEquals("被覆盖的记录应被删除且原记录已移走", 1, all.size)
        assertNull("被覆盖的记录应消失", all.firstOrNull { it.note == "另一天的记录" })
        assertNull("原记录不应留在原日期", all.firstOrNull { it.note == "原始记录" })
        assertEquals("改过的记录", all.first { it.date == d2.toString() && it.hour == 14 }.note)
        assertNull("原日期时段应被腾空", repo.findByDateHour(d1, 14))
    }

    /** 编辑自身记录（同一天同一小时）不应被判定为冲突。 */
    @Test
    fun `编辑自身不产生冲突`() = runTest {
        repo.save(d1, 14, 5, "原始记录", null)
        val original = repo.findByDateHour(d1, 14)!!

        val outcome = repo.save(d1, 14, 1, "只是改了心情", original)

        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(1, repo.observeAll().first().size)
        assertEquals("只是改了心情", repo.findByDateHour(d1, 14)!!.note)
    }

    /** 只改小时不改日期同样要走冲突检测。 */
    @Test
    fun `改小时撞到已有记录也报冲突`() = runTest {
        repo.save(d1, 10, 5, "十点的", null)
        repo.save(d1, 11, 3, "十一点的", null)

        val ten = repo.findByDateHour(d1, 10)!!
        val outcome = repo.save(d1, 11, 1, "挪到十一点", ten)

        assertTrue(outcome is SaveOutcome.Conflict)
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test
    fun `备注超长会被截断`() = runTest {
        repo.save(d1, 14, 5, "x".repeat(MAX_NOTE_LENGTH + 200), null)
        assertEquals(MAX_NOTE_LENGTH, repo.findByDateHour(d1, 14)!!.note.length)
    }

    @Test
    fun `删除记录`() = runTest {
        repo.save(d1, 14, 5, "要删掉的", null)
        repo.delete(repo.findByDateHour(d1, 14)!!)
        assertTrue(repo.observeAll().first().isEmpty())
    }

    @Test
    fun `一天可以记录满 24 小时`() = runTest {
        (0..23).forEach { h -> repo.save(d1, h, 3, "", null) }
        assertEquals(24, repo.observeAll().first().size)
        // 第 25 条必然撞车
        assertTrue(repo.save(d1, 0, 5, "重复", null) is SaveOutcome.Conflict)
    }
}
