package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.Question
import kotlinx.coroutines.flow.Flow

/**
 * 错题查询。
 *
 * 约定：statusName / reasonName 传枚举 name（与 TypeConverter 写入库中的值一致），
 * subjectFilter 传 0 表示不限学科，keyword 传空串表示不搜索。
 *
 * ## 搜索范围
 *
 * 搜**题干、标题、答案、解析、选项 JSON、笔记**六处。
 *
 * 早先只搜 `stem`，于是两类常用场景都搜不到：
 * - 「这题的答案是 B」——题干里根本没有「B」这个答案
 * - 「三角函数」——那是自动生成的 `title`，题干写的是题目原文
 * - 搜解析里才出现的概念（如「拉格朗日乘数」）
 *
 * `optionsJson` 直接 LIKE 也能命中选项文字：存的是 JSON 数组，
 * 里面是选项的原文，LIKE 是子串匹配，用户搜「常数」就能命中「C. 常数」。
 * 代价是可能匹配到转义字符——那只是多搜出几条，不是漏搜。
 *
 * **两处查询必须同步改**（列表与计数）。只改一处的话，
 * 列表显示 3 条而角标写「共 12 条」，用户会以为漏了几条。
 */
@Dao
interface QuestionDao {

    @Query(
        """
        SELECT * FROM questions
        WHERE deletedAt IS NULL
          AND (:statusName = '' OR status = :statusName)
          AND (:subjectFilter = 0 OR subjectId = :subjectFilter)
          AND (:notebookFilter = 0 OR notebookId = :notebookFilter)
          AND (:reasonName = '' OR errorReason = :reasonName)
          AND (
            :keyword = ''
            OR stem LIKE '%' || :keyword || '%'
            OR title LIKE '%' || :keyword || '%'
            OR answer LIKE '%' || :keyword || '%'
            OR analysis LIKE '%' || :keyword || '%'
            OR optionsJson LIKE '%' || :keyword || '%'
            OR note LIKE '%' || :keyword || '%'
          )
        ORDER BY updatedAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observePage(
        statusName: String,
        subjectFilter: Long,
        notebookFilter: Long,
        reasonName: String,
        keyword: String,
        limit: Int,
        offset: Int
    ): Flow<List<Question>>

    @Query(
        """
        SELECT COUNT(*) FROM questions
        WHERE deletedAt IS NULL
          AND (:statusName = '' OR status = :statusName)
          AND (:subjectFilter = 0 OR subjectId = :subjectFilter)
          AND (:notebookFilter = 0 OR notebookId = :notebookFilter)
          AND (:reasonName = '' OR errorReason = :reasonName)
          AND (
            :keyword = ''
            OR stem LIKE '%' || :keyword || '%'
            OR title LIKE '%' || :keyword || '%'
            OR answer LIKE '%' || :keyword || '%'
            OR analysis LIKE '%' || :keyword || '%'
            OR optionsJson LIKE '%' || :keyword || '%'
            OR note LIKE '%' || :keyword || '%'
          )
        """
    )
    fun observeFilteredCount(
        statusName: String,
        subjectFilter: Long,
        notebookFilter: Long,
        reasonName: String,
        keyword: String
    ): Flow<Int>

    @Query("SELECT * FROM questions WHERE id = :id")
    fun observeById(id: Long): Flow<Question?>

    // 待复习列表（首页「待复习」筛选与通知共用）
    @Query(
        """
        SELECT * FROM questions
        WHERE deletedAt IS NULL AND status != 'MASTERED' AND nextReviewAt <= :today
        ORDER BY nextReviewAt
        """
    )
    fun observeDue(today: Long): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun findById(id: Long): Question?

    /**
     * 只取 id + title，给「对话列表」标注每段对话属于哪道题。
     *
     * 不返回整个 [Question]：题干、解析、原图路径全都不需要，
     * 而会话列表每次重组都会重新发射一次全表。
     */
    @Query("SELECT id, title FROM questions WHERE deletedAt IS NULL")
    fun observeTitles(): Flow<List<QuestionTitleRow>>

    @Query(
        """
        SELECT * FROM questions
        WHERE deletedAt IS NULL AND status != 'MASTERED' AND nextReviewAt <= :today
        ORDER BY nextReviewAt
        LIMIT :limit
        """
    )
    suspend fun dueForReview(today: Long, limit: Int): List<Question>

    @Query(
        """
        SELECT COUNT(*) FROM questions
        WHERE deletedAt IS NULL AND status != 'MASTERED' AND nextReviewAt <= :today
        """
    )
    fun observeDueCount(today: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND subjectId = :subjectId")
    fun observeCountBySubject(subjectId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND errorReason = :reasonName")
    fun observeCountByReason(reasonName: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND subjectId = :subjectId")
    suspend fun countBySubject(subjectId: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND errorReason = :reasonName")
    suspend fun countByReason(reasonName: String): Int

    // ===== 周报聚合（F2）：一次性 suspend 查询，报表生成时才跑，不做 Flow =====

    @Query(
        """
        SELECT errorReason AS name, COUNT(*) AS count FROM questions
        WHERE deletedAt IS NULL GROUP BY errorReason
        """
    )
    suspend fun reasonDistribution(): List<NameCount>

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND createdAt >= :sinceMs")
    suspend fun countCreatedSince(sinceMs: Long): Int

    @Query("SELECT COUNT(*) FROM questions WHERE deletedAt IS NULL AND status = 'MASTERED'")
    suspend fun countMastered(): Int

    /**
     * 拉全部未删题的知识点 JSON 做频次统计。知识点存在 JSON 数组里，
     * SQL 聚合不了；千级题量下一次加载再在 Kotlin 里数，开销可忽略。
     */
    @Query("SELECT knowledgePointsJson FROM questions WHERE deletedAt IS NULL AND knowledgePointsJson != '[]'")
    suspend fun allKnowledgePointsJson(): List<String>

    /**
     * 考前排期（F3）：只挪 nextReviewAt，不动轮次与掌握状态——
     * 排期是"什么时候见它"，不是"学得怎么样"。
     */
    @Query("UPDATE questions SET nextReviewAt = :epochDay, updatedAt = :now WHERE id = :id")
    suspend fun updateNextReviewAt(id: Long, epochDay: Long, now: Long)

    @Insert
    suspend fun insert(question: Question): Long

    @Update
    suspend fun update(question: Question)

    @Query("UPDATE questions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE questions SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("SELECT * FROM questions WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun listDeletedBefore(before: Long): List<Question>

    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<Long>)
}

/**
 * [QuestionDao.observeTitles] 的行类型。
 *
 * 单独定义而不是复用 [Question]：查询只投影两列，
 * Room 会按这个类的字段名校验，列名必须一致。
 */
data class QuestionTitleRow(
    val id: Long,
    val title: String
)

/**
 * GROUP BY 计数行（周报聚合用）。字段名 `name`/`count`
 * 必须与 SQL 里的 `AS name, COUNT(*) AS count` 投影一致。
 */
data class NameCount(
    val name: String,
    val count: Int
)
