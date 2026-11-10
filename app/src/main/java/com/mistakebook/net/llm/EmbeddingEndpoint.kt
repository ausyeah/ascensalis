package com.mistakebook.net.llm

import com.mistakebook.net.HttpFactory
import com.mistakebook.data.prefs.LlmProfile

/**
 * 资料库向量化的独立配置（v0.0.119）。
 *
 * ## 为什么单独可配
 * v0.0.113 定的是「复用大模型配置」，理由是省一次填写。实际用起来这个捷径
 * 经常不成立：对话走一家网关（便宜的生成模型），嵌入要走另一家（bge-m3 /
 * text-embedding-v3 往往在别处，甚至同一家的不同 Key），用户被迫把对话
 * 配置改来改去。所以三项拆出来各自可填，**留空才回退**到当前大模型配置——
 * 老用户的既有行为原样保留。
 *
 * @param baseUrl 形如 `https://api.xxx.com/v1`；直接粘 `.../embeddings` 也认
 * @param apiKey 只用于嵌入请求的 Key；空 = 跟对话配置共用
 * @param model 向量化模型名；空 = 不启用语义检索（只走关键词）
 */
data class EmbeddingConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
)

/**
 * 把用户填的配置 + 当前大模型配置**收敛成一次真实请求**的三要素。
 *
 * 纯函数：URL 拼法、斜杠、`Bearer`、`/embeddings` 结尾的判断都能单测钉住，
 * 不用起网络。返回 `null` 表示这份配置跑不了检索——上层据此降级到关键词
 * 检索，而不是发一个注定 401 的请求。
 */
object EmbeddingEndpoint {

    /** 一次嵌入请求需要的东西。 */
    data class Resolved(
        val url: String,
        val authorization: String,
        val model: String
    )

    /** 嵌入接口路径（OpenAI 兼容约定，与 chat 同级）。 */
    private const val EMBEDDINGS_PATH = "embeddings"

    fun resolve(config: EmbeddingConfig, profile: LlmProfile?): Resolved? {
        val model = config.model.trim()
        if (model.isEmpty()) return null

        // 逐项回退：只填了模型名 = 老行为（借对话配置）
        val baseUrl = config.baseUrl.trim().ifBlank { profile?.baseUrl.orEmpty().trim() }
        val apiKey = config.apiKey.trim().ifBlank { profile?.apiKey.orEmpty().trim() }
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return null

        return Resolved(
            url = endpointUrl(baseUrl),
            authorization = HttpFactory.bearer(apiKey),
            model = model
        )
    }

    /**
     * 接口地址归一。
     *
     * 用户复制来的地址可能是 `https://x/v1`，也可能已经是
     * `https://x/v1/embeddings`（很多控制台的「示例地址」直接给到接口）。
     * 两种都认，避免拼成 `.../embeddings/embeddings`。
     */
    internal fun endpointUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/$EMBEDDINGS_PATH", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/$EMBEDDINGS_PATH"
        }
    }
}