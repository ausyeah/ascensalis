package com.mistakebook.pipeline

import com.mistakebook.data.local.entities.CaptureTask
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.repos.CaptureTaskRepository
import com.mistakebook.domain.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 识别流水线状态机（PRD 4.4）：
 *
 * PENDING -> UPLOADING -> PARSING -> LLM -> DONE，任一环节失败进 FAILED 且可重试。
 * 任务状态全部持久化到 Room，UI 只观察数据库。同一时刻只跑一个任务（Mutex），
 * 避免打满 MinerU 额度。
 */
class RecognitionEngine(
    private val taskRepository: CaptureTaskRepository,
    private val mineruClient: MineruClient,
    private val llmClient: LlmClient,
    private val settingsStore: SettingsStore,
    private val files: com.mistakebook.data.AppFiles
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    init {
        scope.launch {
            for (taskId in queue) {
                val job = scope.launch(Dispatchers.IO) {
                    mutex.withLock { execute(taskId) }
                }
                jobs[taskId] = job
                job.invokeOnCompletion { jobs.remove(taskId) }
            }
        }
    }

 /** 提交任务队列；同一次导入可批量提交，按 orderInGroup 顺序执行。 */
    fun submit(taskIds: List<Long>) {
        taskIds.forEach { queue.trySend(it) }
    }

    fun submit(taskId: Long) = submit(listOf(taskId))

    fun cancel(taskId: Long) {
        // jobs 的取消照常执行（幂等）：把还在跑的协程停掉总没错。
        // 但**落库要挑状态**（T6）：任务恰好已经 DONE（或早就 FAILED）时，
        // 无条件的 fail() 会把已完成覆写成「已取消」——用户盯着编辑页
        // 里的题忽然发现任务显示失败，进度页还回不去了。
        jobs.remove(taskId)?.cancel(CancellationException("已取消"))
        scope.launch {
            val task = taskRepository.findById(taskId) ?: return@launch
            when (task.status) {
                TaskStatus.DONE, TaskStatus.FAILED -> return@launch
                else -> taskRepository.fail(taskId, CANCELLED_MESSAGE, System.currentTimeMillis())
            }
        }
    }

    /** App 重启后把残留的未完成任务置为失败，用户可在进度页重试。 */
    suspend fun resumePending() = withContext(Dispatchers.IO) {
        taskRepository.failUnfinished(INTERRUPTED_MESSAGE)
    }

    private suspend fun execute(taskId: Long) {
        val task = taskRepository.findById(taskId) ?: return
        val file = File(task.photoPath)
        if (!file.exists()) {
            taskRepository.fail(taskId, "找不到待识别的文件", System.currentTimeMillis())
            return
        }
        val now = System.currentTimeMillis()
        taskRepository.setStatus(taskId, TaskStatus.UPLOADING, now)
        try {
            // **已经有识别结果就直接跳到 AI 整理**，不再重跑 MinerU。
            //
            // MinerU 的队列拥堵时（用户报告「返回有内容但迟迟不进入下一步」，
            // 诊断显示 state=pending · 文件名已匹配 —— 服务端确实在排队），
            // 点「重试」会重新提交一次，既再烧一份额度，又再等一轮。
            //
            // markdown 非空说明上一次已经拿到结果（可能是在 LLM 那步失败的），
            // 这时重跑识别纯属浪费。
            if (!task.markdown.isNullOrBlank()) {
                taskRepository.setStage(taskId, "已有识别结果，跳过重新识别…", now)
                val cached = task.markdown
                val bundle0 = refineWithLlm(taskId, task, cached, settingsStore.snapshotNow())
                taskRepository.finish(
                    id = taskId,
                    markdown = cached,
                    refinedJson = DraftBundleCodec.encode(bundle0),
                    questionCount = bundle0.questions.size,
                    now = System.currentTimeMillis()
                )
                return
            }
            val settings = settingsStore.snapshotNow()

            val markdown = obtainMarkdown(taskId, task, file, settings)
            taskRepository.findById(taskId)?.let { refreshed ->
                taskRepository.update(
                    refreshed.copy(
                        status = TaskStatus.LLM,
                        markdown = markdown,
                        stageText = "AI 整理中",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            val bundle = refineWithLlm(taskId, task, markdown, settings)

            taskRepository.finish(
                id = taskId,
                markdown = markdown,
                refinedJson = DraftBundleCodec.encode(bundle),
                questionCount = bundle.questions.size,
                now = System.currentTimeMillis()
            )
        } catch (cancel: CancellationException) {
            taskRepository.fail(taskId, CANCELLED_MESSAGE, System.currentTimeMillis())
            throw cancel
        } catch (error: Throwable) {
            failTask(taskId, error)
        } finally {
            // 任务必然到达终态（DONE / FAILED / 已取消）：
            // MinerU 临时目录在这里分层回收（T5），备份瘦身靠它。
            cleanupMineruTemp(taskId)
        }
    }

    /**
     * MinerU 临时目录分层回收（T5）。
     *
     * `files/mineru/{taskId}/` 识别完永不回收的话，备份体积随识别次数
     * 线性膨胀。规则分两层：
     * - **result.zip 总是删**：体积大头，且 DB 里已有 task.markdown 缓存，
     *   zip 对重试毫无价值（重试走 markdown 缓存跳过 MinerU）；
     * - **整目录删除仅当 markdown 已无残留的相对图片引用**（搜不到
     *   `](images/` 子串）：downloadAndExtract 只把**复制成功**的图片
     *   路径改写成绝对路径，复制失败的仍指向 mineru/ 内相对路径，
     *   整目录删掉会让这些引用变死链。markdown 干净才删整目录，
     *   否则只删 zip。
     */
    private suspend fun cleanupMineruTemp(taskId: Long) {
        val dir = files.mineruDir(taskId)
        if (!dir.exists()) return
        runCatching { java.io.File(dir, "result.zip").delete() }
        val markdown = taskRepository.findById(taskId)?.markdown.orEmpty()
        if (!markdown.contains("](images/")) {
            dir.deleteRecursively()
        }
    }

    private suspend fun failTask(taskId: Long, error: Throwable) {
        val now = System.currentTimeMillis()
        when (error) {
            is PipelineException -> taskRepository.fail(
                id = taskId,
                message = error.apiError.serverMessage
                    .ifBlank { defaultMessageFor(error.apiError.kind) },
                now = now,
                kind = error.apiError.kind.name
            )
            is CancellationException -> taskRepository.fail(taskId, CANCELLED_MESSAGE, now)

            else -> taskRepository.fail(
                taskId,
                error.message?.takeIf { it.isNotBlank() } ?: "识别失败，请重试",
                now
            )
        }
    }

    private fun defaultMessageFor(kind: com.mistakebook.net.ApiErrorKind): String = when (kind) {
        com.mistakebook.net.ApiErrorKind.NO_KEY -> "请先到设置填写 API Key"
        com.mistakebook.net.ApiErrorKind.AUTH -> "API Key 无效，请检查设置"
        com.mistakebook.net.ApiErrorKind.RATE_LIMIT -> "服务端繁忙或额度受限，稍后重试"
        com.mistakebook.net.ApiErrorKind.TIMEOUT -> "网络超时，请重试"
        com.mistakebook.net.ApiErrorKind.NETWORK -> "网络不通，请检查连接后重试"
        com.mistakebook.net.ApiErrorKind.BAD_RESPONSE ->
            "AI 没能按要求返回结果。请检查设置里的模型名是否正确，或稍后重试"

        else -> "识别失败，请重试"
    }

    private suspend fun obtainMarkdown(
        taskId: Long,
        task: CaptureTask,
        file: File,
        settings: SettingsSnapshot
    ): String {
        if (task.sourceType == CaptureTask.SOURCE_PDF_TEXT) {
            taskRepository.setStage(taskId, "读取 PDF 文本…", System.currentTimeMillis())
            return file.readText()
        }
        taskRepository.setStage(taskId, "上传照片…", System.currentTimeMillis())
        val result = mineruClient.recognize(
            taskId = taskId,
            file = file,
            fileName = file.name,
            mineruKey = settings.mineruKey,
            modelVersion = settings.mineruModelVersion,
            language = settings.ocrLanguage,
            forceOcr = settings.forceOcr,
            onStage = { stage ->
                // **顺序：先切状态，再写真实文案。**
                // 反过来的话 `setStatusIfParsing` 会用常量覆盖掉这里刚写的 stage，
                // 界面就永远停在同一句话上（那个 bug 已经修掉，
                // 但顺序反过来又会把它请回来）。
                taskRepository.setStatusIfParsing(taskId, System.currentTimeMillis())
                taskRepository.setStage(taskId, stage, System.currentTimeMillis())
            }
        )
        return when (result) {
            is com.mistakebook.net.ApiResult.Success -> result.data.markdown
            is com.mistakebook.net.ApiResult.Failure -> throw PipelineException(result.error)
        }
    }

    private suspend fun refineWithLlm(
        taskId: Long,
        task: CaptureTask,
        markdown: String,
        settings: SettingsSnapshot
    ): DraftBundle {
        val profile = settings.activeProfile ?: throw PipelineException(noProfileError())
        val truncated = MarkdownTruncator.truncate(markdown)
        val attachImage = settings.attachOriginalImage && task.sourceType == CaptureTask.SOURCE_IMAGE
        // 任何来源都按「可能多题」提示：一张照片/一页 PDF 里经常并列多道题，
        // 之前只对批量与文本 PDF 开启，导致单图场景全部揉进一道题。
        val system = PromptTemplates.systemPrompt(multiQuestion = true, attachImage = attachImage)
        val user = PromptTemplates.userPrompt(truncated.text)
        val imageFile = if (attachImage) File(task.photoPath) else null

        val raw = llmClient.refineJson(
            profile = profile,
            systemPrompt = system,
            userPrompt = user,
            imageFile = imageFile,
            longEdgePx = settings.imageLongEdgePx,
            attachImage = attachImage
        )
        when (raw) {
            is com.mistakebook.net.ApiResult.Success -> {
                val outcome = JsonExtractor.extract(
                    raw = raw.data,
                    markdown = truncated.text,
                    imagePath = task.photoPath
                )
                return DraftBundle(
                    imagePath = task.photoPath,
                    markdown = markdown,
                    truncatedInput = truncated.truncated,
                    degraded = outcome.degraded,
                    degradedReason = if (outcome.degraded) DEGRADED_MESSAGE else "",
                    questions = outcome.drafts.map { draft ->
                        DraftQuestion(
                            subject = draft.subjectName,
                            title = draft.title,
                            stem = draft.stem,
                            options = draft.options.map { option ->
                                RefinedOptionDto(label = option.label, text = option.text)
                            },
                            answer = draft.answer,
                            analysis = draft.analysis,
                            knowledgePoints = draft.knowledgePoints,
                            errorReason = draft.errorReason.label,
                            difficulty = draft.difficulty,
                            uncertain = draft.uncertain
                        )
                    }
                )
            }
            is com.mistakebook.net.ApiResult.Failure -> throw PipelineException(raw.error)
        }
    }

    private fun noProfileError() = com.mistakebook.net.ApiError(
        kind = com.mistakebook.net.ApiErrorKind.NO_KEY,
        serverMessage = "尚未配置大模型接入"
    )

    companion object {
        const val CANCELLED_MESSAGE = "已取消"
        const val INTERRUPTED_MESSAGE = "任务被中断"
        const val DEGRADED_MESSAGE = "AI 整理失败，已保留原始识别文本，请手动整理"
        const val SKIP_MESSAGE = "已跳过 AI 整理，请手动整理原始识别文本"
    }
}

class PipelineException(val apiError: com.mistakebook.net.ApiError) : Exception(apiError.serverMessage)
