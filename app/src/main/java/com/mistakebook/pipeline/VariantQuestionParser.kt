package com.mistakebook.pipeline

import kotlinx.serialization.json.Json

/**
 * 变式题（F1 举一反三）输出的容错解析。
 *
 * 与 [JsonExtractor] 同一哲学：模型输出带围栏、带废话是常态，
 * 解析要按「原文 → 去围栏 → 截花括号」的顺序逐级尝试。
 * 但变式题比整理流水线简单得多——没有 OCR 降级回退的语义，
 * 解析不出来就返回 null，由调用方给一句「请重试」即可。
 *
 * 单独成 object 是为了能脱网单测（见 VariantQuestionParserTest），
 * 这条纪律与 matchItem / MineruStallTracker 一致。
 */
object VariantQuestionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 解析模型输出为 [RefinedQuestionDto]。
     *
     * @return 题干非空才算成功；difficulty 钳位到 1~5；选项缺 label 按序补 A/B/C。
     *   全部尝试失败返回 null。
     */
    fun parse(raw: String): RefinedQuestionDto? {
        if (raw.isBlank()) return null
        val candidates = listOf(raw, stripFences(raw), sliceBraces(raw), sliceBraces(stripFences(raw)))
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val dto = runCatching { json.decodeFromString<RefinedQuestionDto>(candidate) }
                .getOrNull()
                ?: continue
            if (dto.stem.isNotBlank()) return normalize(dto)
        }
        return null
    }

    /** 去掉 ```json / ``` 围栏（模型无视「不要围栏」的指令是常态，不是例外）。 */
    private fun stripFences(text: String): String =
        text.trim()
            .replace(Regex("""^```[a-zA-Z]*\s*"""), "")
            .replace(Regex("""\s*```$"""), "")
            .trim()

    /** 截取第一个 `{` 到最后一个 `}`——围栏外混入说明文字时靠它。 */
    private fun sliceBraces(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }

    private fun normalize(dto: RefinedQuestionDto): RefinedQuestionDto = dto.copy(
        options = dto.options.mapIndexed { index, option ->
            if (option.label.isBlank()) option.copy(label = com.mistakebook.domain.labelFor(index))
            else option
        },
        difficulty = dto.difficulty.coerceIn(1, 5)
    )
}
