package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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
 * 所有 LIKE 都带 `ESCAPE '\'`（T9）：关键词里的 `%`/`_` 不转义的话
 * 会被当通配符——搜「100%」命中一切含「100」的题，「_」匹配任意单字。
 * 仓库层传参前先经 escapeLike 把 `\`→`\\`、`%`→`\%`、`_`→`\_`。
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
            OR stem LIKE '%' || :keyword || '%' ESCAPE '\'
            OR title LIKE '%' || :keyword || '%' ESCAPE '\'
            OR answer LIKE '%' || :keyword || '%' ESCAPE '\'
            OR analysis LIKE '%' || :keyword || '%' ESCAPE '\'
            OR optionsJson LIKE '%' || :keyword || '%' ESCAPE '\'
            OR note LIKE '%' || :keyword || '%' ESCAPE '\'
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
            OR stem LIKE '%' || :keyword || '%' ESCAPE '\'
            OR title LIKE '%' || :keyword || '%' ESCAPE '\'
            OR answer LIKE '%' || :keyword || '%' ESCAPE '\'
            OR analysis LIKE '%' || :keyword || '%' ESCAPE '\'
            OR optionsJson LIKE '%' || :keyword || '%' ESCAPE '\'
            OR note LIKE '%' || :keyword || '%' ESCAPE '\'
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

    @Insert
    suspend fun insert(question: Question): Long

    @Update
    suspend fun update(question: Question)

    /**
     * 复习结果原子落库（T1）：一条 review_log + 一次 question 推进，
     * 任一失败整体回滚。原来这两步是分开写的死代码（ReviewRules 全库无调用方），
     * 复习状态从不推进、每日提醒永远报全量——闭环接回来之后必须保证
     * 「记录」与「推进」要么都成功要么都不发生，否则会出现日志有了、
     * 队列没变的半更新状态。
     */
    @Transaction
    suspend fun applyReviewResult(log: com.mistakebook.data.local.entities.ReviewLog, updated: Question) {
        insertReviewLog(log)
        update(updated)
    }

    @Insert
    abstract suspend fun insertReviewLog(log: com.mistakebook.data.local.entities.ReviewLog)

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
