package com.mistakebook.data.repos

import com.mistakebook.data.local.CaptureTaskDao
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.domain.TaskStatus
import kotlinx.coroutines.flow.Flow

class CaptureTaskRepository(private val dao: CaptureTaskDao) {

    fun observeById(id: Long): Flow<CaptureTask?> = dao.observeById(id)

    fun observeGroup(groupId: String): Flow<List<CaptureTask>> = dao.observeGroup(groupId)

    fun observeGroupOf(id: Long): Flow<List<CaptureTask>> = dao.observeGroupOf(id)

    suspend fun create(task: CaptureTask): Long = dao.insert(task)

    /**
     * 标记「这次识别完成后要覆盖那道题」。
     * 走整条 update 而不是单独一条 SQL——CaptureTask 表结构没变，
     * 复用现有的 update 路径，迁移成本最低。
     */
    suspend fun markReplaces(id: Long, replacesQuestionId: Long) {
        val task = dao.findById(id) ?: return
        dao.update(task.copy(replacesQuestionId = replacesQuestionId))
    }

    suspend fun update(task: CaptureTask) = dao.update(task)

    suspend fun findById(id: Long): CaptureTask? = dao.findById(id)

    suspend fun listPending(): List<CaptureTask> = dao.listPending()

    suspend fun setStatus(id: Long, status: TaskStatus, now: Long) =
        dao.updateStatus(id, status, null, now)

    suspend fun setStatusIfParsing(id: Long, now: Long) =
        dao.moveToParsing(id, now)

    suspend fun setStage(id: Long, stage: String, now: Long) = dao.updateStage(id, stage, now)

    suspend fun fail(id: Long, message: String, now: Long, kind: String? = null) =
        dao.updateFailure(id, TaskStatus.FAILED, message, kind, now)

    suspend fun finish(
        id: Long,
        markdown: String,
        refinedJson: String,
        questionCount: Int,
        now: Long
    ) {
        val task = dao.findById(id) ?: return
        dao.update(
            task.copy(
                status = TaskStatus.DONE,
                stageText = "",
                errorMessage = null,
                errorKind = null,
                markdown = markdown,
                refinedJson = refinedJson,
                updatedAt = now
            )
        )
    }

    /** 按草稿索引回填题目 id，保留尚未保存的位置以支持中途恢复。 */
    suspend fun attachQuestionIds(id: Long, questionIds: Map<Int, Long>, now: Long) {
        val indexedIds = serializeQuestionIdsByIndex(questionIds) ?: return
        dao.update(
            (dao.findById(id) ?: return).copy(
                questionId = questionIds.minByOrNull { it.key }?.value,
                questionIdsJson = indexedIds,
                updatedAt = now
            )
        )
    }

    /**
     * 只写回 refinedJson 一列与 updatedAt（T4 自动暂存）。
     *
     * 绝不动 status：暂存发生时任务可能处于任意状态（DONE 之后用户
     * 回来改题很常见），整包 update 拿旧快照会把 status/markdown 等
     * 并发改动一起冲掉。
     */
    suspend fun updateRefinedJson(id: Long, refinedJson: String, now: Long) {
        val task = dao.findById(id) ?: return
        dao.update(task.copy(refinedJson = refinedJson, updatedAt = now))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun failUnfinished(message: String) =
        dao.failUnfinished(message, System.currentTimeMillis())

    suspend fun clearAll() = dao.clearAll()
}

internal fun serializeQuestionIdsByIndex(questionIds: Map<Int, Long>): String? {
    val highestIndex = questionIds.keys.maxOrNull() ?: return null
    return (0..highestIndex).joinToString(",") { questionIds[it]?.toString().orEmpty() }
}
