package com.mistakebook.pipeline

import com.mistakebook.domain.ErrorReason
import com.mistakebook.domain.Option
import com.mistakebook.domain.QuestionDraft
import com.mistakebook.domain.labelFor
import kotlinx.serialization.json.Json

// 一次 LLM 整理的结果：若干道题草稿 + 是否降级。
data class RefinedOutcome(
    val drafts: List<QuestionDraft>,
    val degraded: Boolean = false,
    val degradedReason: String = "",
    val truncatedInput: Boolean = false
)

/**
 * JSON 容错提取（PRD 5.3）：
 * 1) 直接解析；2) 去围栏；3) 第一个 { 到最后一个 }；4) 降级为原始 Markdown；
 * 5) 字段级容错（缺字段用默认值，label 缺省补 A/B/C，difficulty 钳到 1..5）。
 */
object JsonExtractor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun extract(raw: String, markdown: String, imagePath: String): RefinedOutcome {
        val items = parseItems(raw)
        if (items != null && items.isNotEmpty()) {
            return RefinedOutcome(
                drafts = items.map { toDraft(it, markdown, imagePath, degraded = false) }
            )
        }
        // 单题结构必须真的含题目字段：{"items":...} 之类会被 ignoreUnknownKeys 解析成空对象，
        // 早期因此把多题结果误判成一道空题。
        val single = parseSingle(raw)
        if (single != null && single.hasContent()) {
            return RefinedOutcome(
                drafts = listOf(toDraft(single, markdown, imagePath, degraded = false))
            )
        }
        return RefinedOutcome(
            drafts = listOf(
                QuestionDraft(
                    imagePath = imagePath,
                    mineruMarkdown = markdown,
                    subjectName = "",
                    stem = markdown.trim(),
                    options = emptyList(),
                    answer = "",
                    analysis = "",
                    knowledgePoints = emptyList(),
                    errorReason = ErrorReason.OTHER,
                    difficulty = 3,
                    uncertain = listOf("AI 整理失败，已保留原始识别文本")
                )
            ),
            degraded = true
        )
    }

    private fun parseSingle(raw: String): RefinedQuestionDto? {
        val text = raw.trim()
        val candidates = listOf(
            text,
            stripFence(text),
            substringBetweenBraces(text)
        )
        candidates.forEach { candidate ->
            if (candidate.isBlank()) return@forEach
            decodeSingle(candidate)?.let { return it }
        }
        return null
    }

    private fun RefinedQuestionDto.hasContent(): Boolean =
        stem.isNotBlank() || options.isNotEmpty() || answer.isNotBlank() || analysis.isNotBlank()

    private fun parseItems(raw: String): List<RefinedQuestionDto>? {
        val text = raw.trim()
        val candidates = listOf(text, stripFence(text), substringBetweenBraces(text))
        candidates.forEach { candidate ->
            if (candidate.isBlank()) return@forEach
            decodeItems(candidate)?.let { return it }
        }
        return null
    }

    /**
     * 所有 JSON 解析的**唯一入口**。
     *
     * 解析前先跑 [LatexEscapes.protectLatexEscapes]：模型在 JSON 字符串里写
     * `\to` `\right` `\neq` 时，这些首字母恰好都是合法 JSON 转义，会被静默
     * 变成控制字符——解析**不报错**，公式却已经坏了。
     * 见 [LatexEscapes] 的详细说明。
     */
    private fun prepare(raw: String): String = LatexEscapes.protectLatexEscapes(raw)

    private fun decodeSingle(text: String): RefinedQuestionDto? {
        val prepared = prepare(text)
        return runCatching { json.decodeFromString<RefinedQuestionDto>(prepared) }.getOrNull()
            ?: runCatching { json.decodeFromString<RefinedQuestionDto>(repairJson(prepared)) }.getOrNull()
    }

    private fun decodeItems(text: String): List<RefinedQuestionDto>? {
        val prepared = prepare(text)
        return runCatching { json.decodeFromString<RefinedItemsDto>(prepared).items }.getOrNull()
            ?: runCatching { json.decodeFromString<List<RefinedQuestionDto>>(prepared) }.getOrNull()
            ?: runCatching { json.decodeFromString<RefinedItemsDto>(repairJson(prepared)).items }.getOrNull()
            ?: runCatching { json.decodeFromString<List<RefinedQuestionDto>>(repairJson(prepared)) }.getOrNull()
            ?: decodeByBraceScan(prepared)
            ?: decodeByBraceScan(repairJson(prepared))
    }

    /**
     * 括号配平扫描，逐个解出最外层的 `{...}`。
     *
     * 覆盖几种模型常见但标准解析器认不出的输出：
     * - `{"1":{题目},"2":{题目}}`（用题号当 key）
     * - `{题目}{题目}`（多个对象直接并列，没包数组）
     * - 前面带一句「好的，以下是整理结果：」再跟 JSON
     * 只要每个对象能解成一道题就收下，解不出内容的丢掉。
     */
    private fun decodeByBraceScan(text: String): List<RefinedQuestionDto>? {
        val found = mutableListOf<RefinedQuestionDto>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, ch ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }

                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val chunk = text.substring(start, index + 1)
                            decodeSingle(chunk)
                                ?.takeIf { it.hasContent() }
                                ?.let { found.add(it) }
                            start = -1
                        }
                    }
                }
            }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /**
     * 轻量 JSON 修复。
     *
     * 真实事故：模型输出的 JSON 经常只差一两个字符就没法解析——尾随逗号、
     * ```json 围栏没剥干净、单引号、字符串里未转义的换行。
     * 这些情况结构其实是对的，直接判失败太浪费，擦一下就能救回来。
     * 只做「确定不改变语义」的修补，不做猜测性补全。
     */
    /**
     * 委托 [JsonRepair]：那里集中了全部修复步骤并可单测。
     *
     * 相对旧实现多救回两类高频失败：字符串里的裸换行（模型写多行题干
     * 直接回车）和**输出被截断**（末尾缺闭合引号/括号）。
     */
    private fun repairJson(text: String): String = JsonRepair.repair(text)

    private fun stripFence(text: String): String {
        val withoutHead = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        return withoutHead.removeSuffix("```").trim()
    }

    private fun substringBetweenBraces(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        return text.substring(start, end + 1)
    }

    private fun toDraft(
        dto: RefinedQuestionDto,
        markdown: String,
        imagePath: String,
        degraded: Boolean
    ): QuestionDraft {
        val options = dto.options.mapIndexed { index, option ->
            Option(
                label = option.label.ifBlank { labelFor(index) },
                text = normalizeBreaks(option.text)
            )
        }.filter { it.text.isNotBlank() }
        return QuestionDraft(
            imagePath = imagePath,
            mineruMarkdown = markdown,
            subjectName = dto.subject.trim(),
            title = dto.title.trim(),
            stem = normalizeBreaks(dto.stem).trim()
                .ifEmpty { if (degraded) markdown.trim() else "" },
            options = options,
            answer = normalizeBreaks(dto.answer).trim(),
            analysis = normalizeBreaks(dto.analysis).trim(),
            knowledgePoints = dto.knowledge_points.map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("未标注知识点") },
            errorReason = mapReason(dto.error_reason_guess),
            difficulty = dto.difficulty.takeIf { it in 1..5 } ?: 3,
            uncertain = dto.uncertain.map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    /**
     * 把模型输出的**字面量** `\n` 还原成真换行，并修复漏网的 LaTeX 控制字符。
     *
     * ## 这个函数改过两次，两次都是因为它自己
     *
     * 第一版无条件处理 `\n` `\t` `\r`。KDoc 里明明写着
     * 「那会把 LaTeX 里的 \nabla、\neq 之类命令毁掉」——然后代码正是这么干的。
     * 结果是 `\to` 变制表符、`\right` 被整段删掉，真机上表现为公式里冒出
     * `ight]`、`x∈0` 这种东西。
     *
     * 现在只做两件事，且都限定范围：
     * 1. `$...$` 数学区**之外**的字面 `\n` 还原成真换行
     *    （数学区里的 `\n` 可能是 `\neq` `\nabla` `\nu` `\ne` `\not`，绝不能动）
     * 2. 数学区里残留的控制字符还原成对应 LaTeX 命令
     *    （JSON 解析本该处理干净，这里兜底，见 [LatexEscapes.restoreLatexControlChars]）
     *
     * 制表符与回车**完全不再单独处理**：它们在 LaTeX 里没有意义，
     * 而 `\t` `\r` 是极常见的命令首字母，一碰就坏。
     */
    private fun normalizeBreaks(raw: String): String {
        val restored = LatexEscapes.repairForDisplay(raw)
        if (!restored.contains("\\n")) return restored
        val out = StringBuilder(restored.length)
        var i = 0
        var inMath = false
        while (i < restored.length) {
            val ch = restored[i]
            if (ch == '$') {
                inMath = !inMath
                out.append(ch)
                i++
                continue
            }
            if (ch == '\\' && i + 1 < restored.length && restored[i + 1] == 'n' && !inMath) {
                out.append('\n')
                i += 2
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun mapReason(raw: String): ErrorReason = when (raw.trim()) {
        "概念不清" -> ErrorReason.CONCEPT
        "计算失误" -> ErrorReason.CALCULATION
        "审题错误" -> ErrorReason.READING
        "思路不会" -> ErrorReason.NO_IDEA
        "粗心遗漏" -> ErrorReason.CARELESS
        else -> ErrorReason.OTHER
    }
}