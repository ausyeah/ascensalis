package com.mistakebook.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.chat.ChatAttachmentPreparer
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
    val canRetry: Boolean = false
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
                    streamingMessageId = if (committedStream != null) null else current.streamingMessageId,
                    streamingContent = if (committedStream != null) null else current.streamingContent,
                    canRetry = messages.lastOrNull()?.status == MessageStatus.FAILED
                )
                loadBubbleImages(messages)
            }
        }
    }

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
        val context = repository.buildContext(sessionId, questionId)
        val assistantId = repository.insertStreamingAssistantMessage(sessionId, profile.model)
        _state.value = _state.value.copy(
            isStreaming = true,
            streamingMessageId = assistantId,
            streamingContent = "",
            omittedCount = context.omittedCount,
            canRetry = false,
            truncated = false
        )

        val job = viewModelScope.launch { collectStream(profile, context.messages, assistantId) }
        streamJob = job
        job.join()
        streamJob = null
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
            stream.complete(profile, request).collect { event ->
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
        /**
         * 落库节流间隔。
         *
         * 500ms 的理由：一次流式回复通常持续十几秒，节流后是 20~30 次写库；
         * 再密就纯属浪费，因为用户分辨不出「每 50ms 更新」和「每 500ms 更新」。
         */
        const val WRITE_THROTTLE_MS = 500L
        const val PREVIEW_THROTTLE_MS = 120L

        const val FINISH_REASON_LENGTH = "length"

        /**
         * 从题目页进入时的首问预填。**不自动发送**——
         * 自动发会白烧 token，而且用户十有八九想改问法。
         */
        const val PREFILL_QUESTION = "请讲解这道题，给出详细解题步骤、答案和易错点。"
    }
}
