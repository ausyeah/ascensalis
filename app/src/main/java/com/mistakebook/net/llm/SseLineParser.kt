package com.mistakebook.net.llm

import kotlinx.serialization.json.Json

/**
 * 一帧 SSE 的解析结果。
 */
sealed interface SseEvent {

    /** 注释行、`event:`/`id:` 之类与内容无关的行。 */
    data object Ignore : SseEvent

    /**
     * 一个增量。
     *
     * @param finishReason 该帧携带的结束原因。`length` 表示**被 max_tokens 截断**。
     * @param usage 该帧携带的 token 用量。中转站大多不给，为 0。
     */
    data class Chunk(
        val text: String,

        /** 思考过程增量。与 [text] 分开，上层才能分别渲染。 */
        val thinking: String = "",
        val finishReason: String? = null,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,

        /** 命中提示词缓存的输入 token。中转站大多不给，为 0。 */
        val cachedTokens: Int = 0
    ) : SseEvent

    /** `data: [DONE]` —— 服务端正常收尾。 */
    data object Done : SseEvent

    /**
     * 收到 `data:` 但 JSON 解不开，或结构不是预期的样子。
     *
     * **不回滚、不中断**：[ChatCompletionStream] 会统计它，
     * 只有「一帧都没解出来」才降级非流式——已经吐了半截再降级会让用户看到重复内容。
     */
    data class Malformed(val raw: String) : SseEvent
}

/**
 * SSE 逐行解析器。**纯逻辑，无 Android 依赖，可 JVM 单测。**
 *
 * ## 为什么要自己写
 *
 * 引入 SSE 库要新增依赖（项目规则禁止），而且各家的 SSE 方言差别不小：
 * 有的省略冒号后的空格、有的用 CRLF、自己写的解析器反而更好控。
 *
 * ## 为什么「一行就是一帧」，不按规范等空行再拼
 *
 * 规范要求事件以空行结尾、多条 `data:` 拼成一个事件。照做的话有个真实的坑：
 * **最后一帧会一直卡在缓冲区**，直到对端 EOF 或发来 `[DONE]` 才吐出来。
 * 而 chat 流式里每个事件固定就是**一行** `data:`（JSON 无法有意义地跨行），
 * 遵守规范换来的只有「最后一帧可能延迟甚至丢失」。
 *
 * 所以这里按行处理。代价是**不支持一个事件拆成多行 `data:`**——
 * 现实中没有服务这么干（`json.decodeFromString` 遇到跨行 JSON 也会因
 * 换行落在字符串字面量里而报错）。
 *
 * 两种真实方言都能收：省略冒号后空格（`data:{...}`）、CRLF 行尾。
 */
class SseLineParser(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** 喂一行（不含换行符）。返回 null 表示这一行不含内容。 */
    fun accept(line: String): SseEvent? {
        // 空行只是事件边界，本解析器按行处理时没有意义
        if (line.isBlank()) return null

        // 注释行：`: keep-alive`。中转站常用它保活。
        if (line.startsWith(":")) return SseEvent.Ignore

        if (!line.startsWith(DATA_PREFIX)) return SseEvent.Ignore

        // removePrefix(" ") 收「省略冒号后空格」，trim() 顺带收掉 CRLF 的 \r
        val raw = line.substring(DATA_PREFIX.length).removePrefix(" ").trim()
        if (raw.isEmpty()) return null

        if (raw == DONE_SENTINEL) return SseEvent.Done

        val chunk = runCatching { json.decodeFromString(ChatStreamChunk.serializer(), raw) }.getOrNull()
            ?: return SseEvent.Malformed(raw)

        // 少数服务在 HTTP 200 的流里塞错误体（额度用尽之类）。
        // 当成坏帧让上层决定，别把「额度用尽」渲染成一条看不懂的 JSON。
        chunk.error?.let { return SseEvent.Malformed(it.message.ifBlank { it.type }) }

        val usage = chunk.usage
        val choice = chunk.choices.firstOrNull()
        return SseEvent.Chunk(
            text = choice?.delta?.content.orEmpty(),
            thinking = choice?.delta?.reasoningOrNull().orEmpty(),
            finishReason = choice?.finishReason,
            promptTokens = usage?.promptTokens ?: 0,
            completionTokens = usage?.completionTokens ?: 0,
            cachedTokens = usage?.promptCacheHitTokens ?: 0
        )
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"
    }
}
