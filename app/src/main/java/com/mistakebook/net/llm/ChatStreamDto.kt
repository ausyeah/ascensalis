package com.mistakebook.net.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SSE 流式响应的一帧。
 *
 * 与非流式的 [ChatResponse] 分开：**流式给的是 `delta`（增量），
 * 非流式给的是 `message`（全量）**，字段名不同，共用一个类必然有一边读不到。
 */
@Serializable
data class ChatStreamChunk(
    val id: String = "",
    val choices: List<StreamChoice> = emptyList(),

    /** 只有显式要求 `stream_options.include_usage` 时才有。多数中转站不给。 */
    val usage: Usage? = null,

    /** 少数服务会带错误体在 200 响应里。 */
    val error: StreamError? = null
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta? = null,

    /**
     * `stop` = 正常结束；`length` = **被 max_tokens 截断**。
     *
     * 截断必须单独识别：它和「模型胡乱输出」在下游表现完全一样
     * （都是半截内容），但处理方式不同。
     */
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    val role: String? = null,

    /** 正文。思考过程不在这里——见 [reasoningContent]。 */
    val content: String? = null,

    /**
     * **思考过程**（推理模型的适用字段）。
     *
     * 为什么三个字段都要读：各家的命名不一样——DeepSeek / Qwen 系用
     * `reasoning_content`，部分中转站用 `reasoning`，略带思考的接口用 `thinking`。
     * 只读其中一个的话，用户就会看到一个只有蓝点、没有内容的空气泡。
     *
     * 全部有默认值 → 未知的额外字段不会因为缺失而抛异常。
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    val thinking: String? = null,

    /**
     * 工具调用增量（v0.0.43）。
     *
     * 流式里一次调用被拆成多个分片：首片带 [StreamToolCallDelta.id] 与
     * function.name，后续分片只带 function.arguments 的**字符串片段**，
     * 按 [StreamToolCallDelta.index] 归属同一次调用。accumulate 逻辑见
     * [com.mistakebook.net.llm.accumulateToolCall]。
     */
    @SerialName("tool_calls") val toolCalls: List<StreamToolCallDelta>? = null
) {
    /** 取第一个非空的思考字段。 */
    fun reasoningOrNull(): String? =
        reasoningContent?.takeIf { it.isNotEmpty() }
            ?: reasoning?.takeIf { it.isNotEmpty() }
            ?: thinking?.takeIf { it.isNotEmpty() }
}

@Serializable
data class StreamError(
    val message: String = "",
    val type: String = ""
)

/** 一次工具调用在流里的一个分片。 */
@Serializable
data class StreamToolCallDelta(
    /** 同一次调用在所有分片里共享同一个 index（并行调用时从 0 递增）。 */
    val index: Int = 0,

    /** 只有首片带；后续分片为 null。 */
    val id: String? = null,
    val type: String? = null,
    val function: StreamFunctionDelta? = null
)

@Serializable
data class StreamFunctionDelta(
    /** 只有首片带。 */
    val name: String? = null,

    /** 参数 JSON 的**字符串片段**，要按 index 累积拼接。 */
    val arguments: String? = null
)
