package com.mistakebook.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mistakebook.MainActivity
import com.mistakebook.MistakeBookApp
import com.mistakebook.R
import com.mistakebook.data.chat.OutgoingMessage
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatContentPart
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ChatStreamEvent
import com.mistakebook.net.llm.ImageUrl
import com.mistakebook.net.llm.SearchTools
import com.mistakebook.net.llm.MediaTools
import java.io.File
import com.mistakebook.net.llm.ToolLoopPolicy
import com.mistakebook.net.llm.ToolRoundState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * 后台生成（前台服务）。
 *
 * ## 为什么必须有它
 *
 * 用户反馈：「处于后台就停止模型输出的问题仍然没有解决……不要求你能够
 * 继续，只要求你能让它在后台把它跑完」。
 *
 * 根因不在网络，而在**进程优先级**：
 * - 流式请求原本跑在 `viewModelScope` 里。切后台时 Activity 只是 `stop`，
 *   ViewModel 与协程都还活着——但**进程随时会被系统回收**（后台无服务、
 *   内存吃紧时先杀它）。进程一死，正在收的流直接断，模型那半截回答就废了。
 * - 退出 App（划掉任务）时 ViewModel 被销毁，协程被取消，生成彻底停掉。
 *
 * 前台服务解决这两点：进程因此拿到**前台优先级**，系统不会随手回收；
 * 而服务不随界面销毁——**用户划掉任务，生成照跑，跑完落库**，
 * 回来打开会话就能看到完整答案。
 *
 * ## 职责划分
 *
 * - 生成（请求、收流、落库）在本服务里；
 * - 实时预览通过 [ChatGenerationBus] 交给界面；
 * - 最终内容以数据库为准，界面本来就在观察 Room 的消息流，
 *   所以「生成完成 → 界面出现完整消息」是自动的，不需要额外通知。
 *
 * ## 通知为什么常驻
 *
 * 它不是「一条通知」，而是「用户主动发起的一次持续任务」——
 * 和番茄钟同理（见 `PomodoroService`）：静默、常驻、带一个「停止」动作，
 * 让用户随时能掐掉这次生成。
 */
class ChatGenerationService : Service() {

    /** 服务自己的作用域：与界面生命周期无关，界面死了它照样跑。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前这次生成的任务；「停止」动作取消它。 */
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 用**进程级**的 job，而不是本实例的 job：
                // 停止动作到达时，服务实例可能已被系统重建，
                // 本实例的 job 是新的空壳，取消它等于什么都没停。
                currentJob?.cancel()
                currentJob = null
                runningSessionId = 0L
                return START_NOT_STICKY
            }
        }

        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L
        val questionId = intent?.getLongExtra(EXTRA_QUESTION_ID, -1L) ?: -1L
        // 本次请求用的模型；空表示用配置里的默认模型
        val modelOverride = intent?.getStringExtra(EXTRA_MODEL)?.takeIf { it.isNotBlank() }
        if (sessionId == 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 必须先 startForeground 再做耗时工作：
        // Android 要求 startForegroundService 后 5 秒内调用 startForeground，
        // 否则系统抛 ANR/异常。
        startForeground(NOTIFICATION_ID, buildNotification())

        job = scope.launch {
            runGeneration(sessionId, questionId.takeIf { it >= 0 }, modelOverride)
        }
        // 记到**进程级**的静态槽位：停止动作是静态方法，拿不到服务实例。
        //
        // 之前靠「总线快照是否 finished」判断要不要发停止动作，
        // 那玩意儿有两个洞：快照为 null 时（还没发第一帧、或已被新一轮覆盖）
        // 会当成「没有生成」→ 点停止毫无反应；快照属于别的会话时
        // 又会停错对象。现在以**服务自己手里的 job** 为准。
        currentJob = job
        runningSessionId = sessionId
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 跑完一次生成：组装上下文 → 插助手消息 → 收流 → 节流落库 → 落终态。
     *
     * 与原先写在 ViewModel 里的 `collectStream` 是同一套逻辑，只是
     * 「往哪写预览」从 ViewModel 的 state 换成了 [ChatGenerationBus]。
     *
     * **顺序要紧**：先构建上下文、后插助手消息。反了的话那条空的
     * STREAMING 助手消息会被算进历史，模型收到一条莫名其妙的空回复。
     */
    private suspend fun runGeneration(
        sessionId: Long,
        questionId: Long?,
        modelOverride: String? = null
    ) {
        val app = application as? MistakeBookApp ?: return
        val container = app.container
        val repository = container.chatRepository

        val baseProfile = container.settingsStore.settings.first().activeProfile
        // 模型覆盖：用户在本轮临时切了模型（一个 provider 供应多个模型）。
        // 只改 model 字段，URL / Key 池 / 看图能力都沿用当前配置。
        val profile = baseProfile?.let { p ->
            if (modelOverride.isNullOrBlank() || modelOverride == p.model) p
            else p.copy(model = modelOverride)
        }
        if (profile == null || !profile.isConfigured()) {
            // 连模型都没配，就不留一条空助手消息了——直接在库里记一条失败。
            val failedId = repository.insertStreamingAssistantMessage(sessionId, profile?.model ?: "")
            finishWith(
                assistantId = failedId,
                content = "",
                status = MessageStatus.FAILED,
                errorText = "未配置模型",
                sessionId = sessionId
            )
            clearRunningSlot(sessionId)
            return
        }

        val context = repository.buildContext(
            sessionId = sessionId,
            questionId = questionId,
            supportsVision = profile.supportsVision,
            visionToolAvailable = container.settingsStore.snapshotNow().visionConfigured
        )
        val assistantId = repository.insertStreamingAssistantMessage(sessionId, profile.model)

        // 发一条「开始」快照：界面据此把气泡切到生成中，
        // 也让「停止」按钮能判断出当前确有活跃生成。
        ChatGenerationBus.publish(
            ChatGenerationBus.Snapshot(assistantId = assistantId, content = "", sessionId = sessionId)
        )

        // ---- 联网搜索（v0.0.43）：改为 function calling，模型自主决策 ----
        //
        // 旧机制（v0.0.41）是「请求前盲目搜一次再注入」：该搜不该搜的都搜，
        // query 还是客户端拿最后一条提问硬当的。现在把搜索包成 web_search
        // 工具交给模型自己决定（DeepSeek tool_calls / Tavily 同款用法），
        // 执行与结果回传见下面的 tool loop。
        val settings = container.settingsStore.settings.first()
        val searchEnabled = settings.chatSearchEnabled
        val effort = settings.reasoningEffort

        // 媒体工具按配置给：没配识图模型就不给 describe_image（给了也执行不了），
        // 没配生图渠道就不给 generate_image。
        val mediaTools = MediaTools.all(
            visionConfigured = settings.visionConfigured,
            imageConfigured = settings.imageConfigured,
            mineruConfigured = settings.mineruConfigured
        )

        // 本次会话最近一张用户发的图（识图工具的输入）。
        val sessionImageFile = latestImageFile(container, context.messages, sessionId)

        // 生图工具产出的图片路径：循环结束后以 Markdown 接到回答末尾。
        val generatedImages = mutableListOf<String>()

        // tool loop 的消息链走 **DTO 层**：中途要追加 assistant(tool_calls)
        // 与 role=tool 两种本地域模型（OutgoingMessage）表达不了的消息。
        val llmMessages = context.messages.map { it.toLlm() }.toMutableList()

        // 用户这条原始问题：搜索时作为 ori_question 传给 DeepSeek Search API，
        // 官方用它改进 query 理解（模型生成的 query 有时过度精简）。
        val lastUserQuestion = context.messages.lastOrNull { it.role == ChatRole.USER }
            ?.text?.takeIf { it.isNotBlank() }

        val buffer = StringBuilder()
        val thinking = StringBuilder()
        // 工具循环的跨轮累积：见 [ToolRoundState]。
        // 一次提问可能「思考→搜索→再思考→作答」多轮请求，但用户眼里
        // 始终是**一条连续消息**——各轮内容拼接，轮界绝不清零。
        val rounds = ToolRoundState()
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

        suspend fun flush() {
            if (buffer.isEmpty() && thinking.isEmpty()) return
            buffer.setLength(0)
            thinking.setLength(0)
            lastWriteAt = System.currentTimeMillis()
            repository.updateAssistantContent(
                assistantId,
                joinThinkingAndAnswer(rounds.answer, rounds.thinking)
            )
        }

        /**
         * 工具执行期间的进度提示。
         *
         * **必须带着已生成的内容**（同搜索的教训）：界面是整体替换语义，
         * 发空 content 会把正在流的回答清空，看着像「断了」。
         */
        fun publishStage(content: String, stage: String) {
            ChatGenerationBus.publish(
                ChatGenerationBus.Snapshot(
                    assistantId = assistantId,
                    content = content,
                    sessionId = sessionId,
                    stage = stage
                )
            )
        }

        suspend fun publishPreview() {
            // 预览节流：每个 delta 都发一次 StateFlow 会让界面每个 token 重组一次。
            // 与原实现同口径（PREVIEW_THROTTLE_MS）。
            val now = System.currentTimeMillis()
            if (now - lastPreviewAt < PREVIEW_THROTTLE_MS) return
            lastPreviewAt = now
            ChatGenerationBus.publish(
                ChatGenerationBus.Snapshot(
                    assistantId = assistantId,
                    content = joinThinkingAndAnswer(rounds.answer, rounds.thinking),
                    sessionId = sessionId
                )
            )
        }

        try {
            // ---- tool loop：模型要工具 → 客户端执行 → 回传 → 续请求 ----
            //
            // 上限 [SearchTools.MAX_TOOL_ROUNDS]：模型偶尔会「再搜一次确认」，
            // 不设上限的话一次提问能烧掉十几次搜索配额。
            var toolRound = 0
            while (true) {
                val request = ChatRequest(
                    model = profile.model,
                    messages = llmMessages.toList(),
                    temperature = ChatCompletionStream.TEMPERATURE,
                    max_tokens = ChatCompletionStream.MAX_TOKENS,
                    responseFormat = null,
                    stream = true,
                    tools = buildList {
                        if (searchEnabled) addAll(SearchTools.all())
                        addAll(mediaTools)
                    }.takeIf { it.isNotEmpty() }
                )
                // 本轮模型请求的工具调用（finish_reason=tool_calls 时非空）。
                // 用**替换**语义：正常路径一次流只发一个 ToolCalls 事件
                // （drainSse 已把整轮的分片累积成一个列表）；唯一的多次
                // 场景是流中途失败后退回非流式重发——那时旧列表属于已废弃的
                // 尝试，必须被重试结果覆盖，否则同一轮会执行两批调用。
                var pendingCalls: List<com.mistakebook.net.llm.AccumulatedToolCall> =
                    emptyList()

                container.chatCompletionStream.complete(profile, request, effort)
                    .collect { event ->
                        when (event) {
                            is ChatStreamEvent.Thinking -> {
                                if (event.text.isNotEmpty()) {
                                    thinking.append(event.text)
                                    rounds.onThinking(event.text)
                                    publishPreview()
                                    if (System.currentTimeMillis() - lastWriteAt >= WRITE_THROTTLE_MS) flush()
                                }
                            }

                            is ChatStreamEvent.Delta -> {
                                buffer.append(event.text)
                                rounds.onDelta(event.text)
                                publishPreview()
                                if (System.currentTimeMillis() - lastWriteAt >= WRITE_THROTTLE_MS) flush()
                            }

                            is ChatStreamEvent.ToolCalls -> pendingCalls = event.calls

                            is ChatStreamEvent.Done -> {
                                // 工具循环里每轮是一次独立 API 调用，用量要**累加**；
                                // 早先直接覆盖，多轮搜索时只记到最后一轮。
                                promptTokens += event.promptTokens
                                completionTokens += event.completionTokens
                                container.usageStatsStore.recordLlm(
                                    promptTokens = event.promptTokens,
                                    completionTokens = event.completionTokens,
                                    cachedTokens = event.cachedTokens
                                )
                                wasTruncated = wasTruncated ||
                                    event.finishReason == FINISH_REASON_LENGTH
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

                // 失败 / 取消 / 模型没要工具 / 轮数用尽：结束循环，走收尾
                if (status != MessageStatus.DONE) break
                if (pendingCalls.isEmpty()) break
                if (toolRound >= SearchTools.MAX_TOOL_ROUNDS) break

                // ---- 回传 assistant 消息（模型侧的原样回声）----
                //
                // DeepSeek 思考模式带 tools 时，**后续每个请求都必须完整回传
                // reasoning_content**，否则 API 400（官方文档硬性要求）。
                // 且官方样例里每条 assistant 消息只带**自己那一轮**的
                // reasoning_content——所以 echo 用本轮缓冲（[ToolRoundState.roundThinking]），
                // 跨轮累积只归 UI/DB。渠道判定见 [ToolLoopPolicy.needsReasoningEcho]：
                // 官方端点（baseUrl）与中转站 serving deepseek 模型（model 名）都覆盖。
                llmMessages += com.mistakebook.net.llm.ChatMessage(
                    role = "assistant",
                    content = JsonPrimitive(rounds.roundAnswer),
                    toolCalls = pendingCalls.map { call ->
                        com.mistakebook.net.llm.ToolCall(
                            id = call.id,
                            function = com.mistakebook.net.llm.ToolCallFunction(
                                name = call.name,
                                arguments = call.arguments
                            )
                        )
                    },
                    reasoningContent = rounds.roundThinking
                        .takeIf {
                            it.isNotBlank() && ToolLoopPolicy.needsReasoningEcho(
                                profile.baseUrl,
                                profile.model
                            )
                        }
                )
                // 本轮已回声完毕：重置**本轮**缓冲（跨轮累积保留，见 [ToolRoundState]）。
                // 早先在这里把 fullAnswer/fullThinking 一起清零，导致第一轮的
                // 开场白与思考被后续 flush 整体替换掉——永久丢失。
                rounds.beginNextRound()

                // ---- 客户端执行工具，结果以 role=tool 消息回传 ----
                pendingCalls.forEach { call ->
                    // 气泡先显示 stage：搜索这几秒不是「没反应」。
                    //
                    // **stage 快照必须带着已生成的内容**。早先这里发 content=""，
                    // 而界面是整体替换语义（ChatViewModel: streamingContent = snap.content）
                    // ——正在流的回答瞬间清空、只剩一行「正在联网搜索…」，
                    // 用户看到的就是「调用搜索之后断输出」。内容一直在，只是去搜了个索。
                    val currentText = joinThinkingAndAnswer(rounds.answer, rounds.thinking)
                    ChatGenerationBus.publish(
                        ChatGenerationBus.Snapshot(
                            assistantId = assistantId,
                            content = currentText,
                            sessionId = sessionId,
                            stage = "正在联网搜索…"
                        )
                    )
                    val result = when (call.name) {
                        SearchTools.WEB_SEARCH -> {
                            val query = SearchTools.parseSearchQuery(call.arguments)
                            if (query == null) {
                                "搜索未执行：参数缺少 query 字段，请修正后重试。"
                            } else {
                                executeWebSearch(
                                    container, settings, profile, query,
                                    oriQuestion = lastUserQuestion
                                )
                            }
                        }

                        MediaTools.DESCRIBE_IMAGE -> executeDescribeImage(
                            container = container,
                            settings = settings,
                            question = MediaTools.argument(call.arguments, "question"),
                            imageFile = sessionImageFile,
                            stage = { stage -> publishStage(currentText, stage) }
                        )

                        MediaTools.OCR_IMAGE -> executeOcrImage(
                            container = container,
                            sessionId = sessionId,
                            settings = settings,
                            question = MediaTools.argument(call.arguments, "question"),
                            imageFile = sessionImageFile,
                            stage = { stage -> publishStage(currentText, stage) }
                        )

                        MediaTools.GENERATE_IMAGE -> executeGenerateImage(
                            container = container,
                            settings = settings,
                            prompt = MediaTools.argument(call.arguments, "prompt"),
                            generated = generatedImages,
                            stage = { stage -> publishStage(currentText, stage) }
                        )

                        else -> "未识别的工具「${call.name}」，请改用你确定存在的工具。"
                    }
                    // 工具跑完了：内容照旧带上，撤掉 stage。空 content 的快照一律不再发。
                    ChatGenerationBus.publish(
                        ChatGenerationBus.Snapshot(
                            assistantId = assistantId,
                            content = currentText,
                            sessionId = sessionId
                        )
                    )
                    llmMessages += com.mistakebook.net.llm.ChatMessage(
                        role = "tool",
                        content = JsonPrimitive(result),
                        toolCallId = call.id,
                        name = call.name
                    )
                }
                toolRound++
            }

            // 生图工具产出的图：**存成会话附件**，不写进消息正文。
            //
            // 早先是往 content 里追加 `![](绝对路径)`，三个问题：
            // ① 绝对路径进了正文，每次打开会话都要走一遍 Markdown 解析；
            // ② 路径指向 filesDir/images/gen 的缓存文件，缓存清理后整条
            //    会话的图变死链；③ 有用户反馈「生图后这个对话点进去就闪退」。
            // 走附件：图复制到会话目录（删会话一并清理），渲染走已验证的
            // 气泡图片路径，正文保持干净。
            if (generatedImages.isNotEmpty()) {
                generatedImages.forEach { path ->
                    runCatching {
                        persistGeneratedImage(container, sessionId, assistantId, path)
                    }
                }
                publishPreview()
            }
        } catch (cancelled: CancellationException) {
            // 用户按了停止 / 服务被销毁：不是失败，保留已收到的内容。
            status = MessageStatus.CANCELED
            throw cancelled
        } catch (t: Throwable) {
            // 工具循环里任何没被事件化的意外（DB 写失败、组装消息炸掉等）
            // 都要落成 FAILED。按 DONE 静默收尾的话，用户看到的是
            // 「生成结束了、答案却只有半截」且没有任何错误提示。
            status = MessageStatus.FAILED
            errorText = t.message?.takeIf { it.isNotBlank() } ?: "生成过程出现异常"
        } finally {
            // 收尾必须放 finally：取消时 CancellationException 已经抛出去了。
            withContext(NonCancellable) {
                flush()
                repository.finishAssistantMessage(
                    id = assistantId,
                    status = status,
                    errorMessage = errorText,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens
                )
                ChatGenerationBus.publish(
                    ChatGenerationBus.Snapshot(
                        assistantId = assistantId,
                        content = joinThinkingAndAnswer(rounds.answer, rounds.thinking),
                        finished = true,
                        promptTokens = promptTokens,
                        truncated = wasTruncated,
                        failed = status == MessageStatus.FAILED,
                        errorText = errorText,
                        sessionId = sessionId
                    )
                )
            }
            // 撤通知 + 停服务放在 finally 里：取消路径会 rethrow，
            // 写在 try 之后的那段代码根本不会执行——服务会变成
            // 「没有前台通知的空服务」，被系统按后台服务限制杀掉。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // 清掉进程级的在途标记，否则下一次生成前「停止」会误判成
            // 「还有生成在跑」，而实际上早就跑完了。
            if (runningSessionId == sessionId) {
                currentJob = null
                runningSessionId = 0L
            }
        }
    }

    /** 失败收尾：直接落终态并停服务。 */
    private suspend fun finishWith(
        assistantId: Long,
        content: String,
        status: MessageStatus,
        errorText: String?,
        sessionId: Long
    ) {
        val app = application as? MistakeBookApp ?: return
        withContext(NonCancellable) {
            app.container.chatRepository.finishAssistantMessage(
                id = assistantId,
                status = status,
                errorMessage = errorText,
                promptTokens = 0,
                completionTokens = 0
            )
            ChatGenerationBus.publish(
                ChatGenerationBus.Snapshot(
                    assistantId = assistantId,
                    content = content,
                    finished = true,
                    failed = status == MessageStatus.FAILED,
                    errorText = errorText,
                    sessionId = sessionId
                )
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ 通知

    private fun buildNotification(): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在生成回复…")
            .setContentText("可以切到别的应用，生成会在后台继续")
            // 常驻、静默：它是用户主动发起的持续任务，不是一条「通知」
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                "停止",
                PendingIntent.getService(
                    this,
                    REQUEST_STOP,
                    Intent(this, ChatGenerationService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI 生成", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台生成 AI 回复时的常驻提示"
                setShowBadge(false)
            }
        )
    }

    // ------------------------------------------------------------ 联网搜索（v0.0.43 工具执行侧）

    /**
     * 执行一次 web_search 工具，返回给模型的文本结果。
     *
     * **永远不返回 null**：搜索结果要以 role=tool 消息回传，空内容会让
     * 模型困惑。无 Key / 无结果 / 网络失败各自给一句人话，让模型基于
     * 自身知识回答并说明未能联网——搜索是增强，不是依赖。
     *
     * 出口优先级同旧版：用户选了 DeepSeek 就先试官方搜索（复用其渠道
     * Key），渠道不在或失败则退 Tavily；直接选 Tavily 就只用 Tavily。
     */
    /**
     * 把生图结果落到会话附件表：复制进会话目录 + 插一条 IMAGE 附件。
     *
     * 走附件而不是写正文的理由见调用处注释。附件路径是成熟的渲染路径
     * （[com.mistakebook.ui.chat.ChatViewModel.loadBubbleImages] 直接出图），
     * 且删会话时随目录一起清理。
     */
    private suspend fun persistGeneratedImage(
        container: com.mistakebook.di.AppContainer,
        sessionId: Long,
        assistantId: Long,
        sourcePath: String
    ) {
        val src = File(sourcePath)
        if (!src.isFile || src.length() == 0L) return
        val dir = container.files.chatDir(sessionId).apply { mkdirs() }
        val target = File(dir, "gen_${System.currentTimeMillis()}_${src.name}")
        src.copyTo(target, overwrite = true)
        container.chatRepository.insertAttachment(
            com.mistakebook.data.local.entities.ChatAttachment(
                messageId = assistantId,
                sessionId = sessionId,
                kind = com.mistakebook.domain.AttachmentKind.IMAGE,
                localPath = target.absolutePath,
                fileName = "示意图.png",
                mimeType = "image/png",
                status = com.mistakebook.domain.AttachmentStatus.READY,
                sizeBytes = target.length(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 本次会话最近一张用户发的图：消息里存的是 base64 data URL，
     * 识图接口要的是本地文件，这里解码落一份临时文件。
     *
     * 落在会话的 OCR 目录下：随会话一起清理，不会在磁盘上留垃圾。
     */
    private fun latestImageFile(
        container: com.mistakebook.di.AppContainer,
        messages: List<com.mistakebook.data.chat.OutgoingMessage>,
        sessionId: Long
    ): File? {
        val dataUrl = messages.asReversed()
            .firstOrNull { it.images.isNotEmpty() }
            ?.images?.firstOrNull()
            ?: return null
        val base64 = dataUrl.substringAfter("base64,", "").takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val dir = container.files.chatOcrDir(sessionId).apply { mkdirs() }
            File(dir, "vision_input.png").apply {
                writeBytes(java.util.Base64.getDecoder().decode(base64))
            }
        }.getOrNull()
    }

    /**
     * 执行 `describe_image`：让**专门的识图模型**读图，把读到的内容回给主模型。
     *
     * 结果只进上下文（role=tool），不直接糊在界面上——这正是用户要的
     * 「作为一种工具存在，而不是在下面显示识别细节」。
     */
    private suspend fun executeDescribeImage(
        container: com.mistakebook.di.AppContainer,
        settings: com.mistakebook.data.prefs.SettingsSnapshot,
        question: String?,
        imageFile: File?,
        stage: (String) -> Unit
    ): String {
        val vision = settings.visionProfile
            ?: return "识图工具不可用：尚未在设置里指定识图模型（设置 → 大模型 → 识图模型）。"
        if (!vision.isConfigured()) {
            return "识图工具不可用：指定的识图模型配置不完整（地址 / Key / 模型名）。"
        }
        if (imageFile == null) {
            return "本次对话没有可读取的图片：用户没有发图，或图片附件已失效。"
        }
        stage("识图模型正在看图…")
        val reply = container.llmClient.plainTextWithImage(
            profile = vision,
            systemPrompt = "你是一个识图助手。按用户的要求准确读取图片内容：" +
                "文字、公式、表格、图形结构如实转录；" +
                "看不清的地方直说看不清，不要编造。直接给结果，不要解释你怎么看的。",
            userPrompt = question ?: "请完整描述这张图的内容。",
            imageFile = imageFile,
            longEdgePx = settings.imageLongEdgePx
        )
        return when (reply) {
            is com.mistakebook.net.ApiResult.Success ->
                reply.data.trim().ifBlank { "识图模型没有返回内容。" }

            is com.mistakebook.net.ApiResult.Failure ->
                "识图失败：${reply.error.serverMessage.ifBlank { "请检查识图模型配置" }}"
        }
    }

    /**
     * 执行 `generate_image`：按模型给的英文 prompt 出一张示意图。
     *
     * 图落盘后记进 [generated]，循环结束会以 Markdown 接到回答末尾，
     * 用户在气泡里直接看到。
     */
    /**
     * 执行 `ocr_image`：用 **MinerU** 把图里的文字读出来（就是以前那个
     * 预处理，只是现在由模型决定要不要读）。
     *
     * 结果同样只进上下文（role=tool）。
     */
    private suspend fun executeOcrImage(
        container: com.mistakebook.di.AppContainer,
        sessionId: Long,
        settings: com.mistakebook.data.prefs.SettingsSnapshot,
        question: String?,
        imageFile: File?,
        stage: (String) -> Unit
    ): String {
        if (!settings.mineruConfigured) {
            return "OCR 工具不可用：尚未在设置里配置 MinerU Key。"
        }
        if (imageFile == null) {
            return "本次对话没有可读取的图片：用户没有发图，或图片附件已失效。"
        }
        stage("正在用 OCR 读取图片文字…")
        val result = container.chatImageOcr.recognize(
            sessionId = sessionId,
            imageFile = imageFile,
            settings = settings,
            onStage = { s -> stage("正在用 OCR 读取图片文字：$s") }
        )
        return when (result) {
            is com.mistakebook.net.ApiResult.Success ->
                if (result.data.isBlank()) {
                    "OCR 完成，但这张图里没有读出文字（可能是纯图形或拍摄模糊）。"
                } else {
                    // 问题一并带上：让模型知道这段文字是拿来回答什么的
                    val prefix = question?.let { "用户想知道：$it\n\n" } ?: ""
                    "$prefix【图片中的文字（OCR）】\n${result.data}"
                }

            is com.mistakebook.net.ApiResult.Failure ->
                "OCR 失败：${result.error.serverMessage.ifBlank { "请检查 MinerU 配置" }}"
        }
    }

    private suspend fun executeGenerateImage(
        container: com.mistakebook.di.AppContainer,
        settings: com.mistakebook.data.prefs.SettingsSnapshot,
        prompt: String?,
        generated: MutableList<String>,
        stage: (String) -> Unit
    ): String {
        if (prompt == null) return "生图未执行：参数缺少 prompt 字段，请给出英文描述后重试。"
        if (!settings.imageConfigured) {
            return "生图工具不可用：尚未在设置 → 生图设置里配置渠道。"
        }
        stage("正在生成示意图…")
        val result = container.sceneImageGenerator.generate(
            cacheKeySeed = "chat:$prompt",
            systemPrompt = "",
            userPrompt = prompt,
            // 模型已经给了现成 prompt，不再让它自己改写一遍
            imagePrompt = prompt
        )
        return when (result) {
            is com.mistakebook.pipeline.SceneImageResult.Generated -> {
                generated += result.file.absolutePath
                "示意图已生成并显示给用户。请继续你的讲解，不要重复描述图片内容。"
            }

            is com.mistakebook.pipeline.SceneImageResult.Cached -> {
                generated += result.file.absolutePath
                "示意图已生成并显示给用户（命中缓存）。请继续你的讲解。"
            }

            is com.mistakebook.pipeline.SceneImageResult.NotConfigured ->
                "生图工具不可用：尚未在设置 → 生图设置里配置渠道。"

            is com.mistakebook.pipeline.SceneImageResult.Failed ->
                "生图失败：${result.message}"
        }
    }

    private suspend fun executeWebSearch(
        container: com.mistakebook.di.AppContainer,
        settings: com.mistakebook.data.prefs.SettingsSnapshot,
        profile: com.mistakebook.data.prefs.LlmProfile,
        query: String,
        /** 用户原始问题：传给 DeepSeek Search API 的 ori_question，改进 query 理解。 */
        oriQuestion: String? = null
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                // DeepSeek 搜索 Key 三级来源：设置里单独配的 > 对话模型的
                // DeepSeek Key（复用）。两者都空时该出口不可用。
                val deepSeekKey = com.mistakebook.data.chat.pickSearchKey(
                    explicitKey = settings.deepseekSearchApiKey,
                    fallbackKey = profile.apiKey.takeIf {
                        it.isNotBlank() && profile.baseUrl.contains("deepseek", ignoreCase = true)
                    }
                )
                val tavilyKey = settings.tavilyApiKey.takeIf { it.isNotBlank() }
                val attempts: List<Pair<Int, String>> = when (settings.chatSearchProvider) {
                    com.mistakebook.data.chat.ChatSearchProvider.DEEPSEEK ->
                        buildList {
                            if (deepSeekKey != null) add(0 to deepSeekKey)
                            if (tavilyKey != null) add(1 to tavilyKey)
                        }

                    com.mistakebook.data.chat.ChatSearchProvider.TAVILY ->
                        tavilyKey?.let { listOf(1 to it) } ?: emptyList()
                }
                if (attempts.isEmpty()) {
                    return@withTimeoutOrNull "搜索未执行：未配置可用的搜索渠道" +
                        "（DeepSeek API Key 或 Tavily API Key，见设置页「联网搜索」）。"
                }
                for ((which, key) in attempts) {
                    val results = runCatching {
                        if (which == 0) {
                            com.mistakebook.data.chat.DeepSeekSearch.search(
                                container.searchHttpClient, key, query, oriQuestion
                            )
                        } else {
                            com.mistakebook.data.chat.TavilySearch.search(container.searchHttpClient, key, query)
                        }
                    }.getOrDefault(emptyList())
                    if (results.isNotEmpty()) {
                        return@withTimeoutOrNull com.mistakebook.data.chat.WebSearchFormatter.format(query, results)
                    }
                }
                "未找到相关网页结果。请基于自身知识回答，并说明未能联网核实。"
            }
        }.getOrNull() ?: "联网搜索失败（网络异常或超时）。请基于自身知识回答。"
    }

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

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val partSerializer = kotlinx.serialization.builtins.ListSerializer(ChatContentPart.serializer())

    companion object {
        const val ACTION_STOP = "com.mistakebook.chat.STOP"

        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_ASSISTANT_ID = "assistantId"
        const val EXTRA_QUESTION_ID = "questionId"

        /**
         * 本次请求要用的模型（覆盖配置里的默认模型）。
         *
         * 一个 provider（尤其是中转站）常常挂着多个模型，用户想在对话中
         * 直接切换。模型名由界面通过 Intent 传进来，**不下发给服务**。
         */
        const val EXTRA_MODEL = "model"

        /**
         * 进程级的「在途生成」标记。
         *
         * 「停止」是静态入口（通知栏动作 / 界面按钮），拿不到服务实例，
         * 所以这里放一份静态引用。判断「有没有生成在跑」不能只看
         * 总线快照——快照为 null（还没发第一帧、或已被新一轮覆盖）时
         * 会误判成「没在跑」，点停止毫无反应。
         */
        @Volatile
        private var currentJob: Job? = null

        /** [currentJob] 正在为哪个会话生成。0 表示无。 */
        @Volatile
        private var runningSessionId: Long = 0L

        /** 清掉在途标记（只在确实是这个会话收尾时清）。 */
        private fun clearRunningSlot(sessionId: Long) {
            if (runningSessionId == sessionId) {
                currentJob = null
                runningSessionId = 0L
            }
        }

        /** 当前是否有生成在跑。 */
        fun isRunning(): Boolean = currentJob != null && currentJob!!.isActive

        /** 当前正在生成的会话 id；0 表示无。 */
        fun runningSession(): Long = runningSessionId

        private const val CHANNEL_ID = "chat_generation"
        private const val NOTIFICATION_ID = 4202
        private const val REQUEST_STOP = 4203

        /** 落库节流：与原实现一致，肉眼无差别但少几千次写。 */
        private const val WRITE_THROTTLE_MS = 500L

        /** 预览节流：与原实现一致，避免每个 token 都让界面重组。 */
        private const val PREVIEW_THROTTLE_MS = 220L

        private const val FINISH_REASON_LENGTH = "length"

        /**
         * 启动一次后台生成。
         *
         * 用 `startForegroundService`：生成要活过界面生命周期，
         * 而普通 `startService` 在后台会被系统立刻掐掉。
         */
        fun start(context: Context, sessionId: Long, questionId: Long?, model: String? = null) {
            val intent = Intent(context, ChatGenerationService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_QUESTION_ID, questionId ?: -1L)
                if (!model.isNullOrBlank()) putExtra(EXTRA_MODEL, model)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** 停止当前这次生成（界面的「停止」按钮与通知的「停止」动作都走这里）。 */
        fun stop(context: Context) {
            // 没有活跃生成时不发：否则会**凭空启动一个服务实例**，
            // 而它没有前台通知，几秒后被系统的后台服务限制杀掉——
            // 白白制造一个「服务莫名消失」的怪现象。
            //
            // 判据用「在途 job 是否活跃」，而不是总线快照：
            // 快照为 null（还没发第一帧）时会把正在跑的任务误判成没在跑。
            val job = currentJob ?: return
            if (!job.isActive) {
                currentJob = null
                runningSessionId = 0L
                return
            }
            val intent = Intent(context, ChatGenerationService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
