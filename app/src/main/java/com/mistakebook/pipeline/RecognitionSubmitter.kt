package com.mistakebook.pipeline

import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.data.repos.CaptureTaskRepository
import com.mistakebook.domain.TaskStatus
import java.io.File
import java.util.UUID
import com.mistakebook.R

/**
 * 统一的任务提交入口：相册多选、PDF 导入都走这里，保证同一批任务共享 groupId，
 * 进度页可以显示「第 n / m 张」并按组浏览结果。
 */
class RecognitionSubmitter(
    private val taskRepository: CaptureTaskRepository,
    private val engine: RecognitionEngine,
    // 阶段文案写入 CaptureTask 落库后由进度页原样展示；本层无 Composable 作用域，从资源解析。
    private val appContext: android.content.Context
) {

    suspend fun submitImages(files: List<File>, groupTitle: String): List<Long> {
        if (files.isEmpty()) return emptyList()
        val groupId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ids = files.mapIndexed { index, file ->
            taskRepository.create(
                CaptureTask(
                    photoPath = file.absolutePath,
                    status = TaskStatus.PENDING,
                    stageText = appContext.getString(R.string.task_stage_queued),
                    groupId = groupId,
                    orderInGroup = index,
                    groupSize = files.size,
                    groupTitle = groupTitle,
                    sourceType = CaptureTask.SOURCE_IMAGE,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        engine.submit(ids)
        return ids
    }

    /**
     * 重新识别某道题的原图。
     *
     * [replacesQuestionId] 非空时，编辑页保存时会**覆盖**那道题而不是新建——
     * 用户点「重新识别」的意图就是「这条识别错了，重来」，保留旧题会产生重复。
     * 旧的题干/答案/解析会先被重置，覆盖失败也不至于把新内容写进两行。
     */
    suspend fun reRecognize(
        file: File,
        replacesQuestionId: Long,
        groupTitle: String
    ): Long? {
        if (!file.exists()) return null
        val now = System.currentTimeMillis()
        val id = taskRepository.create(
            CaptureTask(
                photoPath = file.absolutePath,
                status = TaskStatus.PENDING,
                stageText = appContext.getString(R.string.task_stage_queued),
                groupId = UUID.randomUUID().toString(),
                orderInGroup = 0,
                groupSize = 1,
                groupTitle = groupTitle,
                sourceType = CaptureTask.SOURCE_IMAGE,
                createdAt = now,
                updatedAt = now
            )
        )
        taskRepository.markReplaces(id, replacesQuestionId)
        engine.submit(id)
        return id
    }

    /** 文本 PDF：每页抽出的纯文本单独建任务，跳过 MinerU。 */
    suspend fun submitTextPages(pages: List<String>, groupTitle: String, folder: java.io.File): List<Long> {
        if (pages.isEmpty()) return emptyList()
        val groupId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ids = pages.mapIndexed { index, text ->
            val file = File(folder, "pdf_${UUID.randomUUID()}_p${index + 1}.md")
            file.writeText(text)
            taskRepository.create(
                CaptureTask(
                    photoPath = file.absolutePath,
                    status = TaskStatus.PENDING,
                    stageText = appContext.getString(R.string.task_stage_queued),
                    groupId = groupId,
                    orderInGroup = index,
                    groupSize = pages.size,
                    groupTitle = groupTitle,
                    sourceType = CaptureTask.SOURCE_PDF_TEXT,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        engine.submit(ids)
        return ids
    }
}
