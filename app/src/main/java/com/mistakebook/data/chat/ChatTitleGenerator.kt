package com.mistakebook.data.chat

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.net.ApiError
import com.mistakebook.net.llm.ChatStreamEvent
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ChatMessage as LlmChatMessage
import android.util.Log
import kotlinx.serialization.json.JsonPrimitive

/**
 * 用默认模型给对话起标题（v0.0.108）。
 *
 * ## 为什么不再拿第一句话当标题
 * 「这道题第二问为什么用洛必达不对啊我算出来是 0」截断成 20 个字，
 * 历史里全是半截句子，扫一眼根本分不清哪条是哪条。标题的作用是**概括**，
 * 那正是模型的活。
 *
 * ## 为什么要一次非流式调用
 * 标题只有几个字，用户看不到它生成的过程；流式反而要多处理一堆事件。
 * 失败也不影响对话——[ChatRepository] 里那句截断兜底仍然在。
 *
 * ## 为什么不覆盖用户改过的名字
 * 重命名会把 `titleLocked` 置位，仓储据此拒绝写入。用户的手动命名优先。
 */
class ChatTitleGenerator(
    private val stream: ChatCompletionStream,
    private val usageStatsStore: UsageStatsStore
) {

    /** 返回可用标题；没配模型、调用失败、输出不像标题时返回 null。 */
    suspend fun generate(profile: LlmProfile?, firstUserText: String): String? {
        if (profile == null || !profile.isConfigured()) return null
        val text = firstUserText.trim()
        if (text.isEmpty()) return null

        val request = ChatRequest(
            model = profile.model,
            messages = listOf(
                LlmChatMessage(
                    role = "system",
                    content = JsonPrimitive(PROMPT)
                ),
                LlmChatMessage(
                    role = "user",
                    content = JsonPrimitive(text.take(MAX_INPUT_CHARS))
                )
            ),
            temperature = 0.3,
            // 2048 而不是 32：实测 sensenova 这类推理模型光「想」就要几百
            // 到一两千个 token，预算给小了返回的正文是空的——标题永远生成不出来。
            // 一个会话只调用一次，这点预算是值得的。
            max_tokens = 2048,
            responseFormat = null,
            // 走流式：见下面那段解释
            stream = true
        )

        // **走流式而不是 completeOnce**（v0.0.108 实测）：同一把 key、同一个模型，
        // 对话流式有内容，非流式的 message.content 却是空的——推理型服务在
        // 非流式路径上不吐正文。标题要的是「拿到字」，路径选错就永远是空的。
        val out = StringBuilder()
        var failure: ApiError? = null
        stream.complete(profile, request, effort = ReasoningEffort.LOW).collect { event ->
            when (event) {
                is ChatStreamEvent.Delta -> out.append(event.text)
                is ChatStreamEvent.Done -> usageStatsStore.recordLlm(
                    promptTokens = event.promptTokens,
                    completionTokens = event.completionTokens,
                    cachedTokens = event.cachedTokens
                )
                is ChatStreamEvent.Failed -> failure = event.error
                else -> Unit
            }
        }
        if (failure != null) return null
        Log.i(TAG, "标题原始输出: ${out.take(80)}")
        return cleanTitle(out.toString())
    }

    internal companion object {

        /**
         * 把模型的输出收拾成一行标题。
         *
         * 模型偶尔会带「标题：」前缀、引号、书名号或者多行解释——这些都不该
         * 出现在会话列表里。解析成 null 时，调用方保留原来的截断标题。
         */
        internal fun cleanTitle(raw: String): String? {
            val line = raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return null

            val stripped = line
                .replace(Regex("^(标题|题目|主题|Topic)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .trim('"', '\'', '「', '」', '『', '』', '《', '》', '`', '*', '【', '】')
                .trim()
                .trimEnd('。', '！', '!', '.', '、', '，', ',', '；', ';', '：', ':')

            if (stripped.isEmpty()) return null
            // 超过长度上限说明模型开始解释了，而不是在起标题——宁可不要
            if (stripped.length > MAX_TITLE_CHARS + 6) return null
            return if (stripped.length <= MAX_TITLE_CHARS) {
                stripped
            } else {
                stripped.take(MAX_TITLE_CHARS) + "…"
            }
        }

        const val TAG = "ChatTitleGenerator"
        const val MAX_INPUT_CHARS = 400
        const val MAX_TITLE_CHARS = 16

        val PROMPT = """
            你是会话标题生成器。用户会给你一段对话的开头发言，你要给它起一个标题。
            要求：
            - 不超过 12 个字，概括这段对话要解决什么；
            - 用中文，不要标点结尾，不要引号、书名号；
            - 只输出标题本身，不要「标题：」之类的前缀，不要解释。
        """.trimIndent()
    }
}
