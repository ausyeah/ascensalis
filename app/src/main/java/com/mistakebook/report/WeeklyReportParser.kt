package com.mistakebook.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 周报输出解析（F2）：从模型回复里抠出 Markdown 正文。
 *
 * 三级尝试：标准 JSON → 去围栏/截花括号后的 JSON → 裸 Markdown 兜底
 * （模型无视 JSON 指令直接吐 Markdown 时，只要结构像报告就收下——
 * 用户要的是报告，不是格式正确性；这与变式题解析器的差别在于
 * 周报没有后续结构化消费，裸文本可用）。
 */
object WeeklyReportParser {

    @Serializable
    private data class ReportDto(val report_markdown: String = "")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 成功的最低门槛：正文够长且确实分了节，防止把报错文案当报告存库。 */
    private const val MIN_LENGTH = 60

    fun parse(raw: String): String? {
        if (raw.isBlank()) return null
        extractJson(raw)?.let { return clean(it) }
        // 裸 Markdown 兜底：必须包含二级标题且达到最低长度
        if (raw.contains("\n## ") && raw.trim().length >= MIN_LENGTH) {
            return clean(stripFences(raw))
        }
        return null
    }

    private fun extractJson(raw: String): String? {
        val candidates = listOf(raw, stripFences(raw), sliceBraces(raw), sliceBraces(stripFences(raw)))
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val markdown = runCatching { json.decodeFromString<ReportDto>(candidate).report_markdown }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (markdown != null) return markdown
        }
        return null
    }

    private fun clean(text: String): String? =
        text.trim().takeIf { it.length >= MIN_LENGTH }

    private fun stripFences(text: String): String =
        text.trim()
            .replace(Regex("""^```[a-zA-Z]*\s*"""), "")
            .replace(Regex("""\s*```$"""), "")
            .trim()

    private fun sliceBraces(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }
}
