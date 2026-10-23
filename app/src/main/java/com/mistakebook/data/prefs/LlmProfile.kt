package com.mistakebook.data.prefs

import kotlinx.serialization.Serializable

/**
 * 一个附加的 API Key，以及它**绑定的模型**。
 *
 * ## 为什么需要
 *
 * 用户诉求：「不能给一个 URL 配置多个 key，从而实现一个 provider 供应多种
 * 模型，适配中转站实用」。
 *
 * 中转站（one-api / new-api 这类）的典型形态是：**一个 baseUrl，
 * 多个 key，每个 key 的可用模型和额度都不一样**。
 * 比如 key-A 只能用 deepseek，key-B 只能用 gpt-4o。
 * 而现在的模型是「一套配置 = 一个 baseUrl + 一个 key + 一个模型」，
 * 用户只能为同一个中转站重复建好几套配置，URL 抄三遍。
 *
 * 所以这里把「key」提升成一等公民：一个配置里可以放多个 key，
 * 每个 key 声明自己管哪些模型；请求时按模型名反查该用哪个 key。
 */
@Serializable
data class LlmKeyEntry(
    val id: String = java.util.UUID.randomUUID().toString(),

    /** 这个 key 本身。 */
    val key: String = "",

    /**
     * 这个 key 负责的模型名列表（**精确匹配**，大小写不敏感）。
     *
     * 空列表 = 「通用 key」：没有模型显式指定它时，作为兜底使用。
     */
    val models: List<String> = emptyList()
) {
    fun matches(model: String): Boolean =
        models.any { it.isNotBlank() && it.trim().equals(model.trim(), ignoreCase = true) }

    /** 是否可以真正用于请求。 */
    fun isUsable(): Boolean = key.isNotBlank()
}

/**
 * 一套可保存、可随时切换的大模型接入配置（OpenAI 兼容）。
 *
 * apiKey 只写 EncryptedSharedPreferences，不进 DataStore、不进日志。
 */
@Serializable
data class LlmProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val note: String = "",

    /**
     * 这个 provider **供应的模型列表**（可选，手填或从 /models 拉来）。
     *
     * 一个中转站常常挂着几十个模型，用户想在 App 里直接切着用。
     * 没有这个列表的话，用户只能为每个模型各建一套配置——
     * 那正是「一个 URL 只能配一个模型」的痛点。
     *
     * 留空表示「只有主模型」，行为与旧版完全一致。
     */
    val models: List<String> = emptyList(),

    /**
     * 附加的 key 池（同一个 baseUrl 下的其他 key）。
     *
     * 存量数据反序列化时没有这个字段，必须给默认值 `emptyList()`，
     * 否则老用户的配置直接崩掉。
     */
    val extraKeys: List<LlmKeyEntry> = emptyList(),

    /**
     * 这个模型**能不能直接看图**。
     *
     * ## 为什么需要它
     *
     * 用户接的大模型不一定是多模态的（DeepSeek-chat、Qwen-plus 这类都看不见图）。
     * 而本 App 已经接了 MinerU——它能把图片里的文字、公式逐字抽出来。
     * 于是「给不识图的模型发图」就有了一条可行路径：
     * **先让 MinerU 读图，再把读出来的文字喂给模型。**
     *
     * ## 默认值为什么是 true
     *
     * 存量的接入配置里没有这个字段，反序列化时若不给默认值会直接崩，
     * 用户的 Key、URL、模型名全部丢失。给 `true` 是**保持原行为**：
     * 旧配置照旧把图片当多模态图片发出去，不会因为升级就突然改走 OCR。
     *
     * 用户可以在设置里按模型单独关掉（不识图的模型关掉即可）。
     */
    val supportsVision: Boolean = true
) {
    fun displayName(): String = name.ifBlank { model.ifBlank { "未命名配置" } }

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 所有可用的 key：主 key 在前，附加 key 在后。
     *
     * 主 key 排第一是刻意的——它承担「兜底」角色，
     * 附加 key 只有在自己绑定了模型时才会被优先选中。
     */
    fun allKeys(): List<LlmKeyEntry> =
        buildList {
            if (apiKey.isNotBlank()) add(LlmKeyEntry(key = apiKey, models = emptyList()))
            addAll(extraKeys.filter { it.key.isNotBlank() })
        }

    /**
     * 按模型名解析出该用的 key。
     *
     * 规则：
     * 1. 附加 key 里凡是**绑定到这个模型**的，优先用（最后一个匹配的优先，
     *    便于用户在设置里往上加新绑定来「覆盖」旧的）；
     * 2. 否则用主 key；
     * 3. 主 key 为空时，退回第一个可用的附加 key。
     *
     * 解析不出来就返回空串——调用方要按「未配置」处理。
     */
    fun resolveKey(model: String): String {
        val matched = extraKeys.filter { it.isUsable() && it.matches(model) }
        if (matched.isNotEmpty()) return matched.last().key
        if (apiKey.isNotBlank()) return apiKey
        return extraKeys.firstOrNull { it.isUsable() }?.key.orEmpty()
    }

    /**
     * 这个配置下**所有**可用的模型名（主模型 + 各 key 绑定的模型）。
     *
     * 设置页用它给出「这个 provider 到底供应哪些模型」的提示。
     */
    fun availableModels(): List<String> {
        val out = LinkedHashSet<String>()
        if (model.isNotBlank()) out += model.trim()
        models.filter { it.isNotBlank() }.forEach { out += it.trim() }
        extraKeys.forEach { entry ->
            entry.models.filter { it.isNotBlank() }.forEach { out += it.trim() }
        }
        return out.toList()
    }

    /**
     * 保存前规范化：只填了根域名（如 https://api.example.com）时自动补 /v1，
     * 已经带路径的（如 https://host/api）保持原样。
     */
    fun normalized(): LlmProfile {
        val raw = baseUrl.trim().trimEnd('/')
        val hasPath = raw.removePrefix("https://").removePrefix("http://").contains('/')
        return copy(baseUrl = if (hasPath) raw else "$raw/v1")
    }

    fun chatCompletionsUrl(): String = baseUrl.trimEnd('/') + "/chat/completions"

    fun modelsUrl(): String = baseUrl.trimEnd('/') + "/models"
}
