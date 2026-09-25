package com.mooddiary.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [MoodStore]，用于在纯 JVM 上测试数据层。
 *
 * 刻意复刻真实 Room/SQLite 的两条关键行为，否则回归测试就失去意义：
 *  1. `(date, hour)` 唯一约束 —— 重复插入抛异常（对应 OnConflictStrategy.ABORT）
 *  2. 事务性 —— 块内抛异常时整体回滚
 */
class FakeMoodStore : MoodStore {
    private val state = MutableStateFlow<List<MoodEntry>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<MoodEntry>> =
        state.asStateFlow().map { list -> list.sortedWith(compareByDescending<MoodEntry> { it.date }.thenByDescending { it.hour }) }

    override suspend fun findByDateHour(date: String, hour: Int): MoodEntry? =
        state.value.firstOrNull { it.date == date && it.hour == hour }

    override suspend fun insert(entry: MoodEntry) {
        if (state.value.any { it.date == entry.date && it.hour == entry.hour }) {
            // 模拟 UNIQUE(date, hour) 冲突（ABORT 语义）
            throw IllegalStateException("UNIQUE constraint failed: mood_entries.date, mood_entries.hour")
        }
        state.value = state.value + entry.copy(id = nextId++)
    }

    override suspend fun update(entry: MoodEntry) {
        val idx = state.value.indexOfFirst { it.id == entry.id }
        require(idx >= 0) { "no entry with id ${entry.id}" }
        if (state.value.any { it.id != entry.id && it.date == entry.date && it.hour == entry.hour }) {
            throw IllegalStateException("UNIQUE constraint failed: mood_entries.date, mood_entries.hour")
        }
        state.value = state.value.toMutableList().also { it[idx] = entry }
    }

    override suspend fun delete(entry: MoodEntry) {
        state.value = state.value.filterNot { it.id == entry.id }
    }

    override suspend fun <R> transaction(block: suspend () -> R): R {
        val snapshot = state.value
        val snapshotId = nextId
        return try {
            block()
        } catch (e: Throwable) {
            state.value = snapshot
            nextId = snapshotId
            throw e
        }
    }
}
