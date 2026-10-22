package com.mistakebook.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.chat.ChatAttachmentPreparer
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatContextCompressor
import com.mistakebook.data.chat.ChatImageOcr
import com.mistakebook.data.chat.ChatOcrText
import com.mistakebook.data.chat.OutgoingMessage
import com.mistakebook.data.chat.PreparedAttachment
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.data.repos.ChatRepository
import com.mistakebook.domain.AttachmentKind
import com.mistakebook.domain.AttachmentStatus
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatContentPart
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ChatStreamEvent
import com.mistakebook.net.llm.ImageUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 还没发送的附件。
 *
 * **不是 Room 实体**：实体的 `messageId` 是非空外键，而用户是**先选附件、后发消息**
 * ——选的那一刻消息还不存在，插进去会撞外键约束。
 * 所以准备阶段只在内存里，发送时才由仓储落库。
 *
 * @param id 本地自增，仅供 UI 区分与删除。
 */
data class PendingAttachment(
    val id: Long,
    val fileName: String,
    val kind: AttachmentKind,
    val status: AttachmentStatus,
    val sizeBytes: Long = 0,
    val extractedChars: Int = 0,
    val errorMessage: String? = null,
    val prepared: PreparedAttachment? = null
)

/**
 * 对话页状态。**单一 StateFlow**（项目规则 5）。
 *
 * 一次性事件（错误提示、已复制等）走 [ChatViewModel.events]，不塞进这里——
 * 塞进 state 的后果是转屏后事件重放，用户看到「已复制」弹两次。
 */
data class ChatUiState(
    val loading: Boolean = true,
    val sessionId: Long = 0L,
    val questionId: Long? = null,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** 当前助手消息的内存预览；Room 仍以较低频率持久化完整内容。 */
    val streamingMessageId: Long? = null,
    val streamingContent: String? = null,

    /**
     * 消息 id -> 该消息里图片的 data URL 列表。
     *
     * 只给**用户自己发的**消息用：AI 不会回图，而历史里更早的图片
     * 也不该在打开会话时全部 base64 一遍。
     */
    val bubbleImages: Map<Long, List<String>> = emptyMap(),

    /** 输入框内容。首问会预填但不自动发送。 */
    val input: String = "",

    /** 还没发送的待发附件。 */
    val pendingAttachments: List<PendingAttachment> = emptyList(),

    val isStreaming: Boolean = false,

    /**
     * 被省略的早期对话轮数。大于 0 时列表顶部要插一条灰色分隔条，
     * 否则用户以为 AI 失忆。
     */
    val omittedCount: Int = 0,

    /**
     * 本次回复被 max_tokens 截断。要区别于正常结束提示用户。
     *
     * 一次性的弹窗提醒，详见 [truncatedMessageId]。
     */
    val truncated: Boolean = false,

    /**
     * 被截断的那条助手消息 id。
     *
     * ## 为什么不只用弹窗
     *
     * 弹窗只在**这一轮**生成时弹一次。用户往回翻时看到的那条残缺回答，
     * 界面上和完整回答长得一模一样——他不知道下面少了东西，
     * 只能靠「记得刚才弹过窗」来判断。
     *
     * 所以正文末尾再挂一个常驻标记（[R.string.chat_truncated_badge]，
     * 这条字符串早就写好了却一直没人用）。
     */
    val truncatedMessageId: Long? = null,

    /** 未配置 API Key——UI 弹引导而不是显示「失败」。 */
    val needsApiKey: Boolean = false,

    /** 最后一条助手消息失败，允许重试。 */
    val canRetry: Boolean = false,

    /**
     * 当前会话有生效中的上文摘要（上下文压缩的产物）。
     * true 时输入栏上方显示摘要提示条。
     */
    val summaryActive: Boolean = false,

    /** 压缩正在进行中。按钮转圈，避免连点生成多份摘要。 */
    val compressing: Boolean = false,

    /**
     * 上一次请求的输入 token 数（服务端回报，0 表示还没问过）。
     * 输入栏显示「上次请求 ≈ N tokens」，让用户对上下文占用有数。
     */
    val lastPromptTokens: Int = 0,

    /**
     * 正在后台用 MinerU 识别图片时的阶段文案。
     *
     * 非空时输入栏上方显示一条进度提示。
     */
    val ocrStage: String? = null,

    /**
     * 图片识别失败时的常驻说明。
     *
     * 与 [ocrStage] 分工：stage 是**进度**，跑了就换；
     * 本字段是**结果**，失败原因一直挂在输入栏上方，直到重试成功或换会话。
     *
     * 没有它的话，失败只进 logcat——用户看到进度条消失、
     * 模型答非所问，完全无从判断是图没传上去还是没识别出来。
     */
    val ocrNotice: String? = null
)

/** 一次性事件。 */
sealed interface ChatEvent {
    data class Error(val text: String) : ChatEvent
    data object Copied : ChatEvent
}

/**
 * 对话页 ViewModel。
 *
 * ## 流式预览与持久化
 *
 * 当前助手消息保留一份内存预览，最多每 [PREVIEW_THROTTLE_MS] 更新一次；UI 只用它覆盖这一条消息。
 * Room 仍按 [WRITE_THROTTLE_MS] 节流写入，生成结束或取消时再强制落下剩余内容。
 * 这样既能及时显示 token，也避免每个 delta 都触发数据库 Flow 与整列列表重组。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val stream: ChatCompletionStream,
    private val settingsStore: SettingsStore,
    private val usageStatsStore: UsageStatsStore,
    private val preparer: ChatAttachmentPreparer,
    private val compressor: ChatContextCompressor,
    private val imageOcr: ChatImageOcr,
    private val questionId: Long?,

    /** 从对话记录进来时直接指定会话；null = 新开或按题目定位。 */
    private val sessionId: Long? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val partSerializer = ListSerializer(ChatContentPart.serializer())

    private var streamJob: Job? = null

    /** 待发附件的本地自增 id。 */
    private var attachmentSeq = 0L

    /** 气泡图片的加载任务，新消息到达时取消旧的。 */
    private var bubbleImageJob: Job? = null

    /** 后台图片 OCR 任务。 */
    private var ocrJob: Job? = null

    /**
     * 本轮被截断的那条助手消息。
     *
     * 定义在 collectStream 里接收完事件后写入 state，
     * 不直接在 Flow 里改 state（那会造成无限循环）。
     */
    private var truncatedMessageId: Long? = null

    init {
        viewModelScope.launch {
            // 从对话记录点进来时会话已经存在，直接按 id 用；
            // 只有「从首页/题目页新开」才需要创建或复用。
            //
            // 早先这里无条件 `sessionForQuestion(questionId)`：自由会话的 questionId
            // 是 null，于是去查「那条空的自由会话」，而 DAO 的查询带
            // `AND NOT EXISTS (SELECT 1 FROM chat_messages ...)`——
            // 只要那条会话有消息就查不到，于是**每次点历史记录都新建一条空会话**，
            // 进去是一片空白，而且越点越多。
            val session = if (sessionId != null) {
                repository.sessionById(sessionId)
                    // 会话被删了（列表里刚删掉就点进来）：退回新建，不要卡在空白页
                    ?: repository.sessionForQuestion(questionId)
            } else {
                repository.sessionForQuestion(questionId)
            }
            // 进程上次被杀时那条消息永远停在 STREAMING，不收拾的话界面会一直转圈，
            // 而且用户没有任何办法让它停下来。
            repository.recoverStaleStreaming(session.id)
            if (questionId != null) {
                repository.ensureQuestionContext(session.id, questionId)
            }
            _state.value = _state.value.copy(
                sessionId = session.id,
                questionId = session.questionId,
                title = session.title
            )
            repository.observeMessages(session.id).collect { messages ->
                val current = _state.value
                val committedStream = current.streamingMessageId?.let { id ->
                    messages.firstOrNull { it.id == id && it.status != MessageStatus.STREAMING }
                }
                _state.value = current.copy(
                    loading = false,
                    messages = messages,
                    // 摘要消息是真实消息，observeMessages 会带上它；
                    // 有它在 state 里就标记 summaryActive，输入栏才显示提示条。
                    summaryActive = messages.any { it.isSummary() },
                    streamingMessageId = if (committedStream != null) null else current.streamingMessageId,
                    streamingContent = if (committedStream != null) null else current.streamingContent,
                    canRetry = messages.lastOrNull()?.status == MessageStatus.FAILED
                )
                loadBubbleImages(messages)
            }
        }
    }

    /** 上下文压缩的摘要消息判定：与 ChatContextCompressor 同一口径。 */
    private fun ChatMessage.isSummary(): Boolean =
        role == ChatRole.SYSTEM && content.startsWith(ChatContextAssembler.SUMMARY_PREFIX)

    /**
     * 把用户消息里的图片读成 data URL，供气泡显示。
     *
     * ## 为什么要单独加载
     *
     * 消息表里只存 `attachmentIdsJson`，图片本身在 `chat_attachments`。
     * 气泡早先**只渲染 `message.content`**，附件从来没进过界面——
     * 用户发出去图、自己这边看不见，但模型那边看得到，
     * 于是表现成「图片发不出去」。
     *
     * 走 `ChatImageDataUrls` 的 LRU 缓存，重复重组不会反复 base64。
     */
    private fun loadBubbleImages(messages: List<ChatMessage>) {
        val targets = messages.filter { it.role == ChatRole.USER && it.attachmentIdsJson.isNotBlank() }
        if (targets.isEmpty()) {
            if (_state.value.bubbleImages.isNotEmpty()) {
                _state.value = _state.value.copy(bubbleImages = emptyMap())
            }
            return
        }
        bubbleImageJob?.cancel()
        bubbleImageJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = mutableMapOf<Long, List<String>>()
            targets.forEach { message ->
                val urls = message.attachmentIds()
                    .mapNotNull { repository.attachmentById(it) }
                    .filter { it.kind == AttachmentKind.IMAGE && it.status == AttachmentStatus.READY }
                    .mapNotNull { attachment -> repository.imageDataUrlOf(attachment) }
                if (urls.isNotEmpty()) loaded[message.id] = urls
            }
            // 中途可能已经切走或又发了一条，以最新的消息列表为准
            val current = _state.value.messages.map { it.id }.toSet()
            _state.value = _state.value.copy(
                bubbleImages = loaded.filterKeys { it in current }
            )
        }
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    // ------------------------------------------------------------ 发送

    fun send() {
        val current = _state.value
        if (current.isStreaming || current.loading) return
        val text = current.input.trim()
        // 还在准备中的附件不算数：用户可能刚点发送就点附件
        val ready = current.pendingAttachments.filter { it.status == AttachmentStatus.READY }
        if (text.isEmpty() && ready.isEmpty()) return

        viewModelScope.launch {
            val profile = settingsStore.settings.first().activeProfile
            if (profile == null || !profile.isConfigured()) {
                _state.value = _state.value.copy(needsApiKey = true)
                return@launch
            }
            // 处理失败的附件就地丢弃，不要让整条消息发不出去
            _state.value = _state.value.copy(
                input = "",
                pendingAttachments = emptyList(),
                truncated = false,
                truncatedMessageId = null
            )
            runOnce(profile, text, ready.mapNotNull { it.prepared }, isRetry = false)
        }
    }

    /**
     * 重试：删掉那条失败的助手消息，用**原来的问题**重发。
     *
     * 复用原问题而不是清空输入框——用户点了重试就是要看同一个答案。
     */
    fun retry() {
        val current = _state.value
        if (current.isStreaming) return
        val lastUser = current.messages.lastOrNull { it.role == ChatRole.USER && !it.injected }
        val failed = current.messages.lastOrNull { it.role == ChatRole.ASSISTANT }
        if (lastUser == null || failed == null) return

        viewModelScope.launch {
            val profile = settingsStore.settings.first().activeProfile
            if (profile == null || !profile.isConfigured()) {
                _state.value = _state.value.copy(needsApiKey = true)
                return@launch
            }
            repository.deleteMessage(failed.id)
            runOnce(profile, lastUser.content, emptyList(), isRetry = true)
        }
    }

    fun stop() {
        streamJob?.cancel()
        // OCR 阶段还没有 streamJob 可停，这里一并取消，
        // 否则用户点「停止」后 MinerU 还在后台烧额度。
        ocrJob?.cancel()
    }

    private suspend fun runOnce(
        profile: LlmProfile,
        text: String,
        attachments: List<PreparedAttachment>,
        isRetry: Boolean
    ) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L) return

        if (!isRetry) {
            val message = repository.insertUserMessage(sessionId, text, emptyList(), questionId)
            // 附件必须在消息建好之后才能插（外键指向它），
            // 插完再把 id 列表回写到消息上
            if (attachments.isNotEmpty()) {
                val ids = repository.attachPrepared(message.id, sessionId, attachments)
                repository.setAttachmentIds(message.id, ids)
            }
        }
        // **模型不识图时，先让 MinerU 把图片读一遍。**
        //
        // 用户接的大模型不一定支持多模态（DeepSeek-chat、Qwen-plus 都看不见图）。
        // 但本 App 已经接了 MinerU——它能把图片里的文字、公式逐字抽出来。
        // 于是「给不识图的模型发图」有了可行路径：先 OCR，再把文字喂给模型。
        //
        // **必须等它做完再发请求**：这一轮用户问的往往正是图里的内容，
        // 少了一张图的文字，模型只能对着「用户发了张图」这句话瞎猜。
        //
        // 「后台」指的是**不阻塞界面**：整个过程在协程里跑，
        // 界面照常显示进度提示，用户随时可以点「停止」取消。
        if (!profile.supportsVision) {
            // 直接在当前协程里跑（runOnce 本身就是 viewModelScope.launch 的协程），
            // 这样取消能自然传播：用户点「停止」→ 外层协程取消 → OCR 一起停。
            // 之前另起一个 job 再 join，取消传播是断的。
            ocrJob = currentCoroutineContext()[Job]
            try {
                ensureImageOcr(sessionId)
            } finally {
                ocrJob = null
            }
        }

        val context = repository.buildContext(
            sessionId = sessionId,
            questionId = questionId,
            supportsVision = profile.supportsVision
        )
        val assistantId = repository.insertStreamingAssistantMessage(sessionId, profile.model)
        _state.value = _state.value.copy(
            isStreaming = true,
            streamingMessageId = assistantId,
            streamingContent = "",
            omittedCount = context.omittedCount,
            canRetry = false,
            truncated = false
        )

        // **自动压缩**：这一轮的上下文已经被 assembler 丢过旧轮次
        // （omittedCount > 0），而会话里还没有摘要——现在补一份，
        // 下一轮请求就能靠摘要续上被丢掉的内容。
        // 异步跑、不 join：压缩结果只影响下一次请求，没道理让这一轮等它。
        // 日常短会话不触发——自动压缩是安全网，不是规划。
        if (context.omittedCount > 0 && !_state.value.summaryActive) {
            viewModelScope.launch { compressContext() }
        }

        val job = viewModelScope.launch { collectStream(profile, context.messages, assistantId) }
        streamJob = job
        job.join()
        streamJob = null
    }

    /**
     * 手动/自动压缩上下文：把旧轮次滚成一份摘要。
     *
     * 失败静默——压缩只是增强，失败不该打断用户。
     * 进行中连点直接返回，避免生成多份互相覆盖的摘要。
     */
    fun compressContext() {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L || _state.value.compressing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(compressing = true)
            try {
                val profile = settingsStore.settings.first().activeProfile
                val summary = if (profile != null) {
                    compressor.compress(sessionId, profile)
                } else {
                    null
                }
                _state.value = _state.value.copy(
                    compressing = false,
                    summaryActive = summary != null || _state.value.summaryActive
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.value = _state.value.copy(compressing = false)
            }
        }
    }

    /**
     * 后台给会话里的图片补做 OCR。
     *
     * ## 为什么是「后台」
     *
     * MinerU 是按页计费的外部服务，一次识别要等几秒到几十秒。
     * 「后台」指的是**不阻塞界面**：整个过程跑在协程里，
     * 输入栏上方显示一行进度提示，用户随时可以点「停止」取消。
     *
     * 但**这一轮的请求仍然要等它做完**——用户问的往往正是图里的内容，
     * 少了一张图的文字，模型只能对着「用户发了张图」这句话瞎猜。
     * 界面看着在转圈，其实是在干实事：进度条会报「正在识别第几张、到哪一步」。
     *
     * ## 失败怎么办（v0.0.19 修）
     *
     * 早先失败只写 logcat：进度条一消失，用户看到模型答非所问，
     * 没有任何线索；更糟的是**下一句话同一张图会被重新上传一次**
     * （没有文字 = 永远在待识别队列里），一句话烧一次额度。
     *
     * 现在每张图认真试过一次之后，无论成败都有交代：
     * - 成功 → 文字落库，模型看得见；
     * - 失败 → **失败说明落库**（[ChatOcrText.failureNote]）：
     *   不再重传、模型明确知道「这张图没读出来」、
     *   输入栏上方常驻一条带原因的通知（[ChatUiState.ocrNotice]），
     *   点「重试」可清掉说明重新排队。
     *
     * ## 为什么覆盖历史轮次的图
     *
     * 只补本轮的图片是不够的：用户经常隔几轮才问「刚才那张图」，
     * 那时那张图早就不在「本轮」里了。所以把会话里所有还没有
     * OCR 文字的图片都补一遍（已识别的靠缓存跳过，不重复烧额度）。
     */
    private suspend fun ensureImageOcr(sessionId: Long) {
        try {
            val settings = settingsStore.settings.first()
            if (!settings.mineruConfigured) {
                // 没配 MinerU Key 就别白跑一趟。用常驻通知而不是一次性 toast——
                // 这个原因不会自己消失，用户回到应用时还得看见它。
                _state.value = _state.value.copy(
                    ocrStage = null,
                    ocrNotice = "当前模型看不到图片，需要先用 MinerU 把图转成文字，" +
                        "但还没有配置 MinerU Key。去设置里配置后，可点右侧「重试」。"
                )
                return
            }
            val pending = repository.imageAttachmentsWithoutOcr(sessionId)
            if (pending.isEmpty()) {
                _state.value = _state.value.copy(ocrStage = null)
                return
            }
            val failureReasons = mutableListOf<String>()
            for ((index, attachment) in pending.withIndex()) {
                _state.value = _state.value.copy(
                    ocrStage = "正在用 MinerU 识别图片（${index + 1}/${pending.size}）…"
                )
                val file = java.io.File(attachment.localPath)
                if (!file.exists()) {
                    // 文件没了（被清理或没拷完）：记一条说明，
                    // 否则它会永远待在待识别队列里，每轮重传一次。
                    markOcrFailed(attachment, "图片文件不存在")
                    failureReasons += "图片文件不存在"
                    continue
                }
                // 本会话已认真试过的图不重传（正常不会走到这——失败说明已落库；
                // 这是落库失败时的兜底）
                if (imageOcr.alreadyTried(file)) continue
                val result = imageOcr.recognize(
                    sessionId = sessionId,
                    imageFile = file,
                    settings = settings,
                    onStage = { stage ->
                        _state.value = _state.value.copy(
                            ocrStage = "正在用 MinerU 识别图片（${index + 1}/${pending.size}）：$stage"
                        )
                    }
                )
                when (result) {
                    is com.mistakebook.net.ApiResult.Success -> {
                        if (result.data.isNotBlank()) {
                            repository.updateAttachmentOcr(attachment.id, result.data)
                        } else {
                            // 跑通了但没抽到字：同样记一笔，别让下一句话再烧一次额度
                            markOcrFailed(attachment, "MinerU 已返回，但图里没有识别出文字")
                            failureReasons += "图里没有识别出文字"
                        }
                    }

                    is com.mistakebook.net.ApiResult.Failure -> {
                        // 单张失败不中断后面的：一张图识别不出来，
                        // 不该让整会话的其他图都不识别。
                        val reason = result.error.serverMessage.ifBlank { "识别请求失败" }
                        Log.w(TAG, "图片 OCR 失败: ${attachment.fileName} / $reason")
                        markOcrFailed(attachment, reason)
                        failureReasons += reason
                    }
                }
            }
            _state.value = _state.value.copy(
                ocrStage = null,
                ocrNotice = if (failureReasons.isEmpty()) {
                    null
                } else {
                    buildOcrNotice(failureReasons)
                }
            )
        } catch (cancelled: CancellationException) {
            // 用户按了停止：进度条必须撤掉，不能卡在「正在识别」——
            // 撤了才重抛，state 写入不是挂起函数，取消态下照样能执行。
            _state.value = _state.value.copy(ocrStage = null)
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "图片 OCR 任务异常", t)
            _state.value = _state.value.copy(
                ocrStage = null,
                ocrNotice = buildOcrNotice(listOf(t.message ?: "识别任务异常"))
            )
        }
    }

    /** 把一条失败说明写进附件：不重传 + 模型知道 + 用户看得见。 */
    private suspend fun markOcrFailed(attachment: ChatAttachment, reason: String) {
        runCatching { repository.updateAttachmentOcr(attachment.id, ChatOcrText.failureNote(reason)) }
            .onFailure { Log.w(TAG, "写入 OCR 失败说明失败: ${attachment.fileName}", it) }
    }

    /** 通知条文案：第一张的原因 + 总数。MinerU 的文案可能很长，只取代表性的那条。 */
    private fun buildOcrNotice(reasons: List<String>): String {
        val first = reasons.firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
        val count = reasons.size
        return if (count <= 1) {
            "图片识别失败：$first 已如实告知模型这张图没能读出来；" +
                "网络或额度恢复后可点右侧「重试」。"
        } else {
            "$count 张图片识别失败，第一张的原因：$first " +
                "已如实告知模型这些图没能读出来；恢复后可点右侧「重试」。"
        }
    }

    /**
     * 重试识别失败的图片（通知条上的「重试」）。
     *
     * 网断了、额度没了导致的失败不该是终局：把失败说明清掉、
     * 撤掉内存里的「已尝试」标记，重新跑一遍 [ensureImageOcr]。
     * 全部成功则通知条自动消失。
     */
    fun retryOcr() {
        val sessionId = _state.value.sessionId
        // 流式进行中不重试：那一轮的请求早就带着现状发出去了，
        // 现在补识别只对下一轮有用，没理由和生成中的界面抢 attention。
        // 已经有一轮 OCR 在跑也同样不重试——两个 ensureImageOcr 并发
        // 会重复处理同一批图。
        if (sessionId == 0L || _state.value.isStreaming || ocrJob?.isActive == true) return
        viewModelScope.launch {
            _state.value = _state.value.copy(ocrNotice = null)
            try {
                val failed = repository.imageAttachmentsOf(sessionId)
                    .filter { ChatOcrText.isFailureNote(it.textExcerpt) }
                failed.forEach { attachment ->
                    repository.clearAttachmentOcr(attachment.id)
                    java.io.File(attachment.localPath)
                        .takeIf { it.exists() }
                        ?.let { imageOcr.forget(it) }
                }
                ocrJob = currentCoroutineContext()[Job]
                try {
                    ensureImageOcr(sessionId)
                } finally {
                    ocrJob = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "重试图片识别异常", t)
                _state.value = _state.value.copy(ocrStage = null)
            }
        }
    }

    /**
     * 收流并节流落库。
     *
     * 每收到一段就写一次库的话，一次回复能产生上千次写 + 上千次 Flow 发射，
     * 列表反复重组。节流到 [WRITE_THROTTLE_MS] 一次，肉眼看不出差别。
     */
    private suspend fun collectStream(
        profile: LlmProfile,
        outgoing: List<OutgoingMessage>,
        assistantId: Long
    ) {
        val request = ChatRequest(
            model = profile.model,
            messages = outgoing.map { it.toLlm() },
            temperature = ChatCompletionStream.TEMPERATURE,
            max_tokens = ChatCompletionStream.MAX_TOKENS,
            responseFormat = null,
            stream = true
        )

        val buffer = StringBuilder()
        val thinking = StringBuilder()
        val fullAnswer = StringBuilder()
        val fullThinking = StringBuilder()
        var lastWriteAt = 0L
        var lastPreviewAt = 0L
        var status = MessageStatus.DONE
        var errorText: String? = null
        var promptTokens = 0
        var completionTokens = 0
        var wasTruncated = false
        fun joinThinkingAndAnswer(answer: String, thought: String): String = when {
            thought.isBlank() -> answer
            answer.isBlank() -> ChatThinking.tags(thought)
            else -> ChatThinking.tags(thought) + "\n\n" + answer
        }

        suspend fun publishPreview() {
            val now = System.currentTimeMillis()
            if (now - lastPreviewAt < PREVIEW_THROTTLE_MS) return
            lastPreviewAt = now
            _state.value = _state.value.copy(
                streamingMessageId = assistantId,
                streamingContent = joinThinkingAndAnswer(fullAnswer.toString(), fullThinking.toString())
            )
        }

        /**
         * 把「攒着的增量」写进库。
         *
         * 思考过程与正文**用 `<think>` 标记隔开、共用一列**，而不是加两个数据库字段：
         * - 不需要 DB 迁移（迁移要写 SQL、升版本号，是这个项目最容易翻车的一步）；
         * - `<think>…</think>` 是 DeepSeek / Qwen 的**通用约定**，
         *   导出的对话在别家前端里也能被正确识别成思考过程。
         *
         * 正文先到、思考后到（或反过来）都能拼对：思考永远放在最前面。
         */
        suspend fun flush() {
            if (buffer.isEmpty() && thinking.isEmpty()) return
            buffer.setLength(0)
            thinking.setLength(0)
            lastWriteAt = System.currentTimeMillis()
            val fullContent = joinThinkingAndAnswer(fullAnswer.toString(), fullThinking.toString())
            repository.updateAssistantContent(assistantId, fullContent)
        }

        try {
            stream.complete(
                profile,
                request,
                settingsStore.settings.first().reasoningEffort
            ).collect { event ->
                when (event) {
                    is ChatStreamEvent.Thinking -> {
                        if (event.text.isNotEmpty()) {
                            thinking.append(event.text)
                            fullThinking.append(event.text)
                            publishPreview()
                            if (System.currentTimeMillis() - lastWriteAt >= WRITE_THROTTLE_MS) flush()
                        }
                    }

                    is ChatStreamEvent.Delta -> {
                        buffer.append(event.text)
                        fullAnswer.append(event.text)
                        publishPreview()
                        if (System.currentTimeMillis() - lastWriteAt >= WRITE_THROTTLE_MS) flush()
                    }

                    is ChatStreamEvent.Done -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                        // 输入 token 数是用户唯一能看到的「上下文占用」口径，
                        // 输到 state 上，输入栏显示「上次请求 ≈ N tokens」。
                        if (event.promptTokens > 0) {
                            _state.value = _state.value.copy(lastPromptTokens = event.promptTokens)
                        }
                        // 用量记账放在这里而不是 Done 事件的发送方：一次对话
                        // （无论流式还是降级非流式）收到且仅收到一次 Done，
                        // 天然就是「一次调用」的口径。失败 / 取消不会到这儿。
                        usageStatsStore.recordLlm(
                            promptTokens = event.promptTokens,
                            completionTokens = event.completionTokens,
                            cachedTokens = event.cachedTokens
                        )
                        // length = 被 max_tokens 截断。它和「模型胡乱输出」在下游一样，
                        // 但处理方式不同：要提示用户换个更大的模型或分次问。
                        wasTruncated = event.finishReason == FINISH_REASON_LENGTH
                        if (wasTruncated) truncatedMessageId = assistantId
                    }

                    is ChatStreamEvent.Failed -> {
                        status = if (event.error.kind == ApiErrorKind.CANCELLED) {
                            MessageStatus.CANCELED
                        } else {
                            MessageStatus.FAILED
                        }
                        errorText = event.error.serverMessage.takeIf { it.isNotBlank() }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // 用户按了停止：不是失败，保留已收到的内容。
            status = MessageStatus.CANCELED
            throw cancelled
        } finally {
            // 收尾必须放 finally：取消时 CancellationException 已经抛出去了，
            // 放在 try 之后的那段代码根本不会执行——
            // 表现是「按了停止，缓冲区里最后半句永久丢失，状态卡在生成中」。
            // NonCancellable 保证这一段能跑完。
            withContext(NonCancellable) {
                flush()
                // 最终预览先保留，等 Room 发出终态行后再清除，避免回退到旧内容。
                _state.value = _state.value.copy(
                    streamingMessageId = assistantId,
                    streamingContent = joinThinkingAndAnswer(fullAnswer.toString(), fullThinking.toString())
                )
                repository.finishAssistantMessage(
                    id = assistantId,
                    status = status,
                    errorMessage = errorText,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens
                )
            }
            // StateFlow 写入不是挂起函数，取消态下仍可执行。
            _state.value = _state.value.copy(
                isStreaming = false,
                truncated = wasTruncated,
                truncatedMessageId = if (wasTruncated) truncatedMessageId else null,
                canRetry = status == MessageStatus.FAILED
            )
            errorText?.let { _events.trySend(ChatEvent.Error(it)) }
        }
    }

    // ------------------------------------------------------------ 附件

    /**
     * 选了一个文件。
     *
     * 立刻插一条 [AttachmentStatus.PREPARING] 占位让用户看到反馈，
     * 处理在 IO 线程跑（复制 + 解码 + 抽文本都可能耗时几十到几百毫秒）。
     */
    fun onAttachmentPicked(uri: android.net.Uri) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L) return
        val localId = ++attachmentSeq
        val placeholder = PendingAttachment(
            id = localId,
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "",
            kind = AttachmentKind.OTHER,
            status = AttachmentStatus.PREPARING
        )
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments + placeholder
        )

        viewModelScope.launch {
            val longEdge = settingsStore.settings.first().imageLongEdgePx
            val result = preparer.prepare(sessionId, uri, longEdge)
            _state.value = _state.value.copy(
                pendingAttachments = _state.value.pendingAttachments.map {
                    if (it.id != localId) it
                    else PendingAttachment(
                        id = localId,
                        fileName = result.fileName,
                        kind = result.kind,
                        // 准备失败的文件仍留在列表里，用户能看到「处理失败」并移除；
                        // 直接从 UI 消失的话用户会以为是自己没选上
                        status = if (result.ok) AttachmentStatus.READY else AttachmentStatus.FAILED,
                        sizeBytes = result.sizeBytes,
                        extractedChars = result.extractedChars,
                        errorMessage = result.errorMessage,
                        prepared = result.takeIf { it.ok }
                    )
                }
            )
        }
    }

    fun removePendingAttachment(id: Long) {
        val target = _state.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filterNot { it.id == id }
        )
        // 落盘的文件要删掉，否则用户反复添加删除附件会不断占空间
        target.prepared?.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            viewModelScope.launch { runCatching { java.io.File(path).delete() } }
        }
    }

    // ------------------------------------------------------------ 其他

    fun dismissApiKeyGate() {
        _state.value = _state.value.copy(needsApiKey = false)
    }

    fun dismissTruncatedNotice() {
        _state.value = _state.value.copy(truncated = false)
    }

    fun notifyCopied() {
        viewModelScope.launch { _events.send(ChatEvent.Copied) }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch { repository.deleteMessage(id) }
    }

    fun renameSession(title: String) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L || title.isBlank()) return
        viewModelScope.launch {
            repository.renameSession(sessionId, title)
            _state.value = _state.value.copy(title = title.trim())
        }
    }

    // ------------------------------------------------------------ 转换

    private fun OutgoingMessage.toLlm() = com.mistakebook.net.llm.ChatMessage(
        role = when (role) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
            ChatRole.SYSTEM -> "system"
        },
        content = if (images.isEmpty()) {
            JsonPrimitive(text)
        } else {
            val parts = buildList {
                add(ChatContentPart(type = ChatContentPart.TYPE_TEXT, text = text.ifBlank { "（见下图）" }))
                images.forEach {
                    add(ChatContentPart(type = ChatContentPart.TYPE_IMAGE, imageUrl = ImageUrl(it)))
                }
            }
            json.parseToJsonElement(json.encodeToString(partSerializer, parts))
        }
    )

    private fun ChatMessage.attachmentIds(): List<Long> {
        if (attachmentIdsJson.isBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(attachmentIdsJson) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                runCatching { element.jsonPrimitive.content.toLongOrNull() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * 落库节流间隔。
         *
         * 500ms 的理由：一次流式回复通常持续十几秒，节流后是 20~30 次写库；
         * 再密就纯属浪费，因为用户分辨不出「每 50ms 更新」和「每 500ms 更新」。
         */
        const val WRITE_THROTTLE_MS = 500L
        /** 流式预览节流：220ms（约每秒 4~5 次）。
     *  原来是 120ms（每秒 8 次）——每刷新一次就要整篇重解析 markdown
     *  并重绘带公式的气泡，长回答下主线程吃不消，滑动明显掉帧。 */
    const val PREVIEW_THROTTLE_MS = 220L

        const val FINISH_REASON_LENGTH = "length"

        /**
         * 从题目页进入时的首问预填。**不自动发送**——
         * 自动发会白烧 token，而且用户十有八九想改问法。
         */
        const val PREFILL_QUESTION = "请讲解这道题，给出详细解题步骤、答案和易错点。"
    }
}
