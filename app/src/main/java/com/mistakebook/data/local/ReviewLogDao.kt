package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mistakebook.data.local.entities.ReviewLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLog): Long

    @Query("SELECT * FROM review_logs WHERE questionId = :questionId ORDER BY reviewedAt DESC")
    fun observeOf(questionId: Long): Flow<List<ReviewLog>>

    @Query("SELECT COUNT(*) FROM review_logs WHERE questionId = :questionId")
    suspend fun countOf(questionId: Long): Int

    @Query("DELETE FROM review_logs WHERE questionId = :questionId")
    suspend fun clearOf(questionId: Long)

    /**
     * 周报聚合（F2）：某时刻以来的复习结果分布。
     * 复习闭环接活前恒为空列表——周报对"本周复习"一节自动显示「暂无数据」，
     * 不需要任何特判。
     */
    @Query(
        """
        SELECT result AS name, COUNT(*) AS count FROM review_logs
        WHERE reviewedAt >= :sinceMs GROUP BY result
        """
    )
    suspend fun resultCountsSince(sinceMs: Long): List<NameCount>

    /** 徽章统计（T10）：累计复习次数。 */
    @Query("SELECT COUNT(*) FROM review_logs")
    suspend fun totalCount(): Int

    /** 徽章统计（T10）：全部复习发生的时间戳（学习行为日期集合用）。 */
    @Query("SELECT reviewedAt FROM review_logs")
    suspend fun allReviewedDays(): List<Long>
}
