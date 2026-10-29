package com.mistakebook.data.prefs

import kotlinx.serialization.Serializable

/**
 * 一套生图 API 渠道配置。
 *
 * ## 为什么仿 [LlmProfile] 再要一套
 *
 * 生图 API 的形态和对话 API 差别很大：没有 `/chat/completions` 这个统一
 * 端点，请求体各家都不一样（SenseNova 的 `n`/`size`、通义万相的
 * `xxx`、DALL·E 的 `response_format`……）。硬编码任何一家的字段，
 * 用户就得为另一家改代码。所以走「用户可配」路线：
 *
 * - [baseUrl] / [apiKey] / [model] 三家通用的部分让用户填；
 * - [requestBodyTemplate] 让用户按 API 文档贴一份请求体 JSON 模板，
 *   代码只替换 `{prompt}` `{width}` `{height}` 三个占位符；
 * - 留空时用内置的 SenseNova 风格默认体（项目最早对接的一家）。
 *
 * apiKey 只写 EncryptedSharedPreferences，不进 DataStore、不进日志。
 */
@Serializable
data class ImageProfile(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    /** API 根地址，如 https://api.sensenova.cn/v1。 */
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 模型/引擎名（各 API 差异大，由用户填）。 */
    val model: String = "",
    /**
     * 可选：请求体自定义 JSON 模板，占位符 {prompt} {width} {height}
     * 由代码替换。留空走内置默认体。
     */
    val requestBodyTemplate: String = ""
) : KeyedProfile {
    fun displayName(): String =
        name.ifBlank { model.ifBlank { "未命名渠道" } }

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 归一化：只填了根域名时补 /v1，已带路径的原样保留（同 [LlmProfile.normalized]）。 */
    override fun normalized(): ImageProfile {
        val raw = baseUrl.trim().trimEnd('/')
        val hasPath = raw.removePrefix("https://").removePrefix("http://").contains('/')
        return copy(baseUrl = if (hasPath) raw else "$raw/v1")
    }

    /**
     * 生图端点。默认 `<baseUrl>/images/generations`（OpenAI DALL·E 风格，
     * SenseNova 视觉模型也是这个路径）。
     */
    fun generateUrl(): String = baseUrl.trimEnd('/') + "/images/generations"
}
