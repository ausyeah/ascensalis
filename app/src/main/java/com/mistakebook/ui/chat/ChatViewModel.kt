package com.mistakebook.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.chat.ChatAttachmentPreparer
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatContextCompressor
import com.mistakebook.data.chat.ChatImageOcr
import com.mistakebook.data.chat.ChatOcrText
import com.mistakebook.data.chat.ChatPrompts
import com.mistakebook.data.chat.OutgoingMessage
import com.mistakebook.data.chat.PreparedAttachment
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.data.repos.ChatRepository
import com.mistakebook.data.repos.QuestionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import java.io.File
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
 * 气泡里非图片附件的最小展示信息（PDF/文档/其它）。
 *
 * 只带 UI 需要的字段，不把 [com.mistakebook.data.local.entities.ChatAttachment]
 * 的 textExcerpt 大段文本透进界面。
 */
data class BubbleFile(
    val localPath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val extractedChars: Int,
    val kind: AttachmentKind,
    /** 非空 = 这个附件处理失败，芯片按错误态展示。 */
    val errorMessage: String? = null
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

    /** 会话联网搜索开关（模型面板里可切，配置好了默认开）。 */
    val chatSearchEnabled: Boolean = true,

    /** 搜索出口：DeepSeek 官方搜索（默认）或 Tavily。 */
    val chatSearchProvider: ChatSearchProvider = ChatSearchProvider.DEEPSEEK,

    /** 首个 token 之前的阶段提示（如「正在联网搜索…」），正文开始流动后为 null。 */
    val generationStage: String? = null,

    /**
     * 消息 id -> 该消息里**非图片**附件（PDF/文档等）。
     *
     * 这些附件只抽文本喂模型，变不出缩略图——但气泡里什么都不渲染
     * 的话，用户看到的就是「发出去了却是空的」。以文件芯片呈现，
     * 点击交给系统对应的应用打开（本应用不做 PDF 阅读器）。
     */
    val bubbleFiles: Map<Long, List<BubbleFile>> = emptyMap(),

    /** 输入框内容。首问会预填但不自动发送。 */
    val input: String = "",

    /**
     * 手写过程批改模式（T13）。仅题目会话（questionId != null）由 UI 展示开关；
     * 自由会话恒 false。开关状态进 UiState，旋转屏不丢。
     */
    val gradingMode: Boolean = false,

    /** 还没发送的待发附件。 */
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /** T6 闄嶇骇锛氬凡閫夊緟娉ㄥ叆鐨勮祫鏂欏簱鏂囦欢鍚嶏紙鍙戦€佹椂鍓嶇疆涓哄綋杞笂涓嬫枃锛夈€?*/
    val libraryInjectionNames: List<String> = emptyList(),

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
    val ocrNotice: String? = null,

    /**
     * 当前 provider 供应的模型列表（供对话页切换）。
     *
     * 一个中转站常常挂着几十个模型，用户想在 App 里直接切着用，
     * 而不是为每个模型各建一套配置（URL 抄 N 遍）。
     */
    val modelOptions: List<String> = emptyList(),

    /**
     * 已配置的供应商（接入配置）列表，供对话页直接切换。
     *
     * 用户诉求：「切换模型不能跳转切换供应商……点开设置里面就是几个
     * 供应商列表，但是不能展开，可以自己选择哪个供应商来切换模型」。
     * 所以这里给的是**扁平列表 + 点选即换**，不是可展开的编辑表单
     * （编辑仍在设置页）。
     */
    val providerOptions: List<ChatProviderOption> = emptyList(),

    /** 当前生效的供应商 id（与设置页同一份数据源，切换后两边同步）。 */
    val activeProviderId: String? = null,

    /** 当前实际生效的模型名（选择面板与输入栏 chip 显示用）。 */
    val currentModel: String = "",
    /** 思考强度（模型面板右上角设置里可调，与设置页同一数据源）。 */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,

    /**
     * 本会话当前选中的模型。
     *
     * 空串 = 用配置里的默认模型。
     */
    val selectedModel: String = ""
)

/**
 * 对话页模型面板里的一个供应商条目。
 *
 * 只带 id 与显示名：面板要能点选切换，又不必把整套 LlmProfile
 * （URL、Key、路由表）塞进 UI 状态——那些是设置页的事。
 */
data class ChatProviderOption(
    val id: String,
    val name: String
)

/** 一次性事件。 */
sealed interface ChatEvent {
    data class Error(val text: String) : ChatEvent
    data object Copied : ChatEvent

    /** 一次性成功提示（如「已存入题目备注」）。 */
    data class Info(val text: String) : ChatEvent
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
    /** 题目备注写入（T13 批改结果「存入题目备注」）。 */
    private val questionRepository: QuestionRepository,
    private val stream: ChatCompletionStream,
    private val settingsStore: SettingsStore,
    private val usageStatsStore: UsageStatsStore,
    private val preparer: ChatAttachmentPreparer,
    private val compressor: ChatContextCompressor,
    private val imageOcr: ChatImageOcr,
    private val questionId: Long?,

    /** 从对话记录进来时直接指定会话；null = 新开或按题目定位。 */
    private val sessionId: Long? = null,

    /**
     * 应用上下文：启动后台生成服务（前台服务）需要它。
     *
     * 生成之所以要放到服务里，是因为 `viewModelScope` 的协程
     * 虽然能活过「切后台」，却活不过「进程被回收」——前台服务给进程
     * 前台优先级，模型才能在后台把答案跑完。
     */
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())

    /** T6 降级注入：@资料库 选中的文件，发送时把内容前置进当轮上下文后清空。 */
    private val pendingLibraryFiles = mutableListOf<File>()

    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val partSerializer = ListSerializer(ChatContentPart.serializer())

    private var streamJob: Job? = null

    /**
     * 本地「在途生成」标记。
     *
     * `isStreaming` 是后台服务通过 [ChatGenerationBus] 回报的，
     * 从点发送到服务回报之间有几毫秒窗口。这段时间里连点两下发送，
     * 会启动两个服务、插两条助手消息、生成两遍——所以本地自己记一份。
     */
    private var generationActive = false

    /**
     * 当前在途生成对应的助手消息 id（**独立于** state 里的字段）。
     *
     * state.streamingMessageId 会在消息落库终态时被清空，用它做追踪
     * 会漏掉「快照丢了但数据库已终态」这一情形，所以这里单独记一份。
     */
    private var trackedStreamingId: Long? = null

    /** 本次生成开始的时间戳（毫秒），用于「消息迟迟没进库」的兜底判定。 */
    private var streamingStartedAt: Long = 0L

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
        // 思考强度 / 供应商列表跟随全局设置：模型选择面板的「设置」里可以直接调，
        // 与设置页共用同一份数据源（SettingsStore），调完下一次发送即生效。
        // 供应商列表也在这里同步——设置页增删配置、或对话页自己切换供应商后，
        // 两边看到的是同一份。
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                val profile = s.activeProfile
                _state.value = _state.value.copy(
                    reasoningEffort = s.reasoningEffort,
                    chatSearchEnabled = s.chatSearchEnabled,
                    chatSearchProvider = s.chatSearchProvider,
                    providerOptions = s.llmProfiles.map { p ->
                        ChatProviderOption(id = p.id, name = p.displayName())
                    },
                    activeProviderId = s.activeProfileId,
                    modelOptions = profile?.availableModels().orEmpty(),
                    // 芯片上显示的名字：用户切过就用切后的，否则用配置的默认模型
                    currentModel = _state.value.selectedModel.ifBlank { profile?.model.orEmpty() }
                )
            }
        }
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
            //
            // **但如果此刻真有生成在跑**（后台服务还活着），那条消息不是「陈旧」的——
            // 它正在被写入。此时把它标成 CANCELED 会把一次正常进行中的生成
            // 误判为中断（用户退出再回来就会看到）。
            if (ChatGenerationBus.snapshot.value?.finished != false) {
                repository.recoverStaleStreaming(session.id)
            }
            if (questionId != null) {
                repository.ensureQuestionContext(session.id, questionId)
            }
            _state.value = _state.value.copy(
                sessionId = session.id,
                questionId = session.questionId,
                title = session.title
            )
            // **恢复输入草稿**。
            //
            // 用户打好的字退出会话再进来就没了——这是纯丢失，没有任何补救。
            // 草稿按会话存，恢复时机在 `loading=false` 之前：
            // 界面里的「首问预填」逻辑判的是 `messages.isEmpty()`，
            // 如果草稿先恢复，预填就不会覆盖掉用户已经写的内容。
            val draft = runCatching { settingsStore.getChatDraft(session.id) }.getOrDefault("")
            if (draft.isNotEmpty() && _state.value.input.isBlank()) {
                _state.value = _state.value.copy(input = draft)
            }
            // 模型列表：配置里可能有多个模型（一个 provider 供应多种模型），
            // 对话页要能直接切。
            runCatching { refreshModelOptions() }
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
                // **兜底复位**：数据库里那条助手消息已经落到终态
                // （DONE / FAILED / CANCELED），说明生成确实结束了。
                //
                // 为什么必须要有这条兜底：界面上「停止」按钮显示与否完全取决于
                // `isStreaming`，而它只靠总线快照复位。总线是**进程内**的
                // StateFlow —— 进程被系统回收后重建、或快照被下一轮覆盖时，
                // `finished=true` 的终态快照可能永远送不到这个 ViewModel，
                // 于是 `isStreaming` 永久停在 true：界面卡在「停止」按钮上，
                // 而且 `send()` 的守卫会把用户的消息直接吞掉。
                // 这正是用户报的「输出已经结束，却卡在终止按钮不让我发」。
                //
                // 数据库是唯一可信的事实来源，所以用它兜底。
                val tracked = trackedStreamingId
                val trackedMsg = tracked?.let { id -> messages.firstOrNull { it.id == id } }
                val settled = trackedMsg != null && trackedMsg.status != MessageStatus.STREAMING
                // 消息根本没进库（服务还没插上）也认为还没在跑，但要给服务
                // 一点起跑时间——否则会在启动瞬间误判成「已结束」。
                val vanished = tracked != null && trackedMsg == null &&
                    System.currentTimeMillis() - streamingStartedAt > 1500L
                if ((settled || vanished) && (current.isStreaming || generationActive)) {
                    generationActive = false
                    trackedStreamingId = null
                    _state.value = _state.value.copy(isStreaming = false)
                }
                loadBubbleImages(messages)
            }
        }

        // 订阅后台生成的实时预览。
        //
        // 生成跑在 [ChatGenerationService]（前台服务）里——它不随界面销毁，
        // 所以用户切后台、甚至划掉任务，模型照样把答案跑完并落库。
        // 这里订阅总线，把增量内容盖到那条助手消息上（打字机效果），
        // 并在终态时更新「可重试 / 被截断 / 输入 token 数」。
        viewModelScope.launch {
            ChatGenerationBus.snapshot.collect { snap ->
                if (snap == null) return@collect
                val current = _state.value
                // **会话隔离**：总线是进程内单例，会话 B 的界面也订阅着它。
                // 快照带 sessionId 时只认自己会话的；不带（0）则退回老的
                // 「按消息 id 归属」逻辑。
                if (snap.sessionId != 0L && current.sessionId != 0L &&
                    snap.sessionId != current.sessionId
                ) {
                    return@collect
                }
                val currentId = current.streamingMessageId
                // 归属校验：只处理「当前这条消息」的快照。
                // - 当前有生成中消息 → 只认同 id 的快照；
                // - 当前没有生成中消息 → 只认「刚开始」的快照（finished=false），
                //   已结束的旧快照（上一轮残留）直接忽略，否则会拿它去覆盖
                //   本轮的状态（把「可重试」「被截断」写到不相干的会话上）。
                val mine = if (currentId != null) {
                    currentId == snap.assistantId
                } else {
                    !snap.finished
                }
                if (!mine) return@collect
                if (snap.finished) {
                    generationActive = false
                    trackedStreamingId = null
                    _state.value = current.copy(
                        isStreaming = false,
                        streamingContent = snap.content,
                        generationStage = null,
                        truncated = snap.truncated,
                        truncatedMessageId = if (snap.truncated) snap.assistantId else null,
                        canRetry = snap.failed,
                        lastPromptTokens = if (snap.promptTokens > 0) snap.promptTokens
                        else current.lastPromptTokens
                    )
                    // **自动压缩（500k 阈值）**：上次请求的输入 token 数到线就压，
                    // 不等用户点。防连发：只有上下文重新长过上一次触发时的量
                    // 才再次触发（压缩后下一轮请求必然大幅回落，不会连环压）。
                    if (snap.promptTokens >= AUTO_COMPRESS_TOKENS &&
                        snap.promptTokens > lastAutoCompressTokens
                    ) {
                        lastAutoCompressTokens = snap.promptTokens
                        viewModelScope.launch { compressContext() }
                    }
                    snap.errorText?.let { _events.trySend(ChatEvent.Error(it)) }
                } else {
                    generationActive = true
                    trackedStreamingId = snap.assistantId
                    _state.value = current.copy(
                        isStreaming = true,
                        streamingMessageId = snap.assistantId,
                        streamingContent = snap.content,
                        generationStage = snap.stage
                    )
                }
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
        // 附属在消息上的图片有两类来源：用户发的（attachmentIdsJson 登记过）
        // 与助手生成的（生图工具产物，附件表里按 messageId 直接挂着）。
        // 所以不能只筛 USER 消息，否则生图结果永远不出现在气泡里。
        val targets = messages.filter { it.attachmentIdsJson.isNotBlank() || it.role != ChatRole.USER }
        if (targets.isEmpty()) {
            if (_state.value.bubbleImages.isNotEmpty() || _state.value.bubbleFiles.isNotEmpty()) {
                _state.value = _state.value.copy(bubbleImages = emptyMap(), bubbleFiles = emptyMap())
            }
            return
        }
        bubbleImageJob?.cancel()
        bubbleImageJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = mutableMapOf<Long, List<String>>()
            val loadedFiles = mutableMapOf<Long, List<BubbleFile>>()
            // 一次查完整个会话的附件：逐条查在长会话里是几十次 DB 往返
            val sessionId = _state.value.sessionId
            val byMessage = runCatching { repository.attachmentsOfSession(sessionId) }
                .getOrDefault(emptyList())
                .groupBy { it.messageId }
            targets.forEach { message ->
                val attachments = byMessage[message.id].orEmpty().ifEmpty {
                    // 兼容旧数据：只在 attachmentIdsJson 里登记过的附件
                    message.attachmentIds().mapNotNull { repository.attachmentById(it) }
                }
                val urls = attachments
                    .filter { it.kind == AttachmentKind.IMAGE && it.status == AttachmentStatus.READY }
                    .mapNotNull { attachment -> repository.imageDataUrlOf(attachment) }
                if (urls.isNotEmpty()) loaded[message.id] = urls
                // 非图片附件：PDF/文档/其它，气泡里以文件芯片呈现（点击系统打开）。
                // 处理失败的也带上——用户需要看到「这个附件没处理成功」而不是空白。
                val files = attachments
                    .filter { it.kind != AttachmentKind.IMAGE }
                    .map {
                        BubbleFile(
                            localPath = it.localPath,
                            fileName = it.fileName,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                            extractedChars = it.extractedChars,
                            kind = it.kind,
                            errorMessage = it.errorMessage
                        )
                    }
                if (files.isNotEmpty()) loadedFiles[message.id] = files
            }
            // 中途可能已经切走或又发了一条，以最新的消息列表为准
            val current = _state.value.messages.map { it.id }.toSet()
            _state.value = _state.value.copy(
                bubbleImages = loaded.filterKeys { it in current },
                bubbleFiles = loadedFiles.filterKeys { it in current }
            )
        }
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
        // 落草稿：退出再进来时能恢复。
        // 写 DataStore 是异步 IO，放到协程里，别卡住输入框。
        val sessionId = _state.value.sessionId
        if (sessionId <= 0L) return
        viewModelScope.launch {
            runCatching { settingsStore.setChatDraft(sessionId, text) }
        }
    }

    // ------------------------------------------------------------ 发送

    /**
     * 切换本会话用的模型。
     *
     * 一个 provider（尤其是中转站）供应多个模型，用户在对话页直接切，
     * 不用为每个模型各建一套配置。空串 = 用配置里的默认模型。
     */
    fun selectModel(model: String) {
        _state.value = _state.value.copy(selectedModel = model, currentModel = model)
    }

    /**
     * 切换供应商（接入配置）：对话页内直接换，**不跳设置页**。
     *
     * 写的是全局 activeProfileId（与设置页的 RadioButton 同一个字段），
     * 所以两边永远同步。切换后清空 [ChatUiState.selectedModel]——
     * 旧供应商的模型名在新供应商里大概率不存在，继续带着它只会
     * 让下一次请求 404；清空即「用新供应商的默认模型」。
     *
     * 设置里没有配置任何供应商时给一条提示，别让用户点了没反应。
     */
    fun selectProvider(profileId: String) {
        if (_state.value.activeProviderId == profileId) return
        // 先清选择再落盘：落盘会触发 settings 流重发，届时 currentModel
        // 会用新供应商的默认模型重算，不会读到旧的 selectedModel。
        _state.value = _state.value.copy(selectedModel = "")
        viewModelScope.launch {
            settingsStore.setActiveProfileId(profileId)
        }
    }

    /** 调整思考强度：写全局设置，下一次发送生效。 */
    fun setReasoningEffort(effort: ReasoningEffort) {
        viewModelScope.launch { settingsStore.setReasoningEffort(effort) }
    }

    /**
     * 刷新「当前 provider 供应哪些模型」。
     *
     * 从设置页返回时配置可能变了（新增了 key、填了模型列表），
     * 所以进对话页时拉一次快照。
     */
    private suspend fun refreshModelOptions() {
        val snapshot = settingsStore.settings.first()
        val profile = snapshot.activeProfile ?: return
        val options = profile.availableModels()
        _state.value = _state.value.copy(
            modelOptions = options,
            // 芯片上显示的名字：用户切过就用切后的，否则用配置的默认模型
            currentModel = _state.value.selectedModel.ifBlank { profile.model }
        )
    }

    /**
     * 切换手写过程批改模式（T13）。仅题目会话的 UI 会暴露这个开关，
     * 自由会话不展示、状态恒 false。
     */
    fun toggleGrading() {
        _state.value = _state.value.copy(gradingMode = !_state.value.gradingMode)
    }

    /**
     * 把一条助手回复存入题目备注（T13）。
     *
     * 追加而不是覆盖：`updateNote` 会 trim，这里自己拼好换行——
     * 用户此前的笔记一个字都不能丢。
     *
     * ## 只存答案，不存思考（v0.0.56 用户反馈）
     *
     * 早先直接 `message.content.trim().take(2000)`，两个问题：
     * 1. **把思考过程也存进去了**。消息正文与思考过程在流式层是分开的两路
     *    （见 `SseLineParser.Delta.thinking`），但落库时拼在同一条 content 里，
     *    于是备注里一大段「让我想想…」——备注是给人回头看思路的，不是复盘
     *    模型的内心独白。
     * 2. **2000 字硬截断**。备注没有字数限制（Room 里是 TEXT），截断只会
     *    让用户存进去半句话，还以为自己存错了。
     *
     * 现在：先剥掉思考段，再原样存全文。
     *
     * ## 没有静默出口（用户反馈「点了没反应」后补的）
     *
     * 每个走不下去的分支都给 snackbar 说明；正常情况下自由会话
     * 根本不显示这个按钮（入口在 ChatScreen 按 questionId 门控），
     * 这里的防御分支只是兜底。
     */
    fun saveToNote(messageId: Long) {
        val qid = questionId
        if (qid == null) {
            _events.trySend(ChatEvent.Info("只有题目会话才能存入备注"))
            return
        }
        viewModelScope.launch {
            val message = repository.findMessageById(messageId)
            if (message == null) {
                _events.trySend(ChatEvent.Error("这条消息已不存在，无法存入备注"))
                return@launch
            }
            // 只存正文：思考段用 ChatThinking 的同一套规则剥掉（见方法注释）。
            // `answerOf` 就是界面渲染正文用的那个函数——这样「备注里存的」
            // 与「气泡里显示的」必然是同一份文本，不会各自为政。
            val body = ChatThinking.answerOf(message.content).trim()
            if (body.isBlank()) {
                _events.trySend(ChatEvent.Info("这条回复没有内容可存"))
                return@launch
            }
            val existing = questionRepository.findById(qid)?.note.orEmpty()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val merged = buildString {
                if (existing.isNotBlank()) {
                    append(existing.trim())
                    append("\n\n")
                }
                append("[AI 批改 $stamp]")
                append("\n")
                append(body)
            }
            questionRepository.updateNote(qid, merged)
            _events.trySend(ChatEvent.Info("已存入题目备注"))
        }
    }

    fun send() {
        val current = _state.value
        // `generationActive` 是**本地**的在途守卫：`isStreaming` 由后台服务
        // 通过总线回报，服务启动到回报之间有几毫秒窗口——用户在这窗口里
        // 连点两下发送，就会启动两个服务、插两条助手消息、生成两遍。
        if (current.isStreaming || current.loading || generationActive) {
            // **僵尸守卫自愈**：本地标记说「在生成」，但服务那边其实没有
            // 活着的任务（进程被杀、快照丢失、异常路径没清干净）。
            // 这时必须解锁，否则用户的消息被静默吞掉——
            // 表现就是「输出早结束了，却卡在停止按钮上不让我发」。
            // 刚发起的几秒内服务可能还没登记，别急着解锁（否则会双发）。
            val since = System.currentTimeMillis() - streamingStartedAt
            if (generationActive && !ChatGenerationService.isRunning() && since > 8000L) {
                generationActive = false
                trackedStreamingId = null
                if (current.isStreaming) {
                    _state.value = current.copy(isStreaming = false)
                }
            } else {
                return
            }
        }
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
            // 草稿已随消息发出，清掉（否则下次进会话会把这句话又填回来）。
            val sessionId = current.sessionId
            if (sessionId > 0L) {
                viewModelScope.launch {
                    runCatching { settingsStore.setChatDraft(sessionId, "") }
                }
            }

            // ---- T13 批改模式分流 ----
            // 批改不是独立页面，是对话的一种发送模式：普通文字/附件照旧，
            // 开关开启时才走下面这条「先 OCR、再拼文、后注入指令」的路。
            var messageText = text
            if (current.gradingMode) {
                val images = ready
                    .mapNotNull { it.prepared }
                    .filter { it.kind == AttachmentKind.IMAGE && it.ok }
                if (images.isEmpty()) {
                    _events.trySend(ChatEvent.Error("批改模式需要附上手写过程照片"))
                    return@launch
                }
                if (messageText.isBlank()) {
                    // 输入框为空时给个默认问法（用户可改）
                    messageText = "请批改我的解题过程，指出从哪一步开始错"
                }
                // OCR 总是执行——**不依赖模型是否支持图片**：纯文本模型靠
                // 拼进消息的 OCR 文本也能批；多模态模型图+文双保险，且公式
                // 经 MinerU 转成 LaTeX 比视觉模型看图更准。
                // 失败不阻断发送：图片按现有机制照发，消息前加一行小字说明。
                val ocrTexts = mutableListOf<String>()
                var ocrFailed = false
                val settings = settingsStore.settings.first()
                images.forEachIndexed { index, prepared ->
                    _state.value = _state.value.copy(
                        ocrStage = "正在识别手写过程…（${index + 1}/${images.size}）"
                    )
                    when (
                        val r = imageOcr.recognize(
                            sessionId = current.sessionId,
                            imageFile = File(prepared.localPath),
                            settings = settings,
                            onStage = { stage ->
                                _state.value = _state.value.copy(ocrStage = "正在识别手写过程…：$stage")
                            }
                        )
                    ) {
                        is com.mistakebook.net.ApiResult.Success ->
                            if (r.data.isNotBlank()) ocrTexts += r.data
                        is com.mistakebook.net.ApiResult.Failure -> ocrFailed = true
                    }
                }
                _state.value = _state.value.copy(ocrStage = null)

                val sb = StringBuilder(messageText)
                if (ocrFailed) {
                    sb.insert(
                        0,
                        "手写过程未能 OCR 为文字，请确保模型支持图片，或到设置配置 MinerU\n\n"
                    )
                }
                if (ocrTexts.isNotEmpty()) {
                    sb.append("\n\n【手写过程识别文本】\n")
                    ocrTexts.forEach {
                        sb.append(it.trim()).append("\n\n")
                    }
                }
                messageText = sb.toString().trimEnd()
            }

            // T6 降级注入：@资料库 选中的文件内容（50KB 截断）前置为本轮上下文。
            // 标签包住来源，模型能区分「资料原文」与「用户问题」。
            if (pendingLibraryFiles.isNotEmpty()) {
                val sb = StringBuilder(messageText)
                pendingLibraryFiles.forEach { f ->
                    val (content, truncated) =
                        com.mistakebook.data.library.LibraryStore.read(f) ?: return@forEach
                    val note = if (truncated) "（内容超 50KB 已截断）" else ""
                    sb.insert(0, "\n\n")
                    sb.insert(0, content)
                    sb.insert(0, note)
                    sb.insert(0, "【资料库文件：${f.name}】\n")
                }
                messageText = sb.toString().trim()
                pendingLibraryFiles.clear()
            }

            runOnce(
                profile = profile,
                text = messageText,
                attachments = ready.mapNotNull { it.prepared },
                isRetry = false,
                grading = current.gradingMode
            )
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
        // 生成跑在服务里：停止 = 让服务取消这次任务（内容保留、状态记为已取消）
        ChatGenerationService.stop(appContext)
        streamJob?.cancel()
        // OCR 阶段还没有 streamJob 可停，这里一并取消，
        // 否则用户点「停止」后 MinerU 还在后台烧额度。
        ocrJob?.cancel()
        // 本地立即解锁：服务那边取消是异步的，如果这中间用户就点了发送，
        // 守卫会把消息吞掉。服务最终还会落一条 CANCELED 终态消息，
        // 界面随后从数据库读到，不影响正确性。
        generationActive = false
        trackedStreamingId = null
        if (_state.value.isStreaming) {
            _state.value = _state.value.copy(isStreaming = false)
        }
    }

    private suspend fun runOnce(
        profile: LlmProfile,
        text: String,
        attachments: List<PreparedAttachment>,
        isRetry: Boolean,
        /** 手写过程批改模式（T13）：true 时向题目上下文注入批改指令。 */
        grading: Boolean = false
    ) {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L) return

        // **模型覆盖**：用户在对话页切了模型（一个 provider 供应多个模型）。
        // 只改 model 字段，URL / Key 池 / 看图能力都沿用当前配置——
        // 换模型时请求照样发到同一个中转站，由 resolveKey 按模型名挑 key。
        val selected = _state.value.selectedModel
        val effectiveProfile = if (selected.isNotBlank() && selected != profile.model) {
            profile.copy(model = selected)
        } else {
            profile
        }

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
        if (!effectiveProfile.supportsVision) {
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

        // 这里只取「省略了多少轮历史」，真正的请求上下文由**服务**构建。
        //
        // 为什么不在这里构建后传过去：助手消息（status=STREAMING）是在
        // 构建上下文**之后**才插入的——顺序反了的话，那条空的助手消息
        // 会被算进历史，模型会收到一条莫名其妙的空回复。
        // 让服务在插入助手消息之前自己构建，顺序天然正确。
        val context = repository.buildContext(
            sessionId = sessionId,
            questionId = questionId,
            supportsVision = effectiveProfile.supportsVision,
            visionToolAvailable = settingsStore.snapshotNow().visionConfigured,
            ocrToolAvailable = settingsStore.snapshotNow().mineruConfigured,
            // 批改指令与题目上下文拼进同一条 system 消息（ChatRepository）；
            // 不开批改时传空串——普通对话的上下文与 v0.0.33 逐字一致
            gradingInstruction = if (grading) ChatPrompts.GRADING_INSTRUCTION else ""
        )
        _state.value = _state.value.copy(
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

        // **生成交给前台服务跑**：切后台 / 划掉任务时进程不会被回收，
        // 模型照样把答案跑完并落库（用户诉求：「让它在后台把它跑完」）。
        // 服务负责插助手消息、收流与落库，实时预览通过 [ChatGenerationBus] 回到界面。
        generationActive = true
        // 记下起点：助手消息是服务启动后才插库的，这里给「迟迟没进库」
        // 的兜底判定一个参照点。
        streamingStartedAt = System.currentTimeMillis()
        runCatching {
            ChatGenerationService.start(
                context = appContext,
                sessionId = sessionId,
                questionId = questionId,
                model = effectiveProfile.model
            )
        }.onFailure {
            // 起不来（比如系统拦了前台服务启动）：解除在途标记并提示，
            // 否则界面永远停在「没有生成也没有错误」的怪状态。
            generationActive = false
            _events.trySend(ChatEvent.Error("无法启动后台生成：${it.message ?: it.javaClass.simpleName}"))
        }
        // 停止动作靠通知上的「停止」按钮，或界面上的停止按钮（见 [stop]）。
        // 这里不再 join：界面生命周期与生成解耦，界面先走也没关系。
    }

    /** 开关会话联网搜索（模型面板）。写入即生效，下一轮对话带上。 */
    fun setChatSearchEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.setChatSearchEnabled(value) }
    }

    /** 切换搜索出口（DeepSeek / Tavily）。 */
    fun setChatSearchProvider(provider: ChatSearchProvider) {
        viewModelScope.launch { settingsStore.setChatSearchProvider(provider) }
    }

    /**
     * 手动/自动压缩上下文：把旧轮次滚成一份摘要。
     *
     * ## 每个结局都有反馈（用户反馈「点了没反应」后加的）
     *
     * 早期版本把 compress 的四个早退（模型未配置/历史太短/无可压内容/空响应）
     * 和异常**全部静默吞掉**，用户点完按钮界面纹丝不动，不知道是成功、
     * 在压缩还是压根没做事。现在：
     * - 成功 → snackbar「已生成上文摘要…」+ 状态条出现「上文摘要生效中」；
     * - 没配模型 / 历史太短 → snackbar 说明原因；
     * - 失败 → snackbar「压缩失败：<原因>」。
     *
     * 进行中连点直接返回，避免生成多份互相覆盖的摘要；
     * 进行中按钮本身会变成「压缩中…」并禁用。
     */
    /** 上次自动压缩时的输入 token 数：只有上下文**重新长过**它才会再压，防止每一轮都触发。 */
    private var lastAutoCompressTokens = 0

    fun compressContext() {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L || _state.value.compressing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(compressing = true)
            try {
                val profile = settingsStore.settings.first().activeProfile
                if (profile == null || !profile.isConfigured()) {
                    _state.value = _state.value.copy(compressing = false)
                    _events.trySend(ChatEvent.Info("未配置大模型，无法压缩上下文"))
                    return@launch
                }
                val summary = compressor.compress(sessionId, profile)
                _state.value = _state.value.copy(
                    compressing = false,
                    summaryActive = summary != null || _state.value.summaryActive
                )
                if (summary != null) {
                    _events.trySend(ChatEvent.Info("已生成上文摘要，更早的对话已压缩进去"))
                } else {
                    _events.trySend(ChatEvent.Info("历史还很短，暂时没有可压缩的内容"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.value = _state.value.copy(compressing = false)
                _events.trySend(ChatEvent.Error("压缩失败：${t.message ?: t.javaClass.simpleName}"))
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

            // **会话里不再自动预识别**（v0.0.49）。
            //
            // 用户诉求：「会话里的逻辑不一样，mineru 速度很慢，会话里这些都
            // 作为工具可以调用，模型自己决定用不用工具」。
            //
            // 这里只做一件事：**确认模型有办法读到图**。
            // - 模型支持识图：图片本体直接随请求发出，不需要任何预处理；
            // - 模型不识图但配了识图模型 / MinerU：它们分别是
            //   describe_image / ocr_image 工具，模型按需调用；
            // - 两者都没有：模型物理上读不到图，只能如实告诉用户。
            //
            // 关键的一点：**图片附件仍然会进请求上下文**（见
            // `ChatRepository.attachmentManifest`），模型知道有图、
            // 知道该调哪个工具——早先这里预跑一次 MinerU，模型既看不到图、
            // 也拿不到工具，纯属白烧一次按页计费的额度。
            val profile = settings.activeProfile
            val modelCanSee = profile?.supportsVision == true
            val hasVisionTool = settings.visionConfigured
            val hasOcrTool = settings.mineruConfigured
            val pending = repository.imageAttachmentsWithoutOcr(sessionId)

            if (modelCanSee || pending.isEmpty()) {
                _state.value = _state.value.copy(ocrStage = null, ocrNotice = null)
                return
            }
            if (hasVisionTool || hasOcrTool) {
                // 有工具可用：交给模型自己决定要不要读。
                _state.value = _state.value.copy(ocrStage = null, ocrNotice = null)
                return
            }
            // 没有任何一条能读到图的路径：如实告诉用户，别让模型对着空气瞎猜。
            _state.value = _state.value.copy(
                ocrStage = null,
                ocrNotice = "当前模型（${profile?.model ?: "未配置"}）看不到图片，" +
                    "也没有配置识图模型或 MinerU，无法读取图片内容。" +
                    "请到设置里配置识图模型（设置 → 大模型 → 识图模型），" +
                    "或换用支持识图的模型。"
            )
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(ocrStage = null)
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "图片可读性检查异常", t)
            _state.value = _state.value.copy(ocrStage = null)
        }
    }

    /**
     * 重试识别失败的图片（通知条上的「重试」）。
     *
     * 会话里图片由**工具**按需读取，不再有「待识别队列」——
     * 但工具调用本身可能失败（网络、额度）。这个入口把失败说明清掉，
     * 让模型下一轮能重新调工具。
     */
    fun retryOcr() {
        val sessionId = _state.value.sessionId
        // 流式进行中不重试：那一轮的请求早就带着现状发出去了，
        // 现在补识别只对下一轮有用，没理由和生成中的界面抢 attention。
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "重试图片识别异常", t)
                _state.value = _state.value.copy(ocrStage = null)
            }
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

    /** T6 降级：把资料库文件加入待注入列表（发送时前置为当轮上下文）。 */
    fun addLibraryInjection(file: File) {
        if (file.isFile && pendingLibraryFiles.none { it.absolutePath == file.absolutePath }) {
            pendingLibraryFiles += file
            _state.value = _state.value.copy(
                libraryInjectionNames = pendingLibraryFiles.map { it.name }
            )
        }
    }

    /** 移除一个待注入的资料库文件。 */
    fun removeLibraryInjection(file: File) {
        pendingLibraryFiles.removeAll { it.absolutePath == file.absolutePath }
        _state.value = _state.value.copy(
            libraryInjectionNames = pendingLibraryFiles.map { it.name }
        )
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

        /**
         * 「是否压缩」按钮的出现阈值（按上次请求的输入 token 数）。
         *
         * 用户约定：200k 之前压缩按钮不出现——短会话没有压缩价值，
         * 常驻按钮只会诱导人去按；到 200k 才交由用户决定压不压。
         */
        const val MANUAL_COMPRESS_TOKENS = 200_000

        /**
         * 自动压缩阈值。到 500k 不再等用户点，直接压——
         * 这个量级的上下文再拖下去请求体与费用都会失控，
         * 自动压缩是安全网不是可选项。
         */
        const val AUTO_COMPRESS_TOKENS = 500_000
    }
}
