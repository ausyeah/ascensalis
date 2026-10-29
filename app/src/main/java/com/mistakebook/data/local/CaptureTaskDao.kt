package com.mistakebook.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.domain.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureTaskDao {

    @Insert
    suspend fun insert(task: CaptureTask): Long

    @Update
    suspend fun update(task: CaptureTask)

    @Query("SELECT * FROM capture_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<CaptureTask?>

    @Query("SELECT * FROM capture_tasks WHERE id = :id")
    suspend fun findById(id: Long): CaptureTask?

    @Query("SELECT * FROM capture_tasks WHERE groupId = :groupId ORDER BY orderInGroup")
    fun observeGroup(groupId: String): Flow<List<CaptureTask>>

    @Query("SELECT * FROM capture_tasks WHERE groupId = :groupId ORDER BY orderInGroup")
    suspend fun listGroup(groupId: String): List<CaptureTask>

    @Query(
        "SELECT * FROM capture_tasks WHERE groupId IS (SELECT groupId FROM capture_tasks WHERE id = :id) ORDER BY orderInGroup"
    )
    fun observeGroupOf(id: Long): kotlinx.coroutines.flow.Flow<List<CaptureTask>>

    @Query(
        """
        SELECT * FROM capture_tasks
        WHERE status IN ('PENDING', 'UPLOADING', 'PARSING', 'LLM')
        ORDER BY COALESCE(groupId, ''), orderInGroup, id
        """
    )
    suspend fun listPending(): List<CaptureTask>

    @Query("UPDATE capture_tasks SET status = :status, errorMessage = :message, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TaskStatus, message: String?, now: Long)

    /**
     * 定向只写 refinedJson 与 updatedAt（T4 自动暂存用）。
     *
     * 整包 update（findById→copy→update）拿的是**旧快照**：暂存与识别完成
     * 的 finish() 并发时互相覆盖，谁后写谁赢、先写的列全丢。定向 UPDATE
     * 只碰自己负责的两列，天然无竞态（v0.0.65）。
     */
    @Query("UPDATE capture_tasks SET refinedJson = :refinedJson, updatedAt = :now WHERE id = :id")
    suspend fun updateRefinedJson(id: Long, refinedJson: String, now: Long)

    @Query(
        "UPDATE capture_tasks SET status = :status, errorMessage = :message, errorKind = :kind, updatedAt = :now WHERE id = :id AND status != 'DONE'"
    )
    suspend fun updateFailure(
        id: Long,
        status: TaskStatus,
        message: String?,
        kind: String?,
        now: Long
    )

    /**
     * 切到「解析中」状态。
     *
     * **刻意不写 `stageText`。**
     *
     * 早先是 `SET status='PARSING', stageText=:stage`，而调用方在同一次
     * `onStage` 里先写真实文案、再调这个方法把文案覆盖成常量
     * （`RecognitionEngine.obtainMarkdown`）。**第二次写入永远赢**，
     * 于是从「上传照片…」开始 stageText 就被钉死成同一句话，
     * 直到整个识别结束。
     *
     * 后果不只是文案不对：`ProgressScreen` 靠正则从 stageText 里抠 `%`，
     * 而那串常量没有百分号 → 进度永远算不出来 → 界面无限转圈。
     * 用户看到「MinerU 解析中」转圈不动，与「真的卡死」完全无法区分，
     * 排障时也拿不到任何信息。
     *
     * `stageText` 的唯一写入方必须是 [updateStage]。
     */
    @Query(
        "UPDATE capture_tasks SET status = 'PARSING', updatedAt = :now WHERE id = :id AND status != 'FAILED'"
    )
    suspend fun moveToParsing(id: Long, now: Long)

    @Query("UPDATE capture_tasks SET stageText = :stage, updatedAt = :now WHERE id = :id")
    suspend fun updateStage(id: Long, stage: String, now: Long)

    @Query(
        """
        UPDATE capture_tasks
        SET status = 'FAILED', errorMessage = :message, updatedAt = :now
        WHERE status IN ('PENDING', 'UPLOADING', 'PARSING', 'LLM')
        """
    )
    suspend fun failUnfinished(message: String, now: Long)

    @Query("DELETE FROM capture_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM capture_tasks")
    suspend fun clearAll()
}
