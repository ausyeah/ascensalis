package com.mistakebook.data.prefs

import kotlinx.serialization.Serializable

/**
 * OCR 服务类型：
 * - [MINERU]：MinerU 官方云端文档解析（文档/排版级识别、公式提取、长轮询）
 * - [OPENAI_VISION]：OpenAI 兼容的多模态视觉大模型或第三方 OCR 服务（DeepSeek-VL、讯飞星辰 MaaS、Qwen-VL、自建 OCR 端点等）
 */
@Serializable
enum class OcrType(val label: String, val hint: String) {
    MINERU(
        label = "MinerU 官方文档解析",
        hint = "官方文档级识别与公式提取，按页计费，适合试卷与整页排版"
    ),
    OPENAI_VISION(
        label = "视觉大模型 / OpenAI 兼容端点",
        hint = "多模态视觉模型直接转写提取文字与 LaTeX 公式，适配 DeepSeek、讯飞星辰、Qwen-VL、中转站或自建端点"
    )
}

/**
 * 一套可保存、可随时切换的 OCR 接入配置（同 [LlmProfile] 体系）。
 *
 * apiKey 只写 EncryptedSharedPreferences，不进 DataStore、不进日志。
 */
@Serializable
data class OcrProfile(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val type: OcrType = OcrType.OPENAI_VISION,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val note: String = ""
) : KeyedProfile {

    fun displayName(): String =
        name.ifBlank {
            when (type) {
                OcrType.MINERU -> "MinerU"
                OcrType.OPENAI_VISION -> model.ifBlank { "自定义 OCR" }
            }
        }

    fun isConfigured(): Boolean = when (type) {
        OcrType.MINERU -> apiKey.isNotBlank()
        OcrType.OPENAI_VISION -> apiKey.isNotBlank() && model.isNotBlank()
    }

    override fun normalized(): OcrProfile {
        val trimmed = baseUrl.trim().trimEnd('/')
        return copy(
            baseUrl = trimmed,
            apiKey = apiKey.trim(),
            model = model.trim(),
            name = name.trim(),
            note = note.trim()
        )
    }

    /**
     * 智能解析 OCR 目标完整 POST 端点 URL。
     *
     * 自动适配各种用户填法：
     * - 用户直接填完整端点：如 `https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions` 或 `/v1/responses` -> 原样使用；
     * - 用户填带版本号根路径：如 `https://maas-api.cn-huabei-1.xf-yun.com/v2` 或 `/v1` -> 自动追加 `/chat/completions`；
     * - 用户填裸域名：如 `https://api.deepseek.com` -> 自动追加 `/v1/chat/completions`；
     * - 留空时默认指向 `https://api.deepseek.com/v1/chat/completions`。
     */
    fun endpointUrl(): String {
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.isBlank()) return "https://api.deepseek.com/v1/chat/completions"
        if (raw.endsWith("/chat/completions") || raw.endsWith("/responses")) {
            return raw
        }
        if (raw.endsWith("/v1") || raw.endsWith("/v2")) {
            return "$raw/chat/completions"
        }
        val hasPath = raw.removePrefix("https://").removePrefix("http://").contains('/')
        return if (hasPath) {
            "$raw/chat/completions"
        } else {
            "$raw/v1/chat/completions"
        }
    }

    companion object {
        const val DEFAULT_MINERU_ID = "builtin-mineru"
        const val DEFAULT_DEEPSEEK_ID = "builtin-deepseek-ocr"

        fun defaultMineru(key: String = ""): OcrProfile = OcrProfile(
            id = DEFAULT_MINERU_ID,
            name = "MinerU 官方文档解析",
            type = OcrType.MINERU,
            baseUrl = "https://mineru.net/api/v4",
            apiKey = key,
            model = "vlm",
            note = "官方文档级解析服务，支持公式与结构化排版"
        )

        fun defaultDeepSeek(
            key: String = "",
            baseUrl: String = "https://api.deepseek.com",
            model: String = "deepseek-chat"
        ): OcrProfile = OcrProfile(
            id = DEFAULT_DEEPSEEK_ID,
            name = "DeepSeek OCR",
            type = OcrType.OPENAI_VISION,
            baseUrl = baseUrl,
            apiKey = key,
            model = model,
            note = "通过视觉大模型将手写或试卷图片转写为 Markdown 与 LaTeX"
        )
    }
}
