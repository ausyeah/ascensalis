package com.mistakebook.data.chat

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.repos.MemoryRepository
import com.mistakebook.net.ApiResult
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.net.llm.ChatMessage as LlmChatMessage
import com.mistakebook.net.llm.ChatRequest
import kotlinx.serialization.json.JsonPrimitive

/**
 * 对话结束后的记忆自动提取（v0.0.128，issue #42 第二半）。
 *
 * 用户：「也不会根据对话内容总结部分重要信息丢进记忆库」。
 *
 * ## 触发条件（两道闸，缺一不可）
 *
 * 1. 本轮用户那句话命中 [MemoryAutoExtract.shouldAttempt] 的措辞闸门
 *    ——「我叫/我准备/我喜欢/以后都…」这类长期信息才会花这次调用；
 * 2. 这一轮模型**没有**主动调用过 `save_memory`（免得重复记一遍）。
 *
 * ## 为什么放在 appScope 而不是生成链路里
 *
 * 提取是一次额外的 LLM 调用。用户已经看完整段回答了，没有理由再让
 * 「停止按钮」多转两秒、或者让服务延迟收尾——扔到应用作用域里后台跑，
 * 成功就静默写入，失败就当没发生（记忆是增益，不该产生错误提示）。
 */
class MemoryExtractor(
    private val memoryRepository: MemoryRepository,
    private val stream: ChatCompletionStream,
    private val usageStatsStore: UsageStatsStore
) {

    /** 一次提取最多回多少 token：五条短句足够。 */
    private val maxTokens = 320

    /**
     * 若判定值得提取，就调用模型抽取事实并合并进记忆库。
     *
     * @param userText 本轮用户输入
     * @param answer 本轮助手回答（用来判断话题，避免只靠一句口令瞎猜）
     * @param profile 当前模型配置
     * @param memoryWrittenThisTurn 本轮是否已经写过记忆（写了就跳过）
     * @return 本次写入的条数（0 = 没写），调用方可用于日志/测试。
     */
    suspend fun maybeExtract(
        userText: String,
        answer: String,
        profile: LlmProfile?,
        memoryWrittenThisTurn: Boolean
    ): Int {
        if (memoryWrittenThisTurn) return 0
        if (profile == null || !profile.isConfigured()) return 0
        if (!MemoryAutoExtract.shouldAttempt(userText)) return 0
        if (answer.isBlank()) return 0

        val request = ChatRequest(
            model = profile.model,
            messages = listOf(
                LlmChatMessage(role = "system", content = JsonPrimitive(EXTRACT_SYSTEM)),
                LlmChatMessage(
                    role = "user",
                    content = JsonPrimitive(
                        "【用户说】\n" + userText.trim().take(1200) +
                            "\n\n【助手回答节选】\n" + answer.trim().take(1200)
                    )
                )
            ),
            temperature = 0.1,
            max_tokens = maxTokens,
            responseFormat = null,
            stream = false
        )
        val result = stream.completeOnce(
            profile,
            request,
            effort = ReasoningEffort.LOW,
            onDone = { done ->
                runCatching {
                    usageStatsStore.recordLlm(
                        promptTokens = done.promptTokens,
                        completionTokens = done.completionTokens,
                        cachedTokens = done.cachedTokens
                    )
                }
            }
        )
        val raw = (result as? ApiResult.Success)?.data ?: return 0
        val facts = MemoryAutoExtract.parseFacts(raw)
        if (facts.isEmpty()) return 0
        return memoryRepository.appendFacts(facts)
    }

    companion object {
        /**
         * 提取提示词：只认「长期有效」的稳定事实，明确禁止一次性的题目与闲聊。
         *
         * 输出约束成「每行一条」，解析最简单；空结果允许输出「无」。
         */
        const val EXTRACT_SYSTEM: String = """
你是长期记忆的筛选器。从下面的对话里，挑出**关于用户本人的、长期有效的事实**：
- 身份与阶段（学生/在职、年级、专业、考的科目）
- 目标（要考什么、目标分数、时间节点）
- 偏好与习惯（喜欢的解法、学习节奏、常用工具）
- 约束（不能做的事、身体或时间上的限制）

不要记录：具体的某道题、一次性的请求、环境噪音、助手自己说的话。
只输出条目本身，每行一条，不超过 30 字，不要编号、不要解释。
一条都没有时，只输出「无」。
"""
    }
}