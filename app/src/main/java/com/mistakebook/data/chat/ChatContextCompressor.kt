package com.mistakebook.data.chat

import com.mistakebook.data.local.ChatDao
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.net.ApiResult
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatMessage as LlmChatMessage
import com.mistakebook.net.llm.ChatRequest
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话上下文压缩器：把旧轮次压成一条摘要，模型从此「记得」前面聊过什么。
 *
 * ## 为什么需要它
 *
 * [ChatContextAssembler] 的超预算策略是**整轮丢弃**——被丢掉的轮次对模型
 * 就是不存在。短会话无所谓，长会话（一道题追问答了十几轮）里，
 * 用户第五轮提到的约束、第二轮的结论，全都凭空消失，模型开始重复提问、
 * 自相矛盾。硬截断保住了请求体，保不住连续性。
 *
 * ## 策略（对齐 Claude Code `/compact` 的实战经验）
 *
 * - **滚动摘要**：新摘要在「旧摘要 + 新变旧的那些轮次」上生成，
 *   不是每次从全量历史重压——越滚越省，且不会丢更早的结论。
 * - **保最近几轮原文**：最近 [KEEP_RECENT] 条不进摘要。刚聊过的内容
 *   正是语义最强的上下文，压成摘要反而丢细节。
 * - **自动压缩是安全网，不是规划**：只在历史真的被 assembler 丢过
 *   （omittedCount > 0）时才自动触发；日常不主动压，让原文尽量多地
 *   留在请求里。用户也可手动点「压缩」。
 * - **摘要透明可见**：落库为一条真实的 SYSTEM 消息，UI 里显示成低调的
 *   提示条——压缩不是幕后动作，用户要知道模型现在「记得」的是什么。
 *
 * ## 为什么摘要不走流式
 *
 * 压缩是「请求发出前的准备工作」，用户不需要看它逐字生成；
 * 一次非流式调用拿完整结果最直接（[ChatCompletionStream.completeOnce]）。
 */
class ChatContextCompressor(
    private val chatDao: ChatDao,
    private val stream: ChatCompletionStream
) {

    /**
     * 压缩指定会话。返回新摘要内容；历史太短或调用失败时返回 null（调用方无感）。
     *
     * @param profile 生效中的大模型配置。未配置时直接放弃——
     *   压缩失败不影响主对话，只是没有摘要而已。
     */
    suspend fun compress(sessionId: Long, profile: LlmProfile): String? {
        if (!profile.isConfigured()) return null
        val messages = chatDao.messagesExcludingInjected(sessionId)
        if (messages.size <= KEEP_RECENT) return null

        val oldSummary = messages.firstOrNull { it.isSummaryMessage() }
        // 旧摘要本身不参与转录：它的内容已经并进新摘要的提示词里。
        val candidates = messages.filterNot { it.isSummaryMessage() }
        val toSummarize = candidates.dropLast(KEEP_RECENT)
        if (toSummarize.isEmpty()) return null

        val transcript = toSummarize.joinToString("\n\n") { message ->
            val who = if (message.role == ChatRole.USER) "用户" else "助手"
            val body = message.content.trim().take(MAX_MESSAGE_CHARS)
            "$who：$body"
        }

        val prompt = buildString {
            append("把下面的对话历史压缩成一段连续性摘要，供后续对话续接上下文。\n")
            append("要求：\n")
            append("1. 不超过 $SUMMARY_MAX_CHARS 字；\n")
            append("2. 保留：讨论过的题目与结论、用户明确的约束与偏好、尚未解决的问题；\n")
            append("3. 略过：寒暄、重复的确认、已经推翻的中间尝试；\n")
            append("4. 直接输出摘要正文，不要解释你在做什么。\n")
            if (!oldSummary?.content.isNullOrBlank()) {
                append("\n【已有摘要，请在其基础上合并更新，不要丢失其中的结论】\n")
                append(oldSummary!!.content.removePrefix(ChatContextAssembler.SUMMARY_PREFIX).trim())
                append("\n")
            }
            append("\n【对话历史】\n")
            append(transcript)
        }

        val request = ChatRequest(
            model = profile.model,
            messages = listOf(
                LlmChatMessage(role = "user", content = JsonPrimitive(prompt))
            ),
            temperature = 0.2,
            max_tokens = SUMMARY_MAX_TOKENS,
            responseFormat = null,
            stream = false
        )

        return when (val result = stream.completeOnce(profile, request)) {
            is ApiResult.Success -> {
                val body = result.data.trim()
                if (body.isEmpty()) return null
                val summary = ChatContextAssembler.SUMMARY_PREFIX + body
                upsertSummaryMessage(sessionId, oldSummary, summary)
                summary
            }

            is ApiResult.Failure -> null
        }
    }

    /** 读会话当前的摘要（没有则 null）。 */
    suspend fun summaryOf(sessionId: Long): String? =
        chatDao.messagesExcludingInjected(sessionId)
            .firstOrNull { it.isSummaryMessage() }
            ?.content
            ?.takeIf { it.isNotBlank() }

    private fun ChatMessage.isSummaryMessage(): Boolean =
        role == ChatRole.SYSTEM && content.startsWith(ChatContextAssembler.SUMMARY_PREFIX)

    /** 有旧摘要就地更新（保持位置在最前），没有就插一条。 */
    private suspend fun upsertSummaryMessage(
        sessionId: Long,
        oldSummary: ChatMessage?,
        summary: String
    ) {
        val now = System.currentTimeMillis()
        if (oldSummary != null) {
            chatDao.updateMessage(oldSummary.copy(content = summary, updatedAt = now))
        } else {
            chatDao.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    role = ChatRole.SYSTEM,
                    content = summary,
                    status = MessageStatus.DONE,
                    injected = false,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    companion object {
        /** 最近多少条消息不进摘要，保留原文。 */
        const val KEEP_RECENT = 6

        /** 单条消息进转录时的字符上限，防止一条暴长的消息吃满提示词。 */
        const val MAX_MESSAGE_CHARS = 1_200

        /** 摘要正文字数上限。 */
        const val SUMMARY_MAX_CHARS = 400

        /** 摘要生成的输出 token 预算：400 中文字 + 余量，远到不了截断线。 */
        const val SUMMARY_MAX_TOKENS = 1_024
    }
}
