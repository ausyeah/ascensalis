package com.mistakebook.data.prefs

import kotlinx.serialization.Serializable

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
