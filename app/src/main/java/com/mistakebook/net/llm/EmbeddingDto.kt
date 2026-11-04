package com.mistakebook.net.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容 `/v1/embeddings` 接口的请求与响应（v0.0.113）。
 *
 * 绝大多数 OpenAI 兼容网关（DeepSeek / 通义 / SiliconFlow / 中转站）
 * 都实现了这个形状：请求 `{model, input:[...]}`，响应
 * `{data:[{index, embedding:[...]}], usage:{...}}`。
 * 与 chat 不同点：`input` 是**字符串数组**，一次请求可批量算多条，
 * 索引器按批（≤16 条）发请求摊薄往返开销。
 */
@Serializable
data class EmbeddingRequest(
    val model: String,
    val input: List<String>
)

@Serializable
data class EmbeddingResponse(
    val data: List<Item> = emptyList(),
    val usage: Usage? = null
) {
    /** 按 [EmbeddingRequest.input] 的下标回传，客户端应按 index 对齐而不是按顺序。 */
    @Serializable
    data class Item(
        val index: Int = 0,
        val embedding: List<Double> = emptyList(),
        val `object`: String? = null
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0
    )
}
