package com.mistakebook.data.repos

import com.mistakebook.data.local.QuestionDao
import com.mistakebook.data.local.ReviewLogDao
import com.mistakebook.data.local.SubjectDao
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.entities.ReviewLog
import com.mistakebook.data.local.figurePaths
import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.MasteryStatus
import com.mistakebook.domain.Option
import com.mistakebook.domain.QuestionDraft
import com.mistakebook.domain.ReviewResult
import com.mistakebook.domain.ReviewRules
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

class QuestionRepository(
    private val questionDao: QuestionDao,
    private val reviewLogDao: ReviewLogDao,
    private val subjectDao: SubjectDao,
    private val tagRepository: TagRepository
) {

    data class Filter(
        val status: MasteryStatus? = null,
        val subjectId: Long? = null,
        /** 错题本筛选；null 表示不限。与学科正交。 */
        val notebookId: Long? = null,
        val reason: ErrorReason? = null,
        val keyword: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun observePage(filter: Filter, page: Int, pageSize: Int = PAGE_SIZE): Flow<List<Question>> =
        questionDao.observePage(
            statusName = filter.status?.name.orEmpty(),
            subjectFilter = filter.subjectId ?: 0L,
            notebookFilter = filter.notebookId ?: 0L,
            reasonName = filter.reason?.name.orEmpty(),
            keyword = escapeLike(filter.keyword.trim()),
            limit = pageSize,
            offset = page.coerceAtLeast(0) * pageSize
        )

    fun observeCount(filter: Filter): Flow<Int> =
        questionDao.observeFilteredCount(
            statusName = filter.status?.name.orEmpty(),
            subjectFilter = filter.subjectId ?: 0L,
            notebookFilter = filter.notebookId ?: 0L,
            reasonName = filter.reason?.name.orEmpty(),
            keyword = escapeLike(filter.keyword.trim())
        )

    fun observeTotalCount(): Flow<Int> = questionDao.observeTotalCount()

    fun observeDue(today: java.time.LocalDate): Flow<List<Question>> =
        questionDao.observeDue(today.toEpochDay())

    fun observeDueCount(today: LocalDate): Flow<Int> =
        questionDao.observeDueCount(today.toEpochDay())

    fun observeById(id: Long): Flow<Question?> = questionDao.observeById(id)

    fun observeLogs(id: Long): Flow<List<ReviewLog>> = reviewLogDao.observeOf(id)

    suspend fun findById(id: Long): Question? = questionDao.findById(id)

    suspend fun dueQuestions(today: LocalDate, limit: Int = 50): List<Question> =
        questionDao.dueForReview(today.toEpochDay(), limit)

    /** 徽章统计（T10）：全部未删除错题（一次读全量，聚合在仓库层做）。 */
    suspend fun allActiveQuestions(): List<Question> = questionDao.allActive()

    suspend fun countBySubject(subjectId: Long): Int = questionDao.countBySubject(subjectId)

    suspend fun countByReason(reason: ErrorReason): Int = questionDao.countByReason(reason.name)

    /** 保存一道新题；新知识点自动建 Tag 并关联。 */
    suspend fun save(draft: QuestionDraft, now: Long): Long {
        val subjectId = resolveSubjectId(draft)
        val today = LocalDate.now()
        val question = Question(
            id = 0,
            subjectId = subjectId,
            notebookId = draft.notebookId,
            imagePath = draft.imagePath,
            figurePathsJson = json.encodeToString(
                draft.figurePaths.map { it.trim() }.filter { it.isNotEmpty() }
            ),
            mineruMarkdown = draft.mineruMarkdown,
            stem = draft.stem.trim(),
            optionsJson = json.encodeToString(draft.options),
            answer = draft.answer.trim(),
            analysis = draft.analysis.trim(),
            title = draft.title.trim(),
            knowledgePointsJson = json.encodeToString(draft.knowledgePoints.map { it.trim() }.filter { it.isNotEmpty() }),
            errorReason = draft.errorReason,
            difficulty = draft.difficulty.coerceIn(1, 5),
            status = MasteryStatus.ACTIVE,
            note = draft.note.trim(),
            reviewStage = 0,
            nextReviewAt = today.toEpochDay(),
            createdAt = now,
            updatedAt = now
        )
        val id = questionDao.insert(question)
        syncTags(id, draft.knowledgePoints)
        return id
    }

    /**
     * 保存一道 AI 变式题（F1 举一反三），并与来源题关联。
     *
     * 复用 [save] 的全部入库规则（学科解析、标签同步、复习排期初值）；
     * 这里只补变式特有的两件事：错题本归属继承来源题、回填 relatedQuestionId。
     * 变式题没有原图——调用方传 imagePath 空串（与手动录入同一守卫约定）。
     */
    suspend fun saveVariant(origin: Question, draft: QuestionDraft, now: Long): Long {
        val id = save(draft.copy(notebookId = draft.notebookId ?: origin.notebookId), now)
        // 关联在 save() 产物上补一笔：save 不感知变式语义，职责保持单一
        questionDao.findById(id)?.let { saved ->
            questionDao.update(saved.copy(relatedQuestionId = origin.id))
        }
        return id
    }

    // 编辑已有题目：题干/答案等字段由调用方写进 question，这里只负责收尾与标签同步。
    suspend fun update(question: Question, knowledgePoints: List<String>) {
        val updated = question.copy(
            stem = question.stem.trim(),
            answer = question.answer.trim(),
            analysis = question.analysis.trim(),
            title = question.title.trim(),
            figurePathsJson = json.encodeToString(question.figurePaths),
            knowledgePointsJson = json.encodeToString(
                knowledgePoints.map { it.trim() }.filter { it.isNotEmpty() }
            ),
            difficulty = question.difficulty.coerceIn(1, 5),
            updatedAt = System.currentTimeMillis()
        )
        questionDao.update(updated)
        syncTags(question.id, knowledgePoints)
    }

    /** 单独回填题目附图（详情页手动加/删附图时用）。 */
    suspend fun updateFigures(id: Long, figurePaths: List<String>) {
        val question = questionDao.findById(id) ?: return
        questionDao.update(
            question.copy(
                figurePathsJson = json.encodeToString(figurePaths),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 单独回填题目标题（列表页展示用）。 */
    suspend fun updateTitle(id: Long, title: String) {
        val question = questionDao.findById(id) ?: return
        questionDao.update(
            question.copy(title = title.trim(), updatedAt = System.currentTimeMillis())
        )
    }

    /**
     * 重新裁切后换掉封面/显示图：**只改 imagePath，不动题干内容**。
     *
     * 重新裁切的语义就是「图裁得不好，重裁一张」——识别结果保持不变。
     * 重新识别（先裁切再提交）走的是另一条路：裁完先调这里换图，
     * 再交给 RecognitionSubmitter 覆盖内容。
     */
    suspend fun updateImagePath(id: Long, imagePath: String) {
        val question = questionDao.findById(id) ?: return
        questionDao.update(
            question.copy(imagePath = imagePath, updatedAt = System.currentTimeMillis())
        )
    }

    /** 单独改难度。大模型给的星级只是初值，用户有最终判断权。 */
    suspend fun setDifficulty(id: Long, difficulty: Int) {
        val question = questionDao.findById(id) ?: return
        questionDao.update(
            question.copy(
                difficulty = difficulty.coerceIn(1, 5),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 重新识别后覆盖题目内容。
     * 调用方负责保留 id / 掌握状态 / 复习排期，这里只写内容相关字段与附图。
     */
    suspend fun overwrite(
        question: Question,
        figurePaths: List<String>,
        knowledgePoints: List<String>
    ) {
        questionDao.update(
            question.copy(
                figurePathsJson = json.encodeToString(figurePaths),
                knowledgePointsJson = json.encodeToString(knowledgePoints),
                updatedAt = System.currentTimeMillis()
            )
        )
        syncTags(question.id, knowledgePoints)
    }

    suspend fun softDelete(id: Long) {
        val now = System.currentTimeMillis()
        questionDao.softDelete(id, now)
    }

    suspend fun restore(id: Long) = questionDao.restore(id, System.currentTimeMillis())

    suspend fun listDeletedBefore(daysAgo: Int): List<Question> {
        val before = System.currentTimeMillis() - daysAgo * 24L * 3600 * 1000
        return questionDao.listDeletedBefore(before)
    }

    suspend fun hardDelete(ids: List<Long>) = questionDao.hardDelete(ids)

    /**
     * 应用考前排期（F3）：批量把到期题的下一复习日写到规划好的日子。
     *
     * 逐条 UPDATE 而不是事务整体替换：中途失败已写入的部分依然成立
     * （每条独立有效），比"全成或全败"更符合排期的语义。
     */
    suspend fun applyExamSchedule(schedule: Map<Long, Long>) {
        val now = System.currentTimeMillis()
        schedule.forEach { (id, epochDay) ->
            questionDao.updateNextReviewAt(id, epochDay, now)
        }
    }

    /**
     * 设置「是否会了」。
     * 会了 → MASTERED，nextReviewAt 清空（不再进待复习队列与每日提醒）；
     * 不会 → ACTIVE，nextReviewAt 设为今天，次日提醒会重新算上。
     */
    suspend fun setMastered(id: Long, mastered: Boolean): Question? {
        val question = questionDao.findById(id) ?: return null
        val now = System.currentTimeMillis()
        val updated = question.copy(
            status = if (mastered) MasteryStatus.MASTERED else MasteryStatus.ACTIVE,
            reviewStage = if (mastered) ReviewRules.MAX_STAGE else 0,
            nextReviewAt = if (mastered) null else LocalDate.now().toEpochDay(),
            updatedAt = now
        )
        questionDao.update(updated)
        return updated
    }

    /** 只更新备注，供详情页「看题时随手记」使用。 */
    suspend fun updateNote(id: Long, note: String) {
        val question = questionDao.findById(id) ?: return
        questionDao.update(
            question.copy(note = note.trim(), updatedAt = System.currentTimeMillis())
        )
    }

    /**
     * 复习结果原子落库（T1 复习闭环）。
     *
     * 一次事务完成两件事：
     * 1. 插入 [ReviewLog]（stageIndex 记**当前**轮次，即评分前的轮次）；
     * 2. 推进 question：reviewStage / nextReviewAt / status。
     *
     * 与 setMastered() 的约定对齐：达到 MAX_STAGE 时 nextReviewAt = null
     * （不再进队列、不再进每日提醒）。
     *
     * 返回是否落库成功。题不存在或 `deletedAt != null`（复习中途被删）
     * 时静默跳过返回 false——调用方照常切下一题即可，不要报错打断复习流。
     */
    suspend fun applyReviewResult(
        questionId: Long,
        result: ReviewResult,
        now: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now()
    ): Boolean {
        val question = questionDao.findById(questionId) ?: return false
        if (question.deletedAt != null) return false
        val newStage = ReviewRules.nextStage(question.reviewStage, result)
        val updated = question.copy(
            reviewStage = newStage,
            nextReviewAt = if (newStage >= ReviewRules.MAX_STAGE) {
                null
            } else {
                ReviewRules.nextReviewDay(question.reviewStage, result, today).toEpochDay()
            },
            status = ReviewRules.statusFor(newStage, question.status),
            updatedAt = now
        )
        questionDao.applyReviewResult(
            log = ReviewLog(
                questionId = questionId,
                reviewedAt = now,
                stageIndex = question.reviewStage,
                result = result
            ),
            updated = updated
        )
        return true
    }

    private suspend fun resolveSubjectId(draft: QuestionDraft): Long? {
        val id = draft.subjectId
        if (id != null && subjectDao.findById(id) != null) return id
        val name = draft.subjectName.trim()
        if (name.isEmpty()) return null
        return tagRepository.ensureSubject(name).id
    }

    private suspend fun syncTags(questionId: Long, knowledgePoints: List<String>) {
        tagRepository.replaceQuestionTags(
            questionId,
            knowledgePoints.map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}

/**
 * LIKE 关键词转义（T9）：配合 QuestionDao 各查询里的 `ESCAPE '\'`。
 *
 * 不转义的话 `%`/`_` 会被当通配符——搜「100%」命中一切含「100」的题，
 * 搜「a_b」把「a+b」也带出来。反斜杠必须**先**转，否则它会破坏
 * 后两步刚写进去的转义序列（`\%` 里的 `\` 又被翻倍一次）。
 */
internal fun escapeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
