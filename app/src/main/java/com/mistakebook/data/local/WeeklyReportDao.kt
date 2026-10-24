package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mistakebook.data.local.entities.WeeklyReport
import kotlinx.coroutines.flow.Flow

@Dao
interface WeeklyReportDao {

    @Query("SELECT * FROM weekly_reports ORDER BY weekStartEpochDay DESC LIMIT 1")
    fun observeLatest(): Flow<WeeklyReport?>

    @Query("SELECT * FROM weekly_reports ORDER BY weekStartEpochDay DESC")
    fun observeAll(): Flow<List<WeeklyReport>>

    /**
     * 同周重生成覆盖：靠 weekStartEpochDay 唯一索引 + [OnConflictStrategy.REPLACE]。
     * 注意 REPLACE 会换主键 id——所有查询都按 weekStartEpochDay 排序，不依赖 id 稳定。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: WeeklyReport)
}
