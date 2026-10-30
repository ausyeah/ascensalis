package com.mistakebook.data.prefs

/**
 * OCR（图片转文字）服务供应商（v0.0.78）。
 *
 * 用户反馈：「把 MinerU 配置改成 OCR 配置，需要自己填写相关配置，
 * 希望能适配 MinerU 和 DeepSeekOCR」。
 *
 * 两者的能力边界不同，选型时要让用户看得见：
 * - [MINERU]：文档级解析（PDF/图片 → Markdown + 题目配图），
 *   错题识别流水线走它；
 * - [DEEPSEEK]：OpenAI 兼容的视觉模型（DeepSeek-OCR / VL 系），
 *   只管「图片 → 文字」，不提取配图。用作对话识图、扫描件 PDF 的读字。
 */
enum class OcrProvider(val label: String, val hint: String) {
    MINERU(
        "MinerU",
        "文档级解析：支持 PDF 与图片，能同时提取题目配图。错题识别流水线用它。"
    ),
    DEEPSEEK(
        "DeepSeek OCR",
        "视觉模型转写：图片 → 文字/公式。不提取配图；错题识别仍建议用 MinerU。"
    );

    companion object {
        val DEFAULT = MINERU

        fun fromKey(key: String?): OcrProvider =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}
