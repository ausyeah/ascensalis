package com.mistakebook.net.llm

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory

/**
 * 向量嵌入客户端（v0.0.113，资料库语义检索用）。
 *
 * ## 为什么复用大模型配置
 * 仓库硬规则「不引入任何新依赖」——端侧跑嵌入模型（ONNX/MediaPipe）
 * 需要引运行时，直接排除。而用户配的 OpenAI 兼容网关几乎都带
 * `/v1/embeddings`，**一次配置、两个用途**：资料库向量化只多要一个
 * 模型名（如 `text-embedding-v3` / `BAAI/bge-m3`）。
 *
 * 好处是零成本接入；代价是嵌入要走网络（索引一次资料库要联网）。
 * 这个取舍写在这里：检索请求本身在本地（暴力余弦），只有**建索引**
 * 需要联网，之后加资料不重建索引就不产生流量。
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
        profile: LlmProfile,
        model: String,
        inputs: List<String>
    ): ApiResult<List<FloatArray>> {
        if (model.isBlank()) {
            return ApiResult.Failure(ApiError(ApiErrorKind.NO_KEY, detail = "未配置向量化模型"))
        }
        if (inputs.isEmpty()) return ApiResult.Success(emptyList())

        val url = profile.baseUrl.trimEnd('/') + "/" + EMBEDDINGS_PATH
        val request = EmbeddingRequest(model = model, input = inputs)
        return try {
            val response = api.embeddings(
                url = url,
                authorization = "Bearer " + profile.apiKey,
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
        /** Base URL 以 /v1 结尾的约定下，嵌入路径与 chat 同级。 */
        const val EMBEDDINGS_PATH = "embeddings"
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
