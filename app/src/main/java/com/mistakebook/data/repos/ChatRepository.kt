package com.mistakebook.data.repos

import android.util.Log
import com.mistakebook.data.AppFiles
import com.mistakebook.data.chat.AssembledContext
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatOcrText
import com.mistakebook.data.chat.ChatPrompts
import com.mistakebook.data.chat.OutgoingMessage
import com.mistakebook.data.local.ChatDao
import com.mistakebook.data.local.QuestionDao
import com.mistakebook.data.local.SubjectDao
import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.local.entities.ChatSession
import com.mistakebook.domain.AttachmentKind
import com.mistakebook.domain.AttachmentStatus
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.domain.Option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 图片附件 -> 发给模型的 data URL。
 *
 * 抽成接口是为了让 [ChatRepository] 不依赖图片处理细节（步骤 5 才实现真正的那套），
 * 同时让仓储逻辑能脱离 Android 单测。
 */
fun interface ImageDataUrlProvider {
    /** @return base64 data URL；失败返回 null（该图就不带，**不因此让整条消息失败**）。 */
    suspend fun dataUrlOf(attachment: ChatAttachment): String?
}

/**
 * 对话仓储。
 *
 * 只做「存 / 取 / 拼」，**不做网络**——流式在 [com.mistakebook.net.llm.ChatCompletionStream]，
 * 两者分开才能让上下文组装脱离 IO 单测。
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val questionDao: QuestionDao,
    private val subjectDao: SubjectDao,
    private val appFiles: AppFiles,
    private val imageDataUrls: ImageDataUrlProvider
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val optionListSerializer = ListSerializer(Option.serializer())

    // ------------------------------------------------------------ 会话

    fun observeSessions(): kotlinx.coroutines.flow.Flow<List<ChatSession>> = chatDao.observeSessions()

    fun observeSession(id: Long): kotlinx.coroutines.flow.Flow<ChatSession?> = chatDao.observeSession(id)

    fun observeMessages(sessionId: Long): kotlinx.coroutines.flow.Flow<List<ChatMessage>> =
        chatDao.observeMessages(sessionId)

    /**
     * 取某题的会话，没有就建一个。
     *
     * 「每题一个固定会话」就靠这里。复用已存在的，**包括已有消息的**——
     * 用户上次聊到一半，这次接着聊，而不是每次进来都是空白。
     *
     * @param questionId null 表示自由会话。此时会**复用最近一个空会话**：
     *   会话列表点「新对话」如果先建一个、聊天页又建一个，
     *   就会留下一堆永远空着的孤儿会话，点几次攒几个。
     */
    suspend fun sessionForQuestion(questionId: Long?): ChatSession {
        if (questionId != null) {
            chatDao.findSessionByQuestion(questionId)?.let { return it }
        } else {
            chatDao.findEmptyFreeSession()?.let { return it }
        }
        val now = System.currentTimeMillis()
        return ChatSession(
            id = newSession(questionId),
            questionId = questionId,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * 已存在但一条消息都没有的自由会话。
     *
     * 「新对话」按钮要的就是它：复用而不是新建，否则点几次攒几条空会话。
     */
    suspend fun findEmptyFreeSession(): ChatSession? = chatDao.findEmptyFreeSession()

    /** 强制新建一条空会话，返回 id。 */
    suspend fun createFreeSession(): Long = newSession(null)

    /** 新建一条会话并返回 id。 */
    private suspend fun newSession(questionId: Long?): Long {
        val now = System.currentTimeMillis()
        return chatDao.insertSession(
            ChatSession(
                questionId = questionId,
                title = "",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /** 重命名。改完**锁定自动标题**，否则下次进会话会被首条消息摘要冲掉。 */
    suspend fun renameSession(id: Long, title: String) {
        val session = chatDao.findSession(id) ?: return
        chatDao.updateSession(session.copy(title = title.trim(), titleLocked = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDeleteSession(id: Long) {
        val now = System.currentTimeMillis()
        chatDao.softDeleteSession(id, now)
        appFiles.deleteRecursively(appFiles.chatDir(id))
    }

    suspend fun restoreSession(id: Long) {
        chatDao.restoreSession(id, System.currentTimeMillis())
    }

    suspend fun softDeleteAllSessions() {
        val now = System.currentTimeMillis()
        val sessions = chatDao.observeSessionsOnce()
        sessions.forEach { appFiles.deleteRecursively(appFiles.chatDir(it.id)) }
        chatDao.softDeleteAllSessions(now)
    }

    // ------------------------------------------------------------ 消息

    /**
     * 进会话时收拾残局：把仍停在 STREAMING 的消息收敛成 CANCELED。
     *
     * 不做的话，进程被杀后重进页面会永远显示「生成中」，
     * 而且用户没有任何办法让它停下来。
     */
    suspend fun recoverStaleStreaming(sessionId: Long) {
        val stale = chatDao.findStreamingMessage(sessionId) ?: return
        chatDao.markStatus(
            id = stale.id,
            status = MessageStatus.CANCELED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 按 id 取一条消息（T13 批改结果「存入题目备注」用）。 */
    suspend fun findMessageById(id: Long): ChatMessage? = chatDao.findMessage(id)

    suspend fun insertUserMessage(
        sessionId: Long,
        text: String,
        attachmentIds: List<Long> = emptyList(),
        questionId: Long? = null
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val id = chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                role = ChatRole.USER,
                content = text,
                status = MessageStatus.DONE,
                attachmentIdsJson = json.encodeToString(ListSerializer(Long.serializer()), attachmentIds),
                questionId = questionId,
                createdAt = now,
                updatedAt = now
            )
        )
        touchSessionWith(sessionId, text, now)
        return ChatMessage(
            id = id, sessionId = sessionId, role = ChatRole.USER, content = text,
            status = MessageStatus.DONE, createdAt = now, updatedAt = now
        )
    }

    /**
     * 插入题目上下文那条 [ChatRole.SYSTEM] 消息（`injected = true`）。
     *
     * **只在会话里还没有这条时插一次**，之后一直靠它垫在上下文头部。
     *
     * 守卫查的是「有没有 injected 消息」而不是「会话里有没有消息」——
     * 早先写成后者，而调用顺序是「先插用户消息、再注入题目上下文」，
     * 于是 `countMessages > 0` 恒成立，这段代码**永远返回 false**，
     * 题目上下文一次都注不进去，而且不报任何错。
     */
    suspend fun ensureQuestionContext(sessionId: Long, questionId: Long): Boolean {
        if (chatDao.findInjectedMessage(sessionId) != null) return false
        val text = buildQuestionContext(questionId) ?: return false
        val now = System.currentTimeMillis()
        chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                role = ChatRole.SYSTEM,
                content = text,
                status = MessageStatus.DONE,
                questionId = questionId,
                injected = true,
                createdAt = now,
                updatedAt = now
            )
        )
        return true
    }

    /** 新建一条空的 STREAMING 助手消息，边收边往里写。 */
    suspend fun insertStreamingAssistantMessage(sessionId: Long, model: String): Long {
        val now = System.currentTimeMillis()
        val id = chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                role = ChatRole.ASSISTANT,
                content = "",
                status = MessageStatus.STREAMING,
                createdAt = now,
                updatedAt = now
            )
        )
        // 记下「这条是哪个模型答的」。会话内换模型不影响历史。
        chatDao.findSession(sessionId)?.let { session ->
            chatDao.updateSession(session.copy(model = model, updatedAt = now))
        }
        return id
    }

    suspend fun appendAssistantContent(id: Long, content: String) {
        val current = chatDao.findMessage(id) ?: return
        chatDao.appendContent(id, current.content + content, MessageStatus.STREAMING, System.currentTimeMillis())
    }

    suspend fun updateAssistantContent(id: Long, content: String) {
        chatDao.appendContent(id, content, MessageStatus.STREAMING, System.currentTimeMillis())
    }

    suspend fun finishAssistantMessage(
        id: Long,
        status: MessageStatus,
        errorMessage: String?,
        promptTokens: Int = 0,
        completionTokens: Int = 0
    ) {
        val now = System.currentTimeMillis()
        if (promptTokens > 0 || completionTokens > 0) {
            chatDao.markUsage(id, promptTokens, completionTokens, now)
        }
        chatDao.markStatus(id, status, errorMessage, now)
        val message = chatDao.findMessage(id)
        chatDao.refreshSessionStats(message?.sessionId ?: 0L, now)
    }

    suspend fun deleteMessage(id: Long) {
        val message = chatDao.findMessage(id) ?: return
        message.attachmentIds().forEach { attachmentId ->
            chatDao.findAttachment(attachmentId)?.let { deleteAttachmentFile(it) }
        }
        // 附件记录靠外键 CASCADE 清掉
        chatDao.deleteMessage(id)
        chatDao.refreshSessionStats(message.sessionId, System.currentTimeMillis())
    }

    // ------------------------------------------------------------ 附件

    fun observeAttachments(messageId: Long): kotlinx.coroutines.flow.Flow<List<ChatAttachment>> =
        chatDao.observeAttachments(messageId)

    /** 按 id 取一条会话。对应「从对话记录点进来」的场景。 */
    suspend fun sessionById(id: Long): ChatSession? = chatDao.findSession(id)

    suspend fun attachmentById(id: Long): ChatAttachment? = chatDao.findAttachment(id)

    /** 按消息 id 查附件（assistant 消息的图片不写 attachmentIdsJson，直接按 messageId 查）。 */
    suspend fun attachmentsOf(messageId: Long): List<ChatAttachment> = chatDao.attachmentsOf(messageId)

    /** 整个会话的附件（一次查完，按 messageId 分组给气泡图用）。 */
    suspend fun attachmentsOfSession(sessionId: Long): List<ChatAttachment> =
        chatDao.attachmentsOfSession(sessionId)

    /**
     * 附件图片 -> base64 data URL。给聊天气泡显示用。
     *
     * 走同一份 LRU 缓存：气泡每次重组都会读一次，不缓存就是反复 base64 一张几百 KB 的图。
     */
    suspend fun imageDataUrlOf(attachment: ChatAttachment): String? =
        imageDataUrls.dataUrlOf(attachment)

    suspend fun insertAttachment(attachment: ChatAttachment): Long = chatDao.insertAttachment(attachment)

    /**
     * 把已准备好的附件落到某条消息上。
     *
     * **必须在消息建好之后调**——`chat_attachments.messageId` 是外键指向 `chat_messages`，
     * 消息还不存在时插入会直接撞约束失败。
     *
     * @return 落库后的附件 id，供 [setAttachmentIds] 回写到消息上。
     */
    suspend fun attachPrepared(
        messageId: Long,
        sessionId: Long,
        items: List<com.mistakebook.data.chat.PreparedAttachment>
    ): List<Long> {
        val now = System.currentTimeMillis()
        return items.map { prepared ->
            chatDao.insertAttachment(
                ChatAttachment(
                    messageId = messageId,
                    sessionId = sessionId,
                    kind = prepared.kind,
                    localPath = prepared.localPath,
                    fileName = prepared.fileName,
                    mimeType = prepared.mimeType,
                    status = com.mistakebook.domain.AttachmentStatus.READY,
                    sizeBytes = prepared.sizeBytes,
                    widthPx = prepared.widthPx,
                    heightPx = prepared.heightPx,
                    textExcerpt = prepared.textExcerpt,
                    extractedChars = prepared.extractedChars,
                    createdAt = now
                )
            )
        }
    }

    /** 回写消息上的附件 id 列表。 */
    suspend fun setAttachmentIds(messageId: Long, ids: List<Long>) {
        val message = chatDao.findMessage(messageId) ?: return
        chatDao.updateMessage(
            message.copy(
                attachmentIdsJson = json.encodeToString(ListSerializer(Long.serializer()), ids),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateAttachment(attachment: ChatAttachment) = chatDao.updateAttachment(attachment)

    /**
     * 把本轮 @资料库 注入的文件登记到消息上（v0.0.58）。
     *
     * 只为**历史可见**：气泡里显示文件芯片，用户回看时知道这一轮参考了哪些资料。
     * 不参与构造请求（[AttachmentKind.LIBRARY] 在 toOutgoing 里被过滤）。
     *
     * @return 落库后的附件 id（目前没有第二写入方，返回只为对称/调试）。
     */
    suspend fun attachLibraryFiles(
        messageId: Long,
        sessionId: Long,
        files: List<java.io.File>
    ): List<Long> {
        if (files.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return files.map { file ->
            chatDao.insertAttachment(
                ChatAttachment(
                    messageId = messageId,
                    sessionId = sessionId,
                    kind = AttachmentKind.LIBRARY,
                    localPath = file.absolutePath,
                    fileName = file.name,
                    mimeType = "",
                    status = com.mistakebook.domain.AttachmentStatus.READY,
                    sizeBytes = file.length(),
                    textExcerpt = "",
                    extractedChars = 0,
                    errorMessage = null,
                    createdAt = now
                )
            )
        }
    }
    suspend fun deleteAttachment(attachment: ChatAttachment) {
        chatDao.deleteAttachment(attachment.id)
        deleteAttachmentFile(attachment)
    }

    private fun deleteAttachmentFile(attachment: ChatAttachment) {
        runCatching { java.io.File(attachment.localPath).takeIf { it.exists() }?.delete() }
            .onFailure { Log.w(TAG, "删除附件文件失败: ${attachment.localPath}", it) }
    }

    // ------------------------------------------------------------ 上下文组装

    /**
     * 组装要发出去的请求上下文。
     *
     * @param pendingText 本次新输入。为空表示「重试上一条」，此时历史里已含它。
     */
    suspend fun buildContext(
        sessionId: Long,
        questionId: Long?,
        pendingText: String = "",
        /**
         * 当前模型**能不能直接看图**。
         *
         * 不能看图时：图片附件的 data URL **不再发给模型**（发了也只会得到
         * 「不支持图片」的错误或空回答），改为只发 MinerU 抽出来的文字。
         */
        supportsVision: Boolean = true,
        /**
         * 是否已经配了专门的**识图模型**（对话里会有 describe_image 工具）。
         *
         * 为真时不再把 MinerU 的识别原文预塞进上下文——图由模型按需调
         * 识图工具去看。预塞的问题：不管这张图看不看都烧一次识别，
         * 而且整段识别原文会进上下文（也容易被界面展示出来），很生硬。
         */
        visionToolAvailable: Boolean = false,
        /**
         * 是否已经配了 **MinerU**（对话里会有 ocr_image 工具）。
         *
         * 与 [visionToolAvailable] 分开是因为两条路读出来的东西不一样：
         * MinerU 给的是**逐字文字**（题目、公式），识图模型给的是**理解**。
         * 附件清单要据此告诉模型该调哪个工具。
         */
        ocrToolAvailable: Boolean = false,
        /**
         * 手写过程批改指令（T13）。非空时拼在题目上下文之后——**与题目上下文
         * 同一条 system 消息**，模型读起来是「这道题 + 按这个方式批」。
         *
         * 普通对话传空串：不注入任何额外内容，行为与 v0.0.33 逐字一致。
         */
        gradingInstruction: String = "",
        /**
         * 本轮临时附加的上下文（v0.0.57）：目前用于 @资料库 选中的文件原文。
         *
         * 拼在 system 消息末尾，**不落库、不进用户气泡**——下一轮自然消失。
         * 见函数体内「本轮临时资料」那段的解释。
         */
        extraContext: String = ""
    ): AssembledContext {
        val systemContext = buildString {
            if (questionId != null) append(buildQuestionContext(questionId).orEmpty())
            if (gradingInstruction.isNotBlank()) {
                if (isNotBlank()) append("\n\n")
                append(gradingInstruction)
            }
            // 本轮临时资料（@资料库 选中的文件原文，v0.0.57）。
            //
            // 以前这段内容是**直接拼进用户消息正文**的：气泡里糊一大段
            // 资料原文（用户反馈「发 docx 直接把文字倒进气泡里」），
            // 而且下一轮还留在历史里重复占上下文。现在改走 system 侧：
            // 只影响本轮请求，气泡里仍是用户真正打的那句话。
            //
            // 放 system 而不是 user：资料是「参考资料」，模型应把它当成
            // 背景知识而不是用户说的话；这也避免模型模仿资料里的语气作答。
            if (extraContext.isNotBlank()) {
                if (isNotBlank()) append("\n\n")
                append(extraContext)
            }
        }
        // 失败的、取消的消息**都保留**：它们的内容是有效的对话上下文。
        // 整条剔除会让模型看到「用户问了什么」却看不到「已经答到哪」，
        // 于是换个说法把同一段重讲一遍。
        val raw = chatDao.messagesExcludingInjected(sessionId)
        // 上下文压缩的产物是一条 SYSTEM 摘要消息：从历史里摘出来单独传，
        // 不留在 history 里（assembler 反正也会滤掉 SYSTEM，这里先行提取
        // 是为了拿到文本）。
        val summaryMessage = raw.firstOrNull {
            it.role == ChatRole.SYSTEM &&
                it.content.startsWith(ChatContextAssembler.SUMMARY_PREFIX)
        }
        val history = raw.filterNot { it.id == summaryMessage?.id }
            .map { it.toOutgoing(supportsVision, visionToolAvailable, ocrToolAvailable) }
        // v0.0.58：资料库附件只是「历史可见」的标记，原文已注入 system 上下文，
        // 不能重复塞进请求（否则同一份资料每轮都占一遍上下文）。
        // 所以 toOutgoing 里按 kind 过滤，LIBRARY 不出现在 usable 集合中。
        return ChatContextAssembler.assemble(
            systemPrompt = ChatPrompts.SYSTEM,
            questionContext = systemContext,
            history = history,
            pendingText = pendingText,
            summary = summaryMessage?.content.orEmpty()
        )
    }

    private suspend fun ChatMessage.toOutgoing(
        supportsVision: Boolean,
        visionToolAvailable: Boolean = false,
        ocrToolAvailable: Boolean = false
    ): OutgoingMessage {
        val attachments = attachmentIds().mapNotNull { chatDao.findAttachment(it) }
        val usable = attachments.filter {
            // v0.0.58：LIBRARY 只是历史可见的标记，不进请求上下文
            it.status == AttachmentStatus.READY && it.kind != AttachmentKind.LIBRARY
        }
        val imageAttachments = usable.filter { it.kind == AttachmentKind.IMAGE }
        val docAttachments = usable.filter { it.kind != AttachmentKind.IMAGE }
        // 先把「实际会发出去的图片」算出来，清单据此如实描述。
        // 不能只看 supportsVision：data URL 转换失败时那张图根本发不出去，
        // 清单若还写「图片已随消息发送」，模型会以为自己看漏了。
        val images = if (supportsVision) {
            imageAttachments.mapNotNull { attachment ->
                runCatching { imageDataUrls.dataUrlOf(attachment) }.getOrNull()
            }
        } else {
            emptyList()
        }

        val text = buildString {
            append(content)
            // 文档类附件的正文：一直都要带。
            val excerpts = docAttachments
                .mapNotNull { it.textExcerpt.takeIf { excerpt -> excerpt.isNotBlank() } }
            if (excerpts.isNotEmpty()) {
                append("\n\n")
                append(excerpts.joinToString("\n\n"))
            }
            // **模型不识图时**，把已经从图片里抽出的文字补上。
            //
            // 图片本身这时不发（`images` 为空），模型完全看不到那张图；
            // 若不给文字，模型只能对着「用户发了张图」这句话瞎猜。
            //
            // 识别失败的图也会带一条失败说明进来（见 [ChatOcrText.failureNote]）：
            // 模型由此知道「这里有一张图但没读出来」，能如实让用户补充文字，
            // 而不是忽略这张图继续答。
            //
            // **只要文字已经存在就带上**（v0.0.49）：这份文字是之前
            // 已经识别过的、**不额外花钱**，没理由浪费。此前这里是
            // 「有识图工具就不预塞」，于是清单说「见上文」而上文根本没有
            // 那段字——模型被自己的清单骗了一次。
            if (!supportsVision) {
                val imageTexts = imageAttachments
                    .mapNotNull { it.textExcerpt.takeIf { excerpt -> excerpt.isNotBlank() } }
                if (imageTexts.isNotEmpty()) {
                    append("\n\n")
                    append(
                        imageTexts.joinToString("\n\n") { ChatOcrText.section(it) }
                    )
                }
            }
            // **附件清单**（v0.0.49）。
            //
            // 之前的问题（用户反馈「不可识图的模型根本接收不到文件」）：
            // 模型不识图时图片 data URL 被丢掉、OCR 文字也可能没有，
            // 于是这条消息发给模型的内容里**完全看不出用户发过文件**——
            // 模型不知道有图，就不会去调识图工具，只能对着空气瞎猜。
            //
            // 附件清单把「发过什么、能不能读、怎么读」讲清楚，
            // 模型据此决定要不要调用 describe_image / ocr_image。
            //
            // v0.0.56：**清单不再只在「模型不识图」时拼**。`supportsVision`
            // 默认就是 true，一旦用户配的是纯文本模型却忘了关这个开关，
            // 图片 data URL 发不进去、清单和工具又都被跳过——模型只能回一句
            // 「我没有看图的工具」。现在无条件拼，并如实说明图片是否已随请求
            // 发出（见 [AttachmentManifest.build] 的 imagesAttached）。
            if (usable.isNotEmpty()) {
                append("\n\n")
                append(
                    attachmentManifest(
                        usable,
                        visionToolAvailable,
                        ocrToolAvailable,
                        imagesAttached = images.isNotEmpty()
                    )
                )
            }
        }
        return OutgoingMessage(
            id = id,
            role = role,
            text = text,
            images = images
        )
    }

    /**
     * 附件清单：交给 [com.mistakebook.data.chat.AttachmentManifest] 生成
     * （那边是纯逻辑，可 JVM 单测）。
     */
    private fun attachmentManifest(
        attachments: List<com.mistakebook.data.local.entities.ChatAttachment>,
        visionToolAvailable: Boolean,
        ocrToolAvailable: Boolean,
        imagesAttached: Boolean
    ): String = com.mistakebook.data.chat.AttachmentManifest.build(
        attachments = attachments,
        visionToolAvailable = visionToolAvailable,
        ocrToolAvailable = ocrToolAvailable,
        imagesAttached = imagesAttached
    )

    /**
     * 会话里还没有 OCR 文字的图片附件（最新的在前）。
     *
     * 用于「模型不识图时补做 OCR」：历史轮次里的图片也得有文字，
     * 否则用户问「刚才那张图里的第三题是什么」时，模型什么都看不到。
     *
     * 注意「没有文字」包含**失败说明**（见 [ChatOcrText.failureNote]）——
     * 那条说明本身就是文字，带着它就不会再重传。
     */
    suspend fun imageAttachmentsWithoutOcr(sessionId: Long): List<ChatAttachment> =
        chatDao.imageAttachmentsOf(sessionId).filter { it.textExcerpt.isBlank() }

    /** 会话里全部图片附件（含已识别与识别失败的），供「重试」挑出失败的那几张。 */
    suspend fun imageAttachmentsOf(sessionId: Long): List<ChatAttachment> =
        chatDao.imageAttachmentsOf(sessionId)

    /** 回写图片附件的 OCR 文字。 */
    suspend fun updateAttachmentOcr(id: Long, text: String) {
        chatDao.updateAttachmentText(id, text, text.length)
    }

    /** 清掉附件上的 OCR 文字（用户点「重试」时把失败说明撤掉，重新排队）。 */
    suspend fun clearAttachmentOcr(id: Long) {
        chatDao.updateAttachmentText(id, "", 0)
    }

    // ------------------------------------------------------------ 辅助

    private fun ChatMessage.attachmentIds(): List<Long> {
        if (attachmentIdsJson.isBlank()) return emptyList()
        return runCatching {
            val element = json.parseToJsonElement(attachmentIdsJson) as? JsonArray ?: return emptyList()
            element.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
        }.getOrDefault(emptyList())
    }

    /** 首条用户消息的前若干字当标题。已重命名过的会话不覆盖。 */
    private suspend fun touchSessionWith(sessionId: Long, firstUserText: String, now: Long) {
        val session = chatDao.findSession(sessionId) ?: return
        if (session.titleLocked) {
            chatDao.refreshSessionStats(sessionId, now)
            return
        }
        val trimmed = firstUserText.trim().replace(Regex("\\s+"), " ")
        val title = if (trimmed.length <= TITLE_MAX_CHARS) trimmed else trimmed.take(TITLE_MAX_CHARS) + "…"
        chatDao.updateSession(session.copy(title = title, updatedAt = now))
        chatDao.refreshSessionStats(sessionId, now)
    }

    private suspend fun buildQuestionContext(questionId: Long): String? {
        val question = questionDao.findById(questionId) ?: return null
        val subjectName = question.subjectId?.let { subjectDao.findById(it)?.name }.orEmpty()
        val options = runCatching {
            json.decodeFromString(optionListSerializer, question.optionsJson)
        }.getOrDefault(emptyList())
        // 知识点存的是 JSON 字符串数组（`json.encodeToString(List<String>)`），
        // 不是对象数组——早先按 `{name:...}` 解析会静默得到空列表。
        val knowledgePoints = runCatching {
            val element = json.parseToJsonElement(question.knowledgePointsJson) as? JsonArray
            element?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
        }.getOrDefault(emptyList())
        return ChatPrompts.questionContext(
            title = question.title,
            stem = question.stem,
            options = options,
            answer = question.answer,
            analysis = question.analysis,
            subjectName = subjectName,
            knowledgePoints = knowledgePoints,
            difficulty = question.difficulty,
            errorReasonName = question.errorReason.name
        )
    }

    private companion object {
        const val TAG = "ChatRepository"
        const val TITLE_MAX_CHARS = 20
    }
}
