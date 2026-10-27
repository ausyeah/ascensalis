package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mistakebook.data.local.entities.BadgeRecord
import kotlinx.coroutines.flow.Flow

/** 成就徽章解锁记录（T10）。 */
@Dao
interface BadgeDao {

    @Query("SELECT * FROM badges ORDER BY unlockedAt ASC")
    fun observeAll(): Flow<List<BadgeRecord>>

    @Query("SELECT * FROM badges")
    suspend fun all(): List<BadgeRecord>

    @Query("SELECT COUNT(*) FROM badges")
    fun observeUnlockedCount(): Flow<Int>

    /** 返回是否新插入（已存在则忽略，由判定侧据此判断「新解锁」）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: BadgeRecord): Long
}