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

    /**
     * 按筛选条件取**全量**未删题（v0.0.59 起不再分页）。
     *
     * ## 为什么去掉 LIMIT/OFFSET
     *
     * 首页曾经按页翻（30 条/页），但 StateFlow 只持当前页：触底加载会把
     * 整个列表**替换**成下一页而不是追加，超过 30 题后滚动体验直接坏掉。
     * 打印页更是早已用 pageSize=Int.MAX_VALUE 绕过分页拿全量——分页没有
     * 任何真实消费者。个人题库千级量级，全量查询 + LazyColumn 惰性组合
     * 完全够用（与 allKnowledgePointsJson 的「千级一次加载」同口径）。
     *
     * **两处查询必须同步改**（列表与计数）。只改一处的话，
     * 列表显示 3 条而角标写「共 12 条」，用户会以为漏了几条。
     */
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
        """
    )
    fun observeFiltered(
        statusName: String,
        subjectFilter: Long,
        notebookFilter: Long,
        reasonName: String,
        keyword: String
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

    // ===== 错题统计页（F5）聚合 =====

    @Query(
        """
        SELECT subjectId AS subjectId, COUNT(*) AS count FROM questions
        WHERE deletedAt IS NULL GROUP BY subjectId
        """
    )
    suspend fun subjectDistribution(): List<SubjectCountRow>

    @Query(
        """
        SELECT CAST(difficulty AS TEXT) AS name, COUNT(*) AS count FROM questions
        WHERE deletedAt IS NULL GROUP BY difficulty ORDER BY difficulty
        """
    )
    suspend fun difficultyDistribution(): List<NameCount>

    /** Anki 导出（F7）：全部未删除的题。导出场景一次性拉全量，不分页。 */
    @Query("SELECT * FROM questions WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    suspend fun allActive(): List<Question>

    /**
     * 思维导图（F4）用：按学科过滤的知识点 JSON 清单。
     * [subjectFilter] 传 0 表示不限学科（与列表查询的约定一致）。
     */
    @Query(
        """
        SELECT knowledgePointsJson FROM questions
        WHERE deletedAt IS NULL AND knowledgePointsJson != '[]'
          AND (:subjectFilter = 0 OR subjectId = :subjectFilter)
        """
    )
    suspend fun allKnowledgePointsJsonOf(subjectFilter: Long): List<String>

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

    /**
     * 物理删除（回收站清理）。**必须连带清 question_tags**：
     * 关联表没有外键（多对多的标准做法），只删 questions 的话，
     * 标签关联行会永久残留——tags 列表里出现一批零引用的孤儿标签，
     * 越积越多。review_logs 刻意保留：它们是「曾经学过」的历史统计。
     */
    @Transaction
    suspend fun hardDelete(ids: List<Long>) {
        deleteTagCrossRefs(ids)
        deleteQuestionsByIds(ids)
    }

    @Query("DELETE FROM question_tags WHERE questionId IN (:ids)")
    suspend fun deleteTagCrossRefs(ids: List<Long>)

    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun deleteQuestionsByIds(ids: List<Long>)
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

/** 学科分布行（统计页用）：subjectId 可空（未分学科的题）。 */
data class SubjectCountRow(
    val subjectId: Long?,
    val count: Int
)
