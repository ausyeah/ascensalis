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
                    if (out.isNotEmpty()) return out.map { balanceDelimiters(it) }
                }
            }
        }
        return greedySplit(trimmed, maxChars)
    }

    /**
     * 让每个片段的 `\left` / `\right` **自洽**。
     *
     * ## 为什么必须做这一步（v0.0.53 真机截图的根因）
     *
     * 拆行把一条公式切成几段后，`\left\{` 与 `\right\}` 可能落在**不同片段**里。
     * KaTeX 对配对定界符的要求是「同一个表达式内必须成对」——
     * 缺 `\right` 报 `Expected '\right', got 'EOF'`，多 `\right` 报
     * `Expected 'EOF', got '\right'`，**两种都让整段公式渲染失败**，
     * 界面上退回显示 LaTeX 源码（用户截图：公式整块以源码形式出现）。
     *
     * 举例（真机原文）：
     * ```
     * \left\{(r,\theta) \mid 0 \leq r \leq \sec\theta, 0 \leq \theta \leq \dfrac{\pi}{4}\right\}
     * ```
     * 按 `\leq` 断行后得到三段：
     * ```
     * \left\{(r,\theta) \mid 0 \leq r \leq      ← 缺 \right
     * \sec\theta, 0 \leq \theta \leq
     * \dfrac{\pi}{4}\right\}                     ← 多 \right
     * ```
     * 第一段与第三段双双报错 → 整条公式退化。
     *
     * 修法：把落单的 `\left` / `\right` **降级成普通定界符字面量**
     * （`\left\{` → `\{`、`\right\}` → `\}`）。
     * 视觉上括号还在，只是不再要求配对，KaTeX 就不再报错。
     */
    internal fun balanceDelimiters(segment: String): String {
        if (segment.isEmpty()) return segment
        val badLeft = unmatchedLeftPositions(segment)
        val badRight = unmatchedRightPositions(segment)
        if (badLeft.isEmpty() && badRight.isEmpty()) return segment

        // 落单的 `\left` / `\right` 一律降级成普通定界符字面量：
        // `\left\{` → `\{`、`\right\}` → `\}`。
        // 视觉上括号还在，只是不再要求配对，KaTeX 就不再报错。
        //
        // 注意**不能只比数量**：出现次数相等也可能配对错位
        // （如 `\left\{ a \right\} b \right\}` —— 左右各一个，但第二个
        // `\right` 落单），所以必须按出现顺序做栈式配对。
        val drop = HashSet<Int>()
        badLeft.forEach { idx ->
            drop += idx
            // `\left.` 是隐形定界符，连后面的点一起删
            if (segment.startsWith("\\left.", idx)) drop += (idx + 5)
        }
        badRight.forEach { idx ->
            drop += idx
            if (segment.startsWith("\\right.", idx)) drop += (idx + 6)
        }
        val sb = StringBuilder(segment.length)
        segment.forEachIndexed { i, c ->
            if (i !in drop) sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 找出**没有配对 `\right`** 的 `\left` 起始下标。
     */
    private fun unmatchedLeftPositions(segment: String): List<Int> =
        scanDelimiters(segment).unmatchedLeft

    /**
     * 找出**没有配对 `\left`** 的 `\right` 起始下标。
     */
    private fun unmatchedRightPositions(segment: String): List<Int> =
        scanDelimiters(segment).unmatchedRight

    /** 扫描结果：落单的 `\left` / `\right` 起始下标。 */
    private data class DelimScan(
        val unmatchedLeft: List<Int>,
        val unmatchedRight: List<Int>
    )

    /**
     * 按出现顺序做**栈式配对**，找出所有落单的 `\left` / `\right`。
     *
     * 规则与 LaTeX 一致：嵌套的 `\left` 压栈，`\right` 弹出最近的一个
     * 未匹配的 `\left`；若栈空则该 `\right` 落单；扫完后栈里剩下的
     * `\left` 也落单。
     *
     * 必须真正配对而不是比数量——`\left\{ a \right\} b \right\}` 左右
     * 各出现一次，但第二个 `\right` 落单，只比数量会漏掉它。
     */
    private fun scanDelimiters(segment: String): DelimScan {
        val stack = ArrayDeque<Int>()
        val unmatchedRight = mutableListOf<Int>()
        var i = 0
        while (i < segment.length) {
            when {
                segment.startsWith("\\left", i) && !isCommandLetter(segment, i + 5) -> {
                    stack.addLast(i); i += 5
                }
                segment.startsWith("\\right", i) && !isCommandLetter(segment, i + 6) -> {
                    if (stack.isNotEmpty()) stack.removeLast() else unmatchedRight += i
                    i += 6
                }
                else -> i++
            }
        }
        return DelimScan(stack.toList(), unmatchedRight)
    }

    /** 位置 [i] 处的字符是否是字母（用于判断 `\leftx` 是不是命令 `\left`）。 */
    private fun isCommandLetter(s: String, i: Int): Boolean =
        i < s.length && s[i].isLetter()

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
     * 找不到顶层断点（一整条没有顶层运算符的长公式，如超长的连乘）时，
     * 退而求其次在窗口内**最后一个命令边界/空白处**切开——绝不在命令中间硬切：
     * `\cos` 硬切成 `\c` + `os` 后，`\c` 不是 KaTeX 命令，整段降级成乱码文本，
     * 用户看到的就是「公式右半边凭空消失/变成怪字」（v0.0.51 用例 A）。
     */
    private fun greedySplit(latex: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (latex.length - start > maxChars) {
            val windowEnd = start + maxChars
            val cut = findBreak(latex, start, windowEnd)
                ?: findSoftBreak(latex, start, windowEnd)
                ?: windowEnd
            val line = latex.substring(start, cut).trim()
            if (line.isNotEmpty()) out += line
            start = cut
        }
        if (start < latex.length) {
            val tail = latex.substring(start).trim()
            if (tail.isNotEmpty()) out += tail
        }
        if (out.isEmpty()) return listOf(latex.trim())
        // 每段都要 `\left` / `\right` 自洽，否则 KaTeX 直接报错、整条公式退化成源码。
        return out.map { balanceDelimiters(it) }
    }

    /**
     * 窗口内**最后一个安全的软切点**：空白处或命令结束边界。
     *
     * 在命令名内部切开会产生非法残命令（`\cos` → `\c` + `os`），
     * 在 `{`/`}` 之间切开会拆坏参数结构。所以软切点只认：
     * - 空白字符之后；
     * - 一个完整 `\command`（含紧跟的花括号参数）结束之后。
     * 找不到返回 null，调用方兜底硬切——宁超宽不破碎，超宽由渲染端缩字号兜住。
     */
    private fun findSoftBreak(latex: String, from: Int, windowEnd: Int): Int? {
        // 允许比窗口超出最多一个命令的回吐，与 findBreak 的 MAX_OVERSHOOT 同理
        val searchEnd = (windowEnd + MAX_OVERSHOOT).coerceAtMost(latex.length)
        var best: Int? = null
        var bestTop: Int? = null
        // `\left` / `\right` 深度：优先在配对定界符**外部**断行（更自然），
        // 内部断行也不是不行——[balanceDelimiters] 会把配平补回来。
        var delimDepth = 0
        var i = from
        while (i < searchEnd) {
            val c = latex[i]
            if (c == '\\') {
                var j = i + 1
                while (j < latex.length && latex[j].isLetter()) j++
                if (j == i + 1) {
                    // `\ `、`\{` 之类单字符控制符号：符号本身之后是安全切点
                    i = j + 1
                    if (j > from + 4) {
                        best = j
                        if (delimDepth == 0) bestTop = j
                    }
                    continue
                }
                // `\left` / `\right` 深度跟踪
                when (latex.substring(i + 1, j)) {
                    "left" -> delimDepth++
                    "right" -> delimDepth = (delimDepth - 1).coerceAtLeast(0)
                }
                // 吃掉紧跟的花括号参数（最多两层，够 \frac{a}{b}）
                var guard = 0
                while (guard < 2) {
                    var k = j
                    while (k < latex.length && latex[k].isWhitespace()) k++
                    if (k < latex.length && latex[k] == '{') {
                        val close = findMatchingBraceAt(latex, k)
                        if (close < 0) { j = latex.length; break }
                        j = close + 1
                        guard++
                    } else break
                }
                if (j <= searchEnd && j > from + 4) {
                    best = j
                    if (delimDepth == 0) bestTop = j
                }
                i = j
                continue
            }
            if (c == ' ' && i > from + 4) {
                best = i + 1
                if (delimDepth == 0) bestTop = i + 1
            }
            i++
        }
        return bestTop ?: best
    }

    /** 找 start 处 `{` 的配对 `}`，找不到返回 -1。 */
    private fun findMatchingBraceAt(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
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
        var bestTop: Int? = null
        // `\left` / `\right` 深度：优先在配对定界符**外部**断行。
        // 落在定界符内部的断点也不是不行——[balanceDelimiters] 会把配平补回来。
        var delimDepth = 0
        var i = from
        while (i < latex.length) {
            val c = latex[i]
            if (c == '\\' && i + 1 < latex.length) {
                // `\\`（行分隔符）本身就是断点
                if (latex[i + 1] == '\\') {
                    if (depth == 0 && i + 2 <= windowEnd + MAX_OVERSHOOT && i >= from) {
                        best = i + 2
                        if (delimDepth == 0) bestTop = i + 2
                    }
                    i += 2
                    continue
                }
                // `\left` / `\right` 深度跟踪
                val cmdEnd = matchCommand(latex, i)
                when (commandName(latex, i)) {
                    "left" -> delimDepth++
                    "right" -> delimDepth = (delimDepth - 1).coerceAtLeast(0)
                }
                if (depth == 0 && i < windowEnd && cmdEnd != null) {
                    best = cmdEnd
                    if (delimDepth == 0) bestTop = cmdEnd
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
                if (delimDepth == 0) bestTop = i + 1
            }
            i++
        }
        val chosen = (listOfNotNull(bestTop, best).firstOrNull { it <= windowEnd + MAX_OVERSHOOT })
            ?: return null
        if (chosen <= from + 4) return null // 切出太碎的行不如不切
        return chosen
    }

    /** 取 [i] 处的命令名（不含反斜杠）；不是命令返回空串。 */
    private fun commandName(latex: String, i: Int): String {
        var j = i + 1
        while (j < latex.length && latex[j].isLetter()) j++
        if (j == i + 1) return ""
        return latex.substring(i + 1, j)
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
