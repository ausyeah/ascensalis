package com.mistakebook.data.repos

import com.mistakebook.data.chat.MemoryPrompts
import com.mistakebook.data.local.ChatDao
import com.mistakebook.data.local.MemoryDao
import com.mistakebook.data.local.entities.Memory
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.net.ApiResult
import com.mistakebook.net.llm.ChatCompletionStream
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ChatMessage as LlmChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive

/**
 * 记忆库（v0.0.108）。
 *
 * ## 它解决什么
 * 每次新对话都从零开始，用户被迫重复交代同一件事（「我在准备考研」
 * 「不要用 emoji」）。记忆把这类**长期有效**的信息存成短句，注入系统提示词。
 *
 * ## 为什么让模型整理，而不是全部自动收集
 * 对话里 95% 的内容是一次性的（这道题怎么做、这段代码报什么错），
 * 全记住等于往上下文里灌噪音。整理是一次显式的、用户主动触发的动作：
 * 挑出「下次还用得上」的那几条，剩下的丢掉。
 *
 * ## 为什么整理的输入只有最近的对话
 * 记忆要的是反复出现的东西。翻遍全部历史既慢又容易被一次性的旧话题带偏，
 * 最近这些内容已经足够看出「他最近在干什么、在意什么」。
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val chatDao: ChatDao,
    private val stream: ChatCompletionStream,
    private val usageStatsStore: UsageStatsStore
) {

    fun observeAll(): Flow<List<Memory>> = memoryDao.observeAll()

    /**
     * 手动加一条（也供整理结果落库）。
     *
     * @return false 表示空、太长被裁成空、或与已有记忆重复。
     */
    suspend fun add(text: String, fromModel: Boolean = false): Boolean {
        val content = text.trim().replace(Regex("\\s+"), " ").take(MemoryPrompts.MAX_ITEM_CHARS)
        if (content.isEmpty()) return false
        val existing = memoryDao.allOnce().map { it.content }
        if (MemoryPrompts.removeDuplicates(listOf(content), existing).isEmpty()) return false
        val now = System.currentTimeMillis()
        memoryDao.insert(
            Memory(content = content, fromModel = fromModel, createdAt = now, updatedAt = now)
        )
        return true
    }

    suspend fun delete(id: Long) = memoryDao.delete(id)

    /** 注入系统提示词的那段文本；没有记忆时是空串（不注入空标题）。 */
    suspend fun blockText(): String =
        MemoryPrompts.injectBlock(memoryDao.allOnce().map { it.content })

    /**
     * 让默认模型从最近的对话里整理一批新记忆。
     *
     * @return 新增条数与失败原因（成功时 error 为 null）。
     */
    suspend fun organize(profile: LlmProfile?): OrganizeResult {
        if (profile == null || !profile.isConfigured()) {
            return OrganizeResult(added = 0, error = "还没有配置默认模型")
        }

        val recent = chatDao.recentSpoken(TRANSCRIPT_MESSAGES)
        if (recent.isEmpty()) return OrganizeResult(added = 0, error = "还没有聊过天，没东西可整理")

        val existing = memoryDao.allOnce().map { it.content }
        val prompt = MemoryPrompts.extractPrompt(
            transcript = buildTranscript(recent),
            existing = existing
        )
        val request = ChatRequest(
            model = profile.model,
            messages = listOf(LlmChatMessage(role = "user", content = JsonPrimitive(prompt))),
            temperature = 0.2,
            max_tokens = EXTRACT_MAX_TOKENS,
            responseFormat = null,
            stream = false
        )

        return when (
            val result = stream.completeOnce(
                profile,
                request,
                // 整理要判断「哪些信息值得长期记住」，这是理解题而不是填空，
                // 但也不必顶格思考——这类判断一次想清就够了。
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
                val candidates = MemoryPrompts.onlyAboutUser(
                    MemoryPrompts.parseCandidates(result.data)
                )
                val fresh = MemoryPrompts.removeDuplicates(candidates, existing)
                val now = System.currentTimeMillis()
                fresh.forEach { content ->
                    memoryDao.insert(
                        Memory(
                            content = content,
                            fromModel = true,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                OrganizeResult(added = fresh.size, error = null)
            }

            is ApiResult.Failure -> OrganizeResult(
                added = 0,
                error = result.error.serverMessage.ifBlank { result.error.kind.name }
            )
        }
    }

    /**
     * 把最近的发言拼成一段可读的对话记录。
     *
     * 按时间正序写（模型读因果更顺），并且**从旧到新**累加到字数上限——
     * 超预算时丢掉最久远的那几轮，而不是拦腰截断一句话。
     */
    private fun buildTranscript(recent: List<com.mistakebook.data.local.RecentSpoken>): String {
        val lines = recent.asReversed().mapNotNull { spoken ->
            val text = spoken.content.trim().take(MAX_MESSAGE_CHARS)
            if (text.isEmpty()) return@mapNotNull null
            val speaker = if (spoken.role == "USER") "用户" else "AI"
            "$speaker：$text"
        }
        val kept = ArrayDeque<String>()
        var used = 0
        // 从最新往回挑，挑满预算后再正序输出
        for (line in lines.asReversed()) {
            if (used + line.length > TRANSCRIPT_CHARS) break
            kept.addFirst(line)
            used += line.length + 1
        }
        return kept.joinToString("\n")
    }

    /** 整理结果。 */
    data class OrganizeResult(val added: Int, val error: String?)

    private companion object {
        /** 最多看这么多条发言：够看出最近在做什么，又不至于把旧话题翻出来。 */
        const val TRANSCRIPT_MESSAGES = 120
        const val TRANSCRIPT_CHARS = 6000
        const val MAX_MESSAGE_CHARS = 300
        const val EXTRACT_MAX_TOKENS = 600
    }
}