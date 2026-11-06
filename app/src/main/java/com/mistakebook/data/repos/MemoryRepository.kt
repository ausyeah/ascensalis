package com.mistakebook.data.repos

import com.mistakebook.data.chat.MemoryPrompts
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.chat.MemoryMerge
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.net.ApiResult
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatMessage as LlmChatMessage
import com.mistakebook.net.llm.ChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive

/**
 * 长期记忆（v0.0.112，单文档模型）。
 *
 * ## 它解决什么
 * 每次新对话都从零开始，用户被迫重复交代同一件事（「我在准备考研」
 * 「不要用 emoji」）。记忆把这类**长期有效**的信息存成一篇文章，
 * 按主题分节，整体注入系统提示词。
 *
 * ## 为什么是「一篇文章」而不是一条条记录
 * 记忆的消费方只有两个：注入提示词、给用户看。模型**改写**时能前后
 * 照应——把「用户在准备考研」和「每天背 20 个单词」归进同一节、
 * 把矛盾的说法换成新的；一条条记录要做到这些得先做条目级 diff，
 * 复杂度全在代码里，收益却只是文档更整齐。
 *
 * ## 「整理」与「请求改写」共用一条链路
 * 界面上无论用户是说要记住什么（「我叫Ausyeah，在准备考研」）、要忘掉什么，
 * 还是点「整理」把杂乱内容重排成分节条目，走的都是同一次模型改写：
 * 当前全文 + 请求 → 全新全文。差别只在请求文本由谁提供。
 */
class MemoryRepository(
    private val settingsStore: SettingsStore,
    private val stream: ChatCompletionStream,
    private val usageStatsStore: UsageStatsStore
) {

    /** 记忆全文的订阅流（空串 = 还没有记忆）。 */
    val document: Flow<String> = settingsStore.memoryDocument

    suspend fun documentNow(): String = settingsStore.memoryDocumentNow()

    suspend fun versionNow(): Int = settingsStore.memoryVersionNow()

    suspend fun updatedAtNow(): Long = settingsStore.memoryUpdatedAtNow()

    /** 手动编辑：用户在编辑框里直接改全文。空文本允许（= 清空记忆）。 */
    suspend fun saveManually(text: String) {
        settingsStore.saveMemoryDocument(text.trim())
    }

    /**
     * 直接把若干条事实并进记忆文档（v0.0.128，issue #42）。
     *
     * 供两条路径使用：模型在对话里调用 `save_memory` 工具；以及每轮结束后
     * 的自动提取。**不调模型**——只做去重、追加到「对话里记下的」一节、
     * 超长时裁掉该节最早的条目（人工写的分节不动）。
     *
     * @return 实际写入的新条目数（全是重复时为 0，此时不写盘）。
     */
    suspend fun appendFacts(facts: List<String>): Int {
        if (facts.isEmpty()) return 0
        val before = documentNow()
        val merged = MemoryMerge.merge(before, facts)
        if (merged == before) return 0
        val added = merged.length - before.length
        settingsStore.saveMemoryDocument(merged)
        return if (added > 0) facts.size else 0
    }

    /**
     * 用户在输入框里说要记住 / 忘记什么，交给模型改写全文。
     *
     * @return 新版本号；失败时 [RewriteResult.error] 带原因。
     */
    suspend fun applyRequest(request: String, profile: LlmProfile?): RewriteResult {
        if (request.isBlank()) return RewriteResult(versionNow(), "请求是空的")
        return rewrite(MemoryPrompts.rewriteUserPrompt(documentNow(), request), profile)
    }

    /** 把现有内容整理成分节条目的清晰形式（不改事实，只重排）。 */
    suspend fun organize(profile: LlmProfile?): RewriteResult =
        rewrite(MemoryPrompts.rewriteUserPrompt(documentNow(), MemoryPrompts.ORGANIZE_REQUEST), profile)

    /** 注入系统提示词的那段文本；没有记忆时是空串（不注入空标题）。 */
    suspend fun blockText(): String = MemoryPrompts.injectDocument(documentNow())

    private suspend fun rewrite(userPrompt: String, profile: LlmProfile?): RewriteResult {
        if (profile == null || !profile.isConfigured()) {
            return RewriteResult(versionNow(), error = "还没有配置默认模型")
        }
        val request = ChatRequest(
            model = profile.model,
            messages = listOf(
                LlmChatMessage(role = "system", content = JsonPrimitive(MemoryPrompts.REWRITE_SYSTEM)),
                LlmChatMessage(role = "user", content = JsonPrimitive(userPrompt))
            ),
            temperature = 0.2,
            // 记忆全文的改写（+推理模型的思考）预算必须给足；
            // 给小了正文被思考吃光，返回空串——对话标题那已经踩过一遍。
            max_tokens = 2048,
            responseFormat = null,
            stream = false
        )
        return when (
            val result = stream.completeOnce(
                profile,
                request,
                // 改写要通读全文、归并矛盾，是理解题；但也不必顶格思考。
                effort = ReasoningEffort.LOW,
                onDone = { done ->
                    usageStatsStore.recordLlm(
                        promptTokens = done.promptTokens,
                        completionTokens = done.completionTokens,
                        cachedTokens = done.cachedTokens
                    )
                }
            )
        ) {
            is ApiResult.Success -> {
                val doc = cleanRewrite(result.data)
                if (doc.isEmpty()) {
                    RewriteResult(versionNow(), error = "模型没有返回内容")
                } else {
                    settingsStore.saveMemoryDocument(doc)
                    RewriteResult(settingsStore.memoryVersionNow(), error = null)
                }
            }

            is ApiResult.Failure -> RewriteResult(
                versionNow(),
                error = result.error.serverMessage.ifBlank { result.error.kind.name }
            )
        }
    }

    /**
     * 收拾模型输出：剥掉客套开场白、截掉超长跑题的部分。
     *
     * 「（空）」表示改写后什么都没有了——按空文档保存。
     */
    private fun cleanRewrite(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        // 常见开场白：到第一个换行为止的客套话里带着「好的/以下是/这是」
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        if (text.lineSequence().count() > 1 &&
            (firstLine.contains("以下是") || firstLine.contains("好的") || firstLine.contains("这是"))
        ) {
            text = text.substringAfter('\n').trim()
        }
        if (text.length > MemoryPrompts.MAX_DOC_CHARS) {
            text = text.take(MemoryPrompts.MAX_DOC_CHARS)
        }
        return if (text == "（空）" || text == "(空)") "" else text.trim()
    }

    /** 改写结果：新版本号 + 失败原因（成功时 error 为 null）。 */
    data class RewriteResult(val version: Int, val error: String?)
}
