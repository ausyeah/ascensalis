package com.mistakebook.math

/**
 * 长公式的**主动换行**：把一条超长 LaTeX 拆成若干可独立渲染的短片段。
 *
 * ## 为什么需要它
 *
 * 块级公式（`$$…$$`）此前整条交给 KaTeX 渲染成一张位图，再按
 * [MathLayout.fit] 等比收缩到屏幕宽。公式一长，等比收缩就把整条压到
 * 指甲盖大——用户反馈「渲染出来很小」「长公式渲染不出来」。
 *
 * 提示词侧已要求模型别写超长公式（见 `ChatPrompts.SYSTEM`），但模型不一定听，
 * 所以渲染侧必须有兜底：**超过宽度预算就主动换行**，一行一个短片段，
 * 每个片段都能按目标字号完整渲染，而不是把一条长公式压成一行蚂蚁。
 *
 * ## 拆分规则
 *
 * 1. 本来就短（不超过 [maxChars]）→ 原样返回，一个字符都不动；
 * 2. 整段是多行环境（`aligned`/`cases`/`matrix`…）→ 按顶层 `\\` 拆行，
 *    每行单独包回同一个环境渲染。环境体内的 `&` 对齐点、嵌套矩阵的
 *    内部 `\\` 都不会被误拆；
 * 3. 其余（单行超长公式）→ 在**顶层**二元运算符/关系符处贪心断行，
 *    运算符留在行尾。括号内的内容绝不从中间拆开——
 *    `\left(\frac{a}{b}\right)` 要么整块留在一行，要么整体换到下一行。
 *
 * ## 刻意不依赖 Android
 *
 * 全是纯字符串计算，直接在 JVM 单元测试里断言拆分结果。
 */
object MathWrap {

    /**
     * 小于这个长度不值得拆：拆出来的行比一行原文还难读，
     * 而 [MathLayout.fit] 的宽度收缩在这个量级上完全可以接受。
     */
    const val MIN_SPLIT_CHARS = 30

    /** 整段就是一个 `\begin{env}…\end{env}`（前后允许空白）。 */
    private val WHOLE_ENV = Regex("""^\s*\\begin\{([a-zA-Z*]+)\}([\s\S]*)\\end\{([a-zA-Z*]+)\}\s*$""")

    /**
     * 会按 `\\` 拆行的多行环境。
     *
     * 白名单制：不在这里的环境（如 `matrix` 的嵌套、`tabular`）一律按普通公式走，
     * 宁可让 [MathLayout.fit] 收缩，也不冒拆坏结构的风险。
     */
    private val MULTILINE_ENVS = setOf(
        "aligned", "align", "alignat", "flalign", "eqnarray", "split", "gather", "gathered",
        "cases", "dcases", "rcases", "matrix", "pmatrix", "bmatrix", "Bmatrix",
        "vmatrix", "Vmatrix", "smallmatrix", "array"
    )

    /**
     * 单字符顶层断点（二元运算符 / 关系符）。
     *
     * 不含 `^` `_`（上下标几乎总在组内，组内深度 > 0 也拆不到）、
     * 不含 `,` `;`（拆了反而更难读）、不含 `/`（分式由 `\frac` 表达，
     * 裸 `/` 多出现在文本里）。
     */
    private val BREAK_CHARS = charArrayOf('=', '+', '-', '<', '>')

    /**
     * 多字符顶层断点（LaTeX 关系命令，按 token 整体切）。
     *
     * 长的排前面，保证 `\rightarrow` 不会被 `\to` 抢先匹配。
     */
    private val BREAK_COMMANDS = arrayOf(
        "rightarrow", "longrightarrow", "leftarrow", "longleftarrow", "Rightarrow", "Longrightarrow",
        "Leftarrow", "Longleftarrow", "leftrightarrow", "Leftrightarrow", "Longleftrightarrow",
        "hookrightarrow", "hookleftarrow", "mapsto", "longmapsto",
        "neq", "ne", "leq", "le", "geq", "ge", "equiv", "approx", "simeq", "cong",
        "sim", "propto", "doteq", "ll", "gg", "prec", "succ", "preceq", "succeq",
        "subset", "supset", "subseteq", "supseteq", "in", "ni", "notin", "cup", "cap",
        "to", "gets", "implies", "iff", "land", "lor", "cdot", "times", "div", "pm", "mp"
    )

    /** 断点 token 匹配后允许的**最大**回吐（命令名长度 + 反斜杠），防止切点跑出窗口太远。 */
    private const val MAX_OVERSHOOT = 14

    /**
     * 把 [latex] 拆成若干可独立渲染的短片段。
     *
     * @param maxChars 单行宽度预算（字符数）。由调用方按屏幕宽与目标字号估算，
     *   见 `RichText` 的 `estimateMaxMathChars`。小于 [MIN_SPLIT_CHARS] 时视作不拆。
     * @return 至少一个元素；输入空白时返回 `listOf("")`。片段均经过 trim，
     *   且**不含** `\\` 行分隔符（环境体已按行拆开）。
     */
    fun splitForDisplay(latex: String, maxChars: Int): List<String> {
        val trimmed = latex.trim()
        if (trimmed.isEmpty()) return listOf(trimmed)
        if (maxChars < MIN_SPLIT_CHARS || trimmed.length <= maxChars) return listOf(trimmed)

        val envMatch = WHOLE_ENV.find(trimmed)
        if (envMatch != null) {
            val begin = envMatch.groupValues[1]
            val end = envMatch.groupValues[3]
            if (begin == end && begin in MULTILINE_ENVS) {
                val rows = splitEnvRows(envMatch.groupValues[2])
                if (rows.size > 1) {
                    val out = mutableListOf<String>()
                    rows.forEach { row ->
                        val body = row.trim()
                        if (body.isEmpty()) return@forEach
                        // 行内仍超长也不按 `&` 拆——对齐点是矩阵/联立方程
                        // 的列语义，拆开每列单独渲染等于把表拆散。
                        // 由 MathLayout.fit 对这一行收缩，保结构优先于保字号。
                        out += wrapEnv(begin, body)
                    }
                    if (out.isNotEmpty()) return out
                }
            }
        }
        return greedySplit(trimmed, maxChars)
    }

    /** `\begin{env}` + 行体 + `\end{env}`。 */
    private fun wrapEnv(env: String, body: String): String =
        "\\begin{$env}$body\\end{$env}"

    /**
     * 按**顶层** `\\` 拆环境体。
     *
     * 深度跟踪把 `\begin{pmatrix}…\end{pmatrix}` 这类嵌套整体括住，
     * 嵌套矩阵内部的 `\\` 属于行内结构，不拆。
     */
    private fun splitEnvRows(body: String): List<String> {
        val rows = mutableListOf<String>()
        var depth = 0
        var lineStart = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                if (body[i + 1] == '\\') {
                    if (depth == 0) {
                        rows += body.substring(lineStart, i)
                        i += 2
                        lineStart = i
                        continue
                    }
                    i += 2
                    continue
                }
                // `\begin{…}` / `\end{…}` 调整环境深度
                if (body.startsWith("\\begin{", i)) depth++
                if (body.startsWith("\\end{", i)) depth = (depth - 1).coerceAtLeast(0)
                i += 2
                continue
            }
            i++
        }
        if (lineStart < body.length) rows += body.substring(lineStart)
        return rows
    }

    /**
     * 贪心断行：窗口内找**最后一个**顶层断点切开，运算符留在行尾。
     *
     * 找不到断点（一整条没有顶层运算符的长公式，如超长的连乘）就硬切窗口。
     */
    private fun greedySplit(latex: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (latex.length - start > maxChars) {
            val windowEnd = start + maxChars
            val cut = findBreak(latex, start, windowEnd) ?: windowEnd
            val line = latex.substring(start, cut).trim()
            if (line.isNotEmpty()) out += line
            start = cut
        }
        if (start < latex.length) {
            val tail = latex.substring(start).trim()
            if (tail.isNotEmpty()) out += tail
        }
        return out.ifEmpty { listOf(latex.trim()) }
    }

    /**
     * 在 `[from, windowEnd)` 里找最后一个顶层断点的**切点**（断点之后的位置）。
     *
     * 切点允许比 `windowEnd` 超出最多 [MAX_OVERSHOOT]（一个完整关系命令的长度），
     * 否则宁可回到硬切——为了追一个断点把窗口撑出去太多，下一行还是会超长。
     *
     * @return 切点下标；窗口内没有可用断点返回 null。
     */
    private fun findBreak(latex: String, from: Int, windowEnd: Int): Int? {
        var depth = 0
        var best: Int? = null
        var i = from
        while (i < latex.length) {
            val c = latex[i]
            if (c == '\\' && i + 1 < latex.length) {
                // `\\`（行分隔符）本身就是断点
                if (latex[i + 1] == '\\') {
                    if (depth == 0 && i + 2 <= windowEnd + MAX_OVERSHOOT && i >= from) {
                        best = i + 2
                    }
                    i += 2
                    continue
                }
                if (depth == 0 && i < windowEnd) {
                    val cmdEnd = matchCommand(latex, i)
                    if (cmdEnd != null) best = cmdEnd
                }
                i += 2
                continue
            }
            when (c) {
                '{', '(', '[' -> depth++
                '}', ')', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (depth == 0 && c in BREAK_CHARS && i < windowEnd) {
                best = i + 1
            }
            i++
        }
        if (best != null && best > windowEnd + MAX_OVERSHOOT) return null
        if (best != null && best <= from + 4) return null // 切出太碎的行不如不切
        return best
    }

    /**
     * 若 [i] 处是一个顶层关系命令（`\to`、`\le`…），返回命令结束下标；否则 null。
     *
     * 命令后的**前导空格不吃**——运算符留在行尾时带一个尾巴空格无所谓，
     * 切片处会 trim。但命令字母表之外的字符（如 `\frac` 的 `\f`）直接返回 null。
     */
    private fun matchCommand(latex: String, i: Int): Int? {
        var j = i + 1
        while (j < latex.length && latex[j].isLetter()) j++
        if (j == i + 1) return null // `\ `、`\{` 之类，不是命令
        val name = latex.substring(i + 1, j)
        val matched = BREAK_COMMANDS.firstOrNull { it == name } ?: return null
        val end = i + 1 + matched.length
        return if (end <= latex.length) end else null
    }
}
