package com.mistakebook.pipeline

import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.knowledgePoints
import com.mistakebook.data.local.options

/**
 * 变式题（F1 举一反三）的提示词。
 *
 * 输出 JSON 字段名刻意与 [RefinedQuestionDto] 完全一致——
 * 解析端直接复用同一个 DTO，不用为变式另养一套结构。
 * image_refs / error_reason_guess / uncertain 不要求输出，
 * DTO 的默认值会兜住缺字段。
 */
object VariantPrompts {

    fun system(): String = """
        你是变式出题引擎。用户会给你一道原题（题干、选项、答案、解析、知识点），
        请出一道考查相同核心知识点、但题面不同的变式题。

        硬性规则：
        1. 只输出 JSON 本身，不要任何解释、注释、Markdown 代码围栏或思考过程。
        2. 变式题必须与原题考查相同的核心知识点，但题面不能与原题雷同：
           数值、情境、图形描述或设问方式至少有一处实质变化。
        3. 难度与原题相近，difficulty 为 1~5 的整数。
        4. 选择题给 options（label 用大写字母按序，text 为选项内容）；
           原题没有选项就给空数组，变式也保持同题型。
        5. 公式一律用 ${'$'}...${'$'} 的 LaTeX 写法，不要转成纯文本。
        6. answer 必须与题干自洽；analysis 按步骤组织，步骤间用 \n 分隔。
        7. knowledge_points 取 2~5 个。

        输出 JSON 结构（字段名必须完全一致）：
        {
          "subject": "数学|物理|化学|生物|语文|英语|历史|地理|政治|其他",
          "title": "简短标题",
          "knowledge_points": ["..."],
          "stem": "题干",
          "options": [{"label":"A","text":"选项内容"}],
          "answer": "答案",
          "analysis": "解析",
          "difficulty": 3
        }
    """.trimIndent()

    fun user(origin: Question, subjectName: String?): String = buildString {
        appendLine("请基于下面这道原题出一道变式题，并按系统指令输出 JSON：")
        appendLine()
        appendLine("-----原题 BEGIN-----")
        appendLine("学科：${subjectName?.takeIf { it.isNotBlank() } ?: "未标注"}")
        if (origin.title.isNotBlank()) appendLine("标题：${origin.title}")
        appendLine("题干：${origin.stem}")
        val opts = origin.options
        if (opts.isNotEmpty()) {
            appendLine("选项：")
            opts.forEach { appendLine("${it.label}. ${it.text}") }
        }
        appendLine("答案：${origin.answer.ifBlank { "（原题未填答案）" }}")
        if (origin.analysis.isNotBlank()) appendLine("解析：${origin.analysis}")
        append("知识点：${origin.knowledgePoints.joinToString("、").ifBlank { "未标注" }}")
        appendLine()
        appendLine("-----原题 END-----")
    }
}
