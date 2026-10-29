package com.mistakebook.pipeline

/**
 * 输入 Markdown 超长截断（PRD 4.2）：超过 12000 字符时，优先保留含公式 `$` 与
 * 图片 `![](` 的行，其余按原顺序拼接后截断。
 *
 * ## 截断点必须避开数学区（v0.0.69）
 *
 * 头尾各截一半的旧实现会把 `$…$` 公式拦腰切断——交给清洗与渲染层后
 * 产出的是残缺公式（`$` 不配对，KaTeX 直接报错或吞字）。现在头尾两个
 * 截断点都收拢到「行末且不在未闭合数学区」的边界上；找不到安全边界
 * （整段都在数学区）才退回原截断点。
 */
object MarkdownTruncator {

    const val MAX_CHARS = 12000

    data class Result(val text: String, val truncated: Boolean)

    fun truncate(markdown: String, maxChars: Int = MAX_CHARS): Result {
        if (markdown.length <= maxChars) return Result(markdown, false)
        val priority = StringBuilder()
        val rest = StringBuilder()
        markdown.lineSequence().forEach { line ->
            if (line.contains('$') || line.contains("![](")) {
                priority.append(line).append('\n')
            } else {
                rest.append(line).append('\n')
            }
        }
        val combined = priority.toString() + rest.toString()
        val headroom = maxChars / 2
        val text = if (combined.length <= maxChars) {
            combined
        } else {
            val headCut = mathSafeBoundary(combined, headroom)
            val tailCut = mathSafeBoundary(combined, combined.length - (maxChars - headroom))
            combined.take(headCut) + "\n……（内容过长已截断）……\n" + combined.substring(tailCut)
        }
        return Result(text.trimEnd(), true)
    }

    /**
     * 在 [limit] 约束下返回一个「行末且不在未闭合数学区」的安全截断点。
     *
     * `$$` 与 `$` 都按翻转计数（单行内成对的 `$$` 翻两次等于没翻，
     * 与 JsonExtractor.normalizeBreaks 的结论一致：`$$` 必须整体算一次）。
     * 找不到安全边界（limit 之前全在数学区）返回 limit 本身。
     */
    internal fun mathSafeBoundary(text: String, limit: Int): Int {
        val capped = limit.coerceIn(0, text.length)
        var inMath = false
        var boundary = 0
        var pos = 0
        for (line in text.lineSequence()) {
            val lineLen = line.length + 1
            if (pos + lineLen > capped) break
            var dollars = 0
            var i = 0
            while (i < line.length) {
                if (line[i] == '$') {
                    if (i + 1 < line.length && line[i + 1] == '$') i++ // $$ 整体算一对
                    dollars++
                }
                i++
            }
            if (dollars % 2 == 1) inMath = !inMath
            if (!inMath) boundary = pos + lineLen
            pos += lineLen
        }
        return if (boundary == 0) capped else boundary
    }
}
