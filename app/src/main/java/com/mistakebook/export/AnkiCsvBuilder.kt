package com.mistakebook.export

/**
 * Anki CSV 导出（F7）的纯构建器。
 *
 * 目标格式：Anki「文件 → 导入」直接可用的三列 CSV（Front,Back,Tags）。
 * - 内容按 HTML 渲染（Anki 默认）：换行写 `<br>`；
 * - LaTeX `$...$` 原样保留——Anki 内置 MathJax，公式直接可渲；
 * - 标签不允许空格：空白折叠成下划线。
 */
object AnkiCsvBuilder {

    data class CardRow(val front: String, val back: String, val tags: List<String>)

    fun build(rows: List<CardRow>): String = buildString {
        appendLine("Front,Back,Tags")
        rows.forEach { row ->
            append(escape(field(row.front)))
            append(',')
            append(escape(field(row.back)))
            append(',')
            appendLine(escape(row.tags.joinToString(" ") { ankiTag(it) }))
        }
    }

    /** 字段级规整：真实换行转 <br>（HTML 模式下才是换行），首尾空白去掉。 */
    internal fun field(text: String): String = text
        .replace("\r\n", "\n")
        .replace("\n", "<br>")
        .trim()

    /**
     * CSV 转义：含逗号/引号/换行的字段包引号，内部引号翻倍。
     * （换行在 field() 之后理论上只剩 <br>，但转义器不该依赖上游约定，
     * 防御性保留换行判断。）
     */
    internal fun escape(text: String): String =
        if (text.contains(',') || text.contains('"') || text.contains('\n')) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else text

    /** Anki 标签：空白 → 下划线；空标签丢弃由调用方负责。 */
    internal fun ankiTag(tag: String): String =
        tag.trim().replace(Regex("\\s+"), "_")

    /**
     * 题目 → 卡片行。图片不导（Anki 媒体库走另一套流程，文件路径对它无意义），
     * 纯文本 + LaTeX 已覆盖复习场景。
     */
    fun cardRow(
        stem: String,
        options: List<Pair<String, String>>, // label to text
        answer: String,
        analysis: String,
        tags: List<String>
    ): CardRow {
        val front = buildString {
            append(stem.trim())
            if (options.isNotEmpty()) {
                append("<br><br>")
                options.forEachIndexed { index, (label, text) ->
                    if (index > 0) append("<br>")
                    append("$label. $text")
                }
            }
        }
        val back = buildString {
            append(answer.trim())
            if (analysis.isNotBlank()) {
                append("<br><br>----<br>")
                append(analysis.trim())
            }
        }
        return CardRow(front, back, tags.filter { it.isNotBlank() })
    }
}
