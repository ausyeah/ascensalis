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

            // ---- 识别路线决策（v0.0.44：MinerU / 大模型 / 自动）----
            //
            // 纯文本 PDF 不参与选择：它的「识别」就是本地读文件，
            // 与 MinerU、大模型都无关（老行为原样保留）。
            val plan = if (task.sourceType == CaptureTask.SOURCE_PDF_TEXT) {
                RecognitionPlan.Mineru
            } else {
                RecognitionPlan.decide(
                    mode = settings.recognitionMode,
                    mineruConfigured = settings.mineruConfigured,
                    supportsVision = settings.activeProfile?.supportsVision == true,
                    visionConfigured = settings.visionConfigured,
                    modelName = settings.activeProfile?.model.orEmpty()
                )
            }

            // 大模型直连：一步出结构化题目，没有 MinerU 中间产物。
            // markdown 槽位存模型原始输出——重试的缓存路径与降级展示都读它。
            if (plan is RecognitionPlan.LlmDirect) {
                val bundle = recognizeWithVisionLlm(
                    taskId = taskId,
                    task = task,
                    file = file,
                    settings = settings,
                    profile = settings.activeProfile
                )
                finishWith(taskId, bundle)
                return
            }

            // 用**用户单独配置的识图模型**直连看图。
            if (plan is RecognitionPlan.VisionDirect) {
                val vision = settings.visionProfile
                    ?: throw PipelineException(
                        com.mistakebook.net.ApiError(
                            kind = com.mistakebook.net.ApiErrorKind.NO_KEY,
                            serverMessage = "尚未配置识图模型（设置 → 大模型 → 识图模型）。"
                        )
                    )
                val bundle = recognizeWithVisionLlm(
                    taskId = taskId,
                    task = task,
                    file = file,
                    settings = settings,
                    profile = vision
                )
                finishWith(taskId, bundle)
                return
            }

            // 两条路都走不通：把原因作为任务失败落库（与其它失败同一套 UI）。
            if (plan is RecognitionPlan.Unavailable) {
                throw PipelineException(
                    com.mistakebook.net.ApiError(
                        kind = com.mistakebook.net.ApiErrorKind.NO_KEY,
                        serverMessage = plan.reason
                    )
                )
            }

            // 自动回落链（AUTO）：依次尝试，**中途失败不中断**，
            // 全部失败才报错。链的顺序见 [RecognitionPlan.decide]。
            if (plan is RecognitionPlan.Chain) {
                val failures = mutableListOf<String>()
                for (route in plan.routes) {
                    val result = runCatching {
                        when (route) {
                            RecognitionPlan.Route.MINERU -> {
                                val markdown = mineruMarkdown(taskId, task, file, settings)
                                // MinerU 出的是原始文字，**最后一步交回大模型整理**
                                // ——「自动」链条的终点就是大模型处理。
                                refineWithLlm(taskId, task, markdown, settings)
                            }

                            RecognitionPlan.Route.CURRENT_LLM -> recognizeWithVisionLlm(
                                taskId = taskId,
                                task = task,
                                file = file,
                                settings = settings,
                                profile = settings.activeProfile
                            )

                            RecognitionPlan.Route.VISION_LLM -> recognizeWithVisionLlm(
                                taskId = taskId,
                                task = task,
                                file = file,
                                settings = settings,
                                profile = settings.visionProfile
                            )
                        }
                    }
                    val bundle = result.getOrNull()
                    if (bundle != null) {
                        finishWith(taskId, bundle)
                        return
                    }
                    failures += routeFailureText(route, result.exceptionOrNull())
                }
                // 整条链都不通：把每一条的失败原因都说清楚，用户才知道该改哪一项。
                throw PipelineException(
                    com.mistakebook.net.ApiError(
                        kind = com.mistakebook.net.ApiErrorKind.NO_KEY,
                        serverMessage = "自动识别的所有途径都没成功：\n" +
                            failures.joinToString("\n") { "· $it" }
                    )
                )
            }

            // 纯 MinerU：失败（含没配 Key）按老行为报错，不回落。
            val markdown = obtainMarkdown(taskId, task, file, settings)
            refineAndFinish(taskId, task, markdown, settings)
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

    /** MinerU 路线拿到 markdown 后的统一收尾：状态切 LLM → 整理 → 落库。 */
    private suspend fun refineAndFinish(
        taskId: Long,
        task: CaptureTask,
        markdown: String,
        settings: SettingsSnapshot
    ) {
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
        finishWith(taskId, bundle)
    }

    /** 落一条完成态任务（大模型直连与 MinerU 路线共用）。 */
    private suspend fun finishWith(taskId: Long, bundle: DraftBundle) {
        taskRepository.finish(
            id = taskId,
            markdown = bundle.markdown,
            refinedJson = DraftBundleCodec.encode(bundle),
            questionCount = bundle.questions.size,
            now = System.currentTimeMillis()
        )
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
                    .ifBlank {
                        buildFailureMessage(error.apiError)
                    },
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

    /**
     * 组装用户可读的失败原因。
     *
     * 网络类错误把底层异常（[com.mistakebook.net.ApiError.detail]，如
     * 「无法解析主机 mineru.net」「connect timeout」）与**当前网络出口
     * 模式**一并展示——用户反馈「切换代理/直连没发挥作用」，实际上
     * 模式每次建连都在生效，但通用文案把真实原因全盖住了，无法对账。
     * 有了这两样，切模式立刻能看到差别；报给开发者也能直接定位。
     */
    private fun buildFailureMessage(apiError: com.mistakebook.net.ApiError): String {
        val base = defaultMessageFor(apiError.kind)
        val diagnostic = apiError.kind == com.mistakebook.net.ApiErrorKind.NETWORK ||
            apiError.kind == com.mistakebook.net.ApiErrorKind.TIMEOUT
        val detail = apiError.detail.trim()
        if (!diagnostic || detail.isBlank()) return base
        return "$base（${detail.take(120)} · 出口：${com.mistakebook.net.NetworkRouter.describe()}）"
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
        // 纯 MinerU 路线：失败照旧抛 PipelineException（老行为，不回落）。
        return mineruMarkdown(taskId, task, file, settings)
    }

    /**
     * 自动回落链里某一条路失败时的一句话说明。
     *
     * 用户要看到的是「每条路各为什么不行」，而不是一句笼统的
     * 「识别失败」——否则他不知道该去配 MinerU 还是去配识图模型。
     */
    private fun routeFailureText(
        route: RecognitionPlan.Route,
        error: Throwable?
    ): String {
        val name = when (route) {
            RecognitionPlan.Route.MINERU -> "MinerU"
            RecognitionPlan.Route.CURRENT_LLM -> "当前大模型直连"
            RecognitionPlan.Route.VISION_LLM -> "识图模型直连"
        }
        val detail = when (error) {
            is PipelineException -> error.apiError.serverMessage.ifBlank { "调用失败" }
            null -> "未知原因"
            else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        }
        return "$name：$detail"
    }

    /** 调 MinerU 拿 markdown；失败抛 [PipelineException]（由调用方决定是否回落）。 */
    private suspend fun mineruMarkdown(
        taskId: Long,
        task: CaptureTask,
        file: File,
        settings: SettingsSnapshot
    ): String {
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

    /**
     * **大模型直连识别**（v0.0.44）：可识图模型直接看原图，一步输出结构化题目。
     *
     * 与「MinerU 出文字 → 再整理」的两步路线相比：少一次外部依赖
     * （没有 MinerU Key 也能用）、少一次网络往返，模型自己对图片里的
     * 公式/表格/手写作答痕迹做判断，不被 OCR 错别字带偏。
     *
     * 产出与 MinerU 路线**同一个 [DraftBundle]**——下游（编辑页、打印）
     * 不需要区分题目是怎么来的。
     */
    private suspend fun recognizeWithVisionLlm(
        taskId: Long,
        task: CaptureTask,
        file: File,
        settings: SettingsSnapshot,
        /** 用哪个模型看图：当前默认模型，或用户单独配置的识图模型。 */
        profile: com.mistakebook.data.prefs.LlmProfile?
    ): DraftBundle {
        val active = profile ?: throw PipelineException(noProfileError())
        if (!active.supportsVision) {
            // 正常情况下 decide() 已经把这条路堵死了；这里是纵深防御——
            // 用户可能在任务排队期间换了模型，执行时必须再验一次。
            throw PipelineException(
                com.mistakebook.net.ApiError(
                    kind = com.mistakebook.net.ApiErrorKind.NO_KEY,
                    serverMessage = "当前模型（${active.model}）不支持识图，" +
                        "无法直接识别图片。请在设置里切换到识图模型，" +
                        "或把识别方式改成「自动」/「仅 MinerU」。"
                )
            )
        }
        taskRepository.setStatus(taskId, TaskStatus.PARSING, System.currentTimeMillis())
        taskRepository.setStage(taskId, "大模型识图中…", System.currentTimeMillis())
        val raw = llmClient.refineJson(
            profile = active,
            systemPrompt = PromptTemplates.visionSystemPrompt(),
            userPrompt = PromptTemplates.visionUserPrompt(),
            imageFile = file,
            longEdgePx = settings.imageLongEdgePx,
            // 直连路径**必须**带图：图就是输入。这与「整理时附带原图核对」
            // （attachOriginalImage）是两个不同的设置，别混。
            attachImage = true
        )
        return when (raw) {
            is com.mistakebook.net.ApiResult.Success -> bundleFrom(
                task = task,
                // markdown 槽位存模型原始输出：重试的缓存路径与降级展示都读它。
                // 降级兜底也用同一份——直连路线没有「截断」概念。
                markdown = raw.data,
                fallbackMarkdown = raw.data,
                truncatedInput = false,
                raw = raw.data
            )

            is com.mistakebook.net.ApiResult.Failure -> throw PipelineException(
                raw.error.copy(serverMessage = visionFailureMessage(raw.error, active.model))
            )
        }
    }

    /**
     * 带图调用失败时的用户可读原因。
     *
     * 多出的一句排查建议是针对一个具体陷阱：[LlmProfile.supportsVision]
     * 默认 **true**（存量配置兼容：升级不能把「能看图」悄悄改成不能）。
     * 于是「这个模型到底识不识图」在多数情况下其实是**猜的**——
     * 模型不识图时，带图的请求要么被忽略图片、要么直接 400，
     * 表现就是「识别失败」，用户无从判断是配置问题还是网络问题。
     * 把这条最可能的原因写进错误里，省得对着笼统的失败发呆。
     */
    private fun visionFailureMessage(
        error: com.mistakebook.net.ApiError,
        model: String
    ): String {
        val base = error.serverMessage.ifBlank { "大模型识别失败" }
        val suspicious = error.kind == com.mistakebook.net.ApiErrorKind.BAD_RESPONSE ||
            error.kind == com.mistakebook.net.ApiErrorKind.AUTH
        if (!suspicious) return base
        return "$base。若反复失败，可能是「$model」实际不支持识图：" +
            "请到设置里关掉该模型的识图开关，或把识别方式改回「仅 MinerU」。"
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
        return when (raw) {
            is com.mistakebook.net.ApiResult.Success -> bundleFrom(
                task = task,
                markdown = markdown,
                // extract 的 markdown 参数是「降级时的兜底题干」，
                // 用截断后的文本（与老行为一致）：真降级时说明输出不可用，
                // 兜底内容短一点反而好读。
                fallbackMarkdown = truncated.text,
                truncatedInput = truncated.truncated,
                raw = raw.data
            )

            is com.mistakebook.net.ApiResult.Failure -> throw PipelineException(raw.error)
        }
    }

    /**
     * 大模型原始输出 → [DraftBundle]（两条识别路线共用）。
     *
     * @param markdown 记入 bundle 的中间产物：MinerU 路线是 markdown，
     *   直连路线是模型原始 JSON（重试的缓存路径与降级展示都读它）。
     * @param fallbackMarkdown 解析失败时的兜底题干来源。
     */
    private fun bundleFrom(
        task: CaptureTask,
        markdown: String,
        fallbackMarkdown: String,
        truncatedInput: Boolean,
        raw: String
    ): DraftBundle {
        val outcome = JsonExtractor.extract(
            raw = raw,
            markdown = fallbackMarkdown,
            imagePath = task.photoPath
        )
        return DraftBundle(
            imagePath = task.photoPath,
            markdown = markdown,
            truncatedInput = truncatedInput,
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
