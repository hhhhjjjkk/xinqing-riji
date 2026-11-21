package com.mooddiary.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [MoodStore]，用于在纯 JVM 上测试数据层。
 *
 * 刻意复刻真实 Room/SQLite 的关键行为，否则回归测试就失去意义：
 *  1. 事务性 —— 块内抛异常时整体回滚
 *
 * 注意：v3 起 `mood_entries` **不再有 (date, hour) 唯一约束**，
 * 同一小时内可以有多条记录（这正是「一个小时内只能记一次不合理」的修复点），
 * 所以这里不再模拟 UNIQUE 冲突。
 */
class FakeMoodStore : MoodStore {
    private val state = MutableStateFlow<List<MoodEntry>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<MoodEntry>> =
        state.asStateFlow().map { list ->
            list.sortedWith(
                compareByDescending<MoodEntry> { it.date }
                    .thenByDescending { it.hour }
                    .thenByDescending { it.minute }
                    .thenByDescending { it.id }
            )
        }

    override suspend fun findByDateHour(date: String, hour: Int): List<MoodEntry> =
        state.value.filter { it.date == date && it.hour == hour }
            .sortedWith(compareBy<MoodEntry> { it.minute }.thenBy { it.id })

    override suspend fun findExact(date: String, hour: Int, minute: Int): MoodEntry? =
        state.value.firstOrNull { it.date == date && it.hour == hour && it.minute == minute }

    override suspend fun insert(entry: MoodEntry) {
        state.value = state.value + entry.copy(id = nextId++)
    }

    override suspend fun update(entry: MoodEntry) {
        val idx = state.value.indexOfFirst { it.id == entry.id }
        require(idx >= 0) { "no entry with id ${entry.id}" }
        state.value = state.value.toMutableList().also { it[idx] = entry }
    }

    override suspend fun delete(entry: MoodEntry) {
        state.value = state.value.filterNot { it.id == entry.id }
    }

    override suspend fun deleteAll() {
        state.value = emptyList()
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
