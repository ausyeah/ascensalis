package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mistakebook.data.local.entities.Memory
import kotlinx.coroutines.flow.Flow

/**
 * 记忆库（v0.0.108）。
 *
 * 列表按「最近改过的在前」：用户刚说过要记住的东西，下次打开就能看到，
 * 这是「它记住了吗」最直接的确认方式。
 */
@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun allOnce(): List<Memory>

    @Insert
    suspend fun insert(memory: Memory): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()
}