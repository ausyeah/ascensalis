package com.mistakebook.net.llm

import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory

/**
 * 向量嵌入客户端（v0.0.113，资料库语义检索用）。
 *
 * ## 接口从哪来
 * 仓库硬规则「不引入任何新依赖」——端侧跑嵌入模型（ONNX/MediaPipe）
 * 需要引运行时，直接排除。嵌入只能走网络，配置由 [EmbeddingConfig]
 * 提供：地址 / Key / 模型名三项**各自可填**，留空才回退到当前大模型
 * 配置（v0.0.113 的老行为）。最终拼成什么由 [EmbeddingEndpoint] 决定。
 *
 * 代价是索引一次资料库要联网。这个取舍写在这里：检索时的余弦比对在本地，
 * 只有**建索引**需要联网，之后加资料不重建索引就不产生流量。
 */
class EmbeddingClient(private val api: LlmApi) {

    /** 单批最多多少条 input：批太大会撞网关请求体/耗时上限。 */
    val batchSize: Int get() = BATCH_SIZE

    /**
     * 批量嵌入。返回的列表按**调用方输入顺序**排列（内部按响应 index 对齐）。
     *
     * @param inputs 非空文本列表；调用方保证（空白文本没有嵌入意义）
     */
    suspend fun embed(
        endpoint: EmbeddingEndpoint.Resolved,
        inputs: List<String>
    ): ApiResult<List<FloatArray>> {
        if (endpoint.model.isBlank()) {
            return ApiResult.Failure(ApiError(ApiErrorKind.NO_KEY, detail = "未配置向量化模型"))
        }
        if (inputs.isEmpty()) return ApiResult.Success(emptyList())

        val request = EmbeddingRequest(model = endpoint.model, input = inputs)
        return try {
            val response = api.embeddings(
                url = endpoint.url,
                authorization = endpoint.authorization,
                request = request
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                val raw = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
                ApiResult.Failure(HttpFactory.httpError(response.code(), raw))
            } else {
                val vectors = parseEmbeddingResponse(body, inputs.size)
                    ?: return ApiResult.Failure(
                        ApiError(ApiErrorKind.BAD_RESPONSE, detail = "embeddings 响应缺少 data")
                    )
                ApiResult.Success(vectors)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ApiResult.Failure(HttpFactory.throwableError(t))
        }
    }

    private companion object {
        const val BATCH_SIZE = 16
    }
}

/**
 * 把响应按 index 对齐成有序向量，并归一化（检索用内积）。
 *
 * 解析独立成纯函数便于单测：`data` 缺失 / 条数对不上 / 某条 embedding
 * 为空都算坏响应——静默补零向量会让检索出现「永远不相关」的假结果。
 */
internal fun parseEmbeddingResponse(
    response: EmbeddingResponse,
    expected: Int
): List<FloatArray>? {
    val items = response.data
    if (items.size != expected) return null
    val out = ArrayList<FloatArray>(expected)
    val byIndex = items.associateBy { it.index }
    for (i in 0 until expected) {
        val item = byIndex[i] ?: return null
        if (item.embedding.isEmpty()) return null
        val vector = FloatArray(item.embedding.size) { j -> item.embedding[j].toFloat() }
        out.add(com.mistakebook.math.VectorMath.normalize(vector))
    }
    return out
}
