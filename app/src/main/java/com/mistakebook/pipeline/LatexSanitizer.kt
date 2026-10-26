package com.mistakebook.pipeline

/**
 * LaTeX 清洗器：把大模型输出的「大概意思对、但语法不合法」的 LaTeX 修到能渲染。
 *
 * ## 为什么必须有这一层
 * 提示词已经严格约束过（见 [ChatPrompts.SYSTEM]），
 * 但**不能只靠提示词**。真机上观察到的失败模式是系统性的、可枚举的：
 * 缺右括号、`\frac` 分子分母没配对花括号、下标后面丢了内容等。
 * 这些都是「差一两个字符」的错误，把它们交给 KaTeX 只会得到一段红字源码。
 *
 * 原则：**只做「确定不改变语义」的修补**，不做猜测性补全。
 * 宁可少修，也不要把公式改成另一个意思——那比显示源码更危险。
 *
 * ## 正则书写约定（本文件专用）
 * Java 正则里 `\\` 匹配**一个**字面反斜杠；`\{` `\}` 是合法的字面花括号转义。
 * raw string 中写 `"""(\\left\s*)\{"""` 才是「匹配源码里的 `\left{`」。
 * 若写成三个反斜杠会变成 `\\\l`，其中 `\l` 是 Java 正则的**非法转义**，
 * 会在类初始化时抛 PatternSyntaxException（ExceptionInInitializerError）。
 */
object LatexSanitizer {

    /**
     * 补全未闭合的花括号。
     * `\frac{1}{2` -> `\frac{1}{2}`。
     * 数一遍 `{` 与 `}` 的差，在末尾补齐。
     */
    fun balanceBraces(input: String): String {
        var depth = 0
        input.forEach { ch ->
            when (ch) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
        }
        if (depth <= 0) return input
        return input + "}".repeat(depth)
    }

    /**
     * 修复未配对的 `\left` / `\right`。
     *
     * KaTeX 遇到孤立的 `\left` 会直接放弃整个公式（抛 ParseError），
     * 于是整条公式退化成红字源码。补上缺失的 `\right.` 就能救回来。
     */
    fun balanceDelimiters(input: String): String {
        val opens = LEFT_DELIMITER_REGEX.findAll(input).count()
        val closes = RIGHT_DELIMITER_REGEX.findAll(input).count()
        if (opens <= closes) return input
        // \left 比 \right 多几个，在末尾补对应数量的 \right.
        return input.trimEnd() + " ".repeat(opens - closes) + "\\right."
    }

    /**
     * 处理 KaTeX 不认识的命令。
     *
     * 大模型有时会输出中文资料里的自定义宏（`\cellcolor`、`\ding`）。
     * 这些在 KaTeX 里没有定义，撞上就整条公式失败。
     *
     * 降级为正体文本（去掉反斜杠）：只是排版变朴素，含义不变。
     *
     * ## 重音命令为什么不删了（2026-10 行为变更）
     *
     * 旧版把 `\bar` `\hat` `\vec` 这类重音命令**直接删除**，理由是
     * 「降级成字母会改掉数学含义」。但用仓库内实际打包的 katex.min.js
     * 逐条实测：**这些命令 KaTeX 全部原生支持**，行内/块级都渲染正常。
     * 删掉它们等于把能渲染的公式主动改坏——`\vec{v}` 显示成 `v`，向量箭头凭空消失。
     *
     * 现在它们进入 [KNOWN_COMMANDS] 原样保留；真正的未知命令仍降级为文本。
     */
    fun dropUnknownCommands(input: String): String {
        var out = input.replace(COMMAND_REGEX) { match ->
            val name = match.groupValues[1]
            if (name in KNOWN_COMMANDS) match.value else name
        }
        // 删除后可能留下连续空格，压一下；也顺手清掉行尾空白
        return out.replace(MULTI_SPACE, " ").trim()
    }

    private val MULTI_SPACE = Regex("\\s{2,}")

    /**
     * 裸 LaTeX 救援（v0.0.51 用例 B1）：模型整段输出 LaTeX 却忘了 `$$` 包裹。
     *
     * 挂在 Markdown 解析的独立段落入口：对「整段就是一条独立公式、
     * 但没带定界符」的文本自动包上 `$$…$$`。
     *
     * 启发式**必须保守**——把正常中文误包成公式比显示源码更糟：
     * - 长度合理，且不以叙述标点收尾（公式行不会用「。：，」结尾）；
     * - LaTeX 令牌（`\命令`、`&`、`\\`、上下标）密度达标；
     * - 含大段中文叙述（>6 个连续汉字，通常只有正文才有）不包；
     * - `\begin{…}` / `\end{…}` 成对出现是强信号，直接接受。
     *
     * 返回 null 表示「不像裸公式」，调用方按原样渲染。
     */
    fun rescueUndelimited(text: String): String? {
        val line = text.trim()
        if (line.length < 6 || line.length > 400) return null
        // 已带任何一种公式定界符的不归这里管
        if (line.contains("$$") || line.contains("\\[")) return null
        // 正文的行会以叙述标点收尾；公式行不会
        if (line.endsWith("。") || line.endsWith("，") || line.endsWith("？") ||
            line.endsWith("！") || line.endsWith("；") || line.endsWith(":") ||
            line.endsWith("：") || line.endsWith(".") || line.endsWith(",") ||
            line.endsWith("?") || line.endsWith("!") || line.endsWith(";")
        ) {
            return null
        }
        val commands = COMMAND_DENSITY.findAll(line).toList()
        // 令牌：\命令、& 对齐、\\ 换行、上下标
        val structuralTokens = line.count { it == '&' } +
            Regex("""\\\\""").findAll(line).count() * 2 +
            Regex("""[_^]\{?""").findAll(line).count()
        val tokenCount = commands.size + structuralTokens
        if (tokenCount < 3) return null
        // 长中文叙述只出现在正文里；公式里的中文应包在 \text{} 内、且很短
        val cjkRun = CJK_RUN.findAll(line).maxOfOrNull { it.value.length } ?: 0
        if (cjkRun > 6) return null
        // 环境结构是强信号：\begin{…} 与 \end{…} 成对出现即接受
        val beginCount = Regex("""\\begin\{""").findAll(line).count()
        val endCount = Regex("""\\end\{""").findAll(line).count()
        if (beginCount in 1..endCount) return "$$" + line + "$$"
        // 其余按「\命令开头」或「命令密度」判定
        val densityOk = commands.size >= 2 && tokenCount >= 4
        return if (startsWithLatexCommand(line) || densityOk) "$$" + line + "$$" else null
    }

    /** 整行是否以一个 `\command` 开头（命令名至少一个字母）。 */
    private fun startsWithLatexCommand(line: String): Boolean {
        if (!line.startsWith("\\")) return false
        var i = 1
        while (i < line.length && line[i].isLetter()) i++
        return i > 1
    }

    /** `\命令`（只匹配命令名，用于密度统计）。 */
    private val COMMAND_DENSITY = Regex("""\\[a-zA-Z]+""")

    /** 连续中文片段（不含全角标点——标点在公式里也可能出现在 \text 内）。 */
    private val CJK_RUN = Regex("""[\u4E00-\u9FFF]+""")
    /**
     * 清理行内公式里最常见的致命问题：未配对的 `$`。
     *
     * 上游 [JsonExtractor] 已保证 `$` 成对，这里只兜底极端情况。
     */
    fun stripStrayDollar(input: String): String {
        // 奇数个未转义的 $ 说明配对已经断了，全删比留着当普通字符更干净
        val unescaped = input.count { it == '$' }
        if (unescaped % 2 == 0) return input
        return input.replace("$", "")
    }

    /**
     * 渲染前救援：把**必然让 KaTeX 报错**的写法改成等价可渲染形式。
     *
     * 与 [dropUnknownCommands] 的区别：那边处理「命令不认识」，
     * 这里处理「结构本身非法」。全部规则都是**确定性改写，不改变数学含义**：
     *
     * 1. `\label{…}` / `\nonumber`：交叉引用元数据，数学含义为零 → 删除。
     * 2. `\ce{X}`：化学式（mhchem 扩展），本包 KaTeX 未带 → 降级为 `\text{X}`。
     * 3. `\begin{multline}` → `\begin{gathered}`、`\begin{flalign}` → `\begin{aligned}`。
     * 4. `\begin{align}` / `align*` / `split` / `alignat` → `\begin{aligned}`：
     *    这几个环境在**行内模式**下 KaTeX 直接报错
     *    （`can be used only in display mode`），聊天气泡里的公式多半是行内的。
     * 5. 环境外裸 `&`：`a &= b` 不带任何 `\begin{…}` 包裹时，KaTeX 直接整条报错。
     * 6. 裸花括号定界符 `\left{` / `\right}`：KaTeX 要求写成 `\left\{` / `\right\}`。
     * 7. `\text{…}` 里嵌 `$…$`：KaTeX 把 `$` 当**字面字符**印出来，
     *    界面上就是截图里的「黑体里带 $ 的乱码」→ 重排成文本/数学交替。
     * 8. 数学模式里的裸 `$`：KaTeX 报 `Can't use function '$' in math mode` → 删除。
     * 9. 数学模式里的中文：KaTeX 报 `Unicode text character '…' used in math mode`
     *    → 把中文片段包进 `\text{…}`。
     * 10. `\multirow` / `\tag` / `tabular`：KaTeX 未定义 → 剥除或改写。
     *
     * 以上每条失败模式都用仓库内 katex.min.js 实测过。
     */
    fun rescueUnsupported(input: String): String {
        var out = LABEL_REGEX.replace(input, "")
        out = out.replace(CE_REGEX) { "\\text{${it.groupValues[1]}}" }
        out = ENV_FLALIGN.replace(out) { m -> m.value.replace("flalign", "aligned") }
        out = ENV_MULTLINE.replace(out) { m -> m.value.replace("multline", "gathered") }
        // align / align* / split / alignat：行内模式下 KaTeX 全部报
        // 「can be used only in display mode」，统一改写成 aligned。
        // alignat 还带列数参数 `{2}`，改写时连参数一起吃掉，
        // 否则 `\begin{aligned}{2}` 会因为多参数报错。
        // 注意：必须用 lambda 形式的 replace。
        // 字符串重载里 `\b` 会被 Matcher 当成转义序列，反斜杠会被吃掉。
        out = ALIGN_BEGIN.replace(out) { "\\begin{aligned}" }
        out = ALIGN_END.replace(out) { "\\end{aligned}" }
        // `\tag{1}`：KaTeX 只在 display 模式认它，行内写就整条失败。
        out = TAG_REGEX.replace(out, "")
        // `\multirow{2}{*}{x}`：KaTeX 未定义，整段剥掉（行跨度信息在聊天里无意义）。
        out = MULTIROW_REGEX.replace(out, "")
        // 裸花括号定界符：`\left{ ... \right}` 在 KaTeX 里报
        // 「Expected '}', got '\right'」；正确写法是 `\left\{`。模型经常漏反斜杠，这里补上。
        out = LEFT_BRACE_REGEX.replace(out) { m -> m.groupValues[1] + "\\{" }
        out = RIGHT_BRACE_REGEX.replace(out) { m -> m.groupValues[1] + "\\}" }
        // `\begin{tabular}{cc}` KaTeX 不支持，换成列规格相近的 array；
        // 列规格里的 `p{2cm}` 宽度声明 KaTeX 也不认，再单独降级。
        out = ENV_TABULAR.replace(out) { m -> m.value.replace("tabular", "array") }
        out = simplifyArraySpecs(out)
        // `\text{由 $V_y$ 求}`：KaTeX 在 \text{} 里把 $ 当普通字符印出来，
        // 界面上就出现「带字面 $ 的乱码」。这里把嵌套数学重排成文本/数学交替。
        out = rewriteNestedMathInText(out)
        // 数学模式里的中文：KaTeX 直接报错，包进 \text{} 即可正常显示。
        out = wrapCjkOutsideText(out)
        // 数学模式里残留的裸 $：KaTeX 直接报「Can't use function '$' in math mode」。
        out = stripBareDollarOutsideText(out)
        // 只在**完全没有环境**时才动 &：环境里的 & 是对齐语义，必须保留。
        // 环境改名发生在前面，改完的名字同样含 \begin{，判断不受影响。
        if (!out.contains("\\begin{")) {
            out = out.replace("&", "")
        }
        return out
    }

    /**
     * 把 `\text{…}` 内部的 `$…$` 重排成「文本 / 数学」交替的等价形式。
     *
     * `\text{由 $V_y = 10 V_x$ 求 }a`
     *   -> `\text{由 }V_y = 10 V_x\text{ 求 }a`
     *
     * 语义完全一致，但 KaTeX 不再把 $ 当字面字符印出来。
     *
     * 返回值是**整个 `\text{…}` 组**的替换文本，调用方直接拼接即可。
     */
    private fun rewriteNestedMathInText(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val textStart = input.indexOf("\\text", i)
            if (textStart < 0) {
                out.append(input, i, input.length)
                break
            }
            val braceOpen = input.indexOf('{', textStart)
            if (braceOpen < 0) {
                out.append(input, i, input.length)
                break
            }
            val braceClose = matchBrace(input, braceOpen)
            if (braceClose < 0) {
                out.append(input, i, input.length)
                break
            }
            out.append(input, i, textStart)
            val inner = input.substring(braceOpen + 1, braceClose)
            out.append(rewriteTextWithNestedMath(inner))
            i = braceClose + 1
        }
        return out.toString()
    }

    /**
     * 把一段 `\text{}` 内部的内容按 `$` 切成「文本段 / 数学段」交替。
     *
     * 返回整个 `\text{…}` 组的等价写法。
     */
    internal fun rewriteTextWithNestedMath(inner: String): String {
        val parts = inner.split('$')
        if (parts.size <= 1) return "\\text{$inner}"
        val out = StringBuilder()
        parts.forEachIndexed { index, part ->
            if (part.isBlank()) return@forEachIndexed
            if (index % 2 == 0) out.append("\\text{").append(part).append('}')
            else out.append(part.trim())
        }
        return out.toString()
    }

    /** 找到与 start 处 `{` 配对的 `}` 下标，找不到返回 -1。 */
    private fun matchBrace(text: String, start: Int): Int {
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
     * 把数学模式里的中文片段包进 `\text{…}`。
     *
     * KaTeX 对数学模式里的非 ASCII 字符直接抛
     * `Unicode text character '由' used in math mode`，
     * 这正是「黑体里的正文整段没渲染出来」的原因之一。
     * 已经在 `\text{…}` 里的内容不动。
     */
    private fun wrapCjkOutsideText(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val textStart = input.indexOf("\\text", i)
            if (textStart < 0) {
                out.append(wrapCjkRun(input.substring(i)))
                break
            }
            val braceOpen = input.indexOf('{', textStart)
            if (braceOpen < 0) {
                out.append(wrapCjkRun(input.substring(i)))
                break
            }
            val braceClose = matchBrace(input, braceOpen)
            if (braceClose < 0) {
                out.append(wrapCjkRun(input.substring(i)))
                break
            }
            out.append(wrapCjkRun(input.substring(i, textStart)))
            out.append(input, textStart, braceClose + 1)
            i = braceClose + 1
        }
        return out.toString()
    }

    /** 把一段里的连续中文（含全角标点）包成 `\text{…}`。 */
    private fun wrapCjkRun(text: String): String {
        if (text.none { isCjk(it) }) return text
        val out = StringBuilder()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.append("\\text{").append(buf).append('}')
                buf.setLength(0)
            }
        }
        text.forEach { ch ->
            if (isCjk(ch)) buf.append(ch) else {
                flush()
                out.append(ch)
            }
        }
        flush()
        return out.toString()
    }

    /** 是否是中文 / 全角标点（CJK 统一表意文字 + CJK 符号标点 + 全角形式）。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0x3000..0x303F) ||
            (code in 0xFF00..0xFFEF)
    }

    /**
     * 删除数学模式里（\text{} 之外）的裸 `$`。
     *
     * 输入已经是纯数学表达式，其中的 `$` 必然是模型误写——
     * KaTeX 遇到会直接整条报错。
     */
    private fun stripBareDollarOutsideText(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val textStart = input.indexOf("\\text", i)
            if (textStart < 0) {
                out.append(input.substring(i).replace("$", ""))
                break
            }
            val braceOpen = input.indexOf('{', textStart)
            if (braceOpen < 0) {
                out.append(input.substring(i).replace("$", ""))
                break
            }
            val braceClose = matchBrace(input, braceOpen)
            if (braceClose < 0) {
                out.append(input.substring(i).replace("$", ""))
                break
            }
            out.append(input.substring(i, textStart).replace("$", ""))
            out.append(input.substring(textStart, braceClose + 1).replace("$", ""))
            i = braceClose + 1
        }
        return out.toString()
    }

    /**
     * 简化 `\begin{array}` 的列规格。
     *
     * `{cc}` 这类 KaTeX 原生支持，原样保留；
     * `{p{2cm}}` 这类宽度声明 KaTeX 不认，降级成 `{c}`。
     */
    private fun simplifyArraySpecs(input: String): String {
        if (!input.contains("\\begin{array}")) return input
        val out = StringBuilder()
        var i = 0
        while (true) {
            val idx = input.indexOf("\\begin{array}", i)
            if (idx < 0) {
                out.append(input, i, input.length)
                break
            }
            out.append(input, i, idx + "\\begin{array}".length)
            var j = idx + "\\begin{array}".length
            while (j < input.length && input[j].isWhitespace()) j++
            if (j < input.length && input[j] == '{') {
                val close = matchBrace(input, j)
                if (close > 0) {
                    val spec = input.substring(j, close + 1)
                    out.append(simplifyArraySpec(spec))
                    i = close + 1
                    continue
                }
            }
            i = idx + "\\begin{array}".length
        }
        return out.toString()
    }

    /** 把一个列规格（如 `{p{2cm}cc}`）简化成 KaTeX 认识的等价物。 */
    private fun simplifyArraySpec(spec: String): String {
        val inner = spec.removePrefix("{").removeSuffix("}")
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        inner.forEach { ch ->
            when {
                ch == '{' -> { cur.append(ch); depth++ }
                ch == '}' -> { cur.append(ch); depth-- }
                depth == 0 && ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.setLength(0) }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        if (tokens.isEmpty()) return spec
        val simplified = tokens.joinToString("") { token ->
            if (token.matches(Regex("[lcr|]+"))) token else "c"
        }
        return "{$simplified}"
    }

    /** `\label{…}`（花括号可缺省）与无参数的 `\nonumber` / `\notag`（编号开关，聊天里无意义）。 */
    private val LABEL_REGEX = Regex("""\\(?:label|nonumber|notag)\s*(?:\[[^\]]*\])?(?:\{[^{}]*\})?""")

    /** 化学式 `\ce{…}`（mhchem 扩展未打包）。只嵌套一层花括号，够用。 */
    private val CE_REGEX = Regex("""\\ce\s*\{([^{}]*)\}""")

    /** KaTeX 没有的环境，改写成行语义相同的已支持环境。begin 与 end 都要改，带 `*` 变体。 */
    private val ENV_FLALIGN = Regex("""\\(?:begin|end)\{flalign\*?""")
    private val ENV_MULTLINE = Regex("""\\(?:begin|end)\{multline\*?""")

    /**
     * `\begin{align}` / `align*` / `split` / `alignat`：
     * 行内模式下 KaTeX 全部报错，统一改写成 `\begin{aligned}`。
     *
     * alignat 的列数参数 `{2}` 也在匹配范围内，一起吃掉。
     * `aligned` 本身不在列表里，所以已经合法的 aligned 环境原样保留。
     */
    private val ALIGN_BEGIN = Regex("""\\begin\{(?:align\*?|split|alignat)\}(?:\s*\{[^{}]*\})?""")

    /** 对应的 `\end{…}`。 */
    private val ALIGN_END = Regex("""\\end\{(?:align\*?|split|alignat)\}""")

    /** `\tag{…}`：行内模式 KaTeX 不认。 */
    private val TAG_REGEX = Regex("""\\tag\*?\s*\{[^{}]*\}""")

    /** `\multirow{2}{*}{x}`：KaTeX 未定义。 */
    private val MULTIROW_REGEX = Regex("""\\multirow\s*\{[^{}]*\}\s*\{[^{}]*\}\s*\{[^{}]*\}""")

    /**
     * 裸花括号定界符：`\left{` / `\right}`。
     *
     * `\\left` 在 raw string 里匹配源码里的**一个**反斜杠 + left。
     * （写成三个反斜杠会变成 `\\\l`，其中 `\l` 是 Java 正则的非法转义。）
     */
    private val LEFT_BRACE_REGEX = Regex("""(\\left\s*)\{""")
    private val RIGHT_BRACE_REGEX = Regex("""(\\right\s*)\}""")

    /** tabular 环境：KaTeX 不支持，改写成 array。 */
    private val ENV_TABULAR = Regex("""\\(?:begin|end)\{tabular\}""")

    /** 完整清洗流水线。 */
    fun clean(input: String): String {
        if (input.isBlank()) return input
        var out = stripStrayDollar(input)
        // 救援必须发生在命令降级之前：\label \ce 若先走降级，
        // 反斜杠被剥掉后就再也认不出来了。
        out = rescueUnsupported(out)
        out = dropUnknownCommands(out)
        out = balanceDelimiters(out)
        out = balanceBraces(out)
        return out.trim()
    }

    /** KaTeX 支持的命令白名单。够用即可，不求覆盖全部。 */
    private val KNOWN_COMMANDS = setOf(
        // 希腊字母
        "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
        "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "rho", "sigma", "tau",
        "upsilon", "phi", "chi", "psi", "omega",
        "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon", "Phi", "Psi", "Omega",
        // 常用变体（模型爱写，KaTeX 都支持）
        "varepsilon", "varphi", "vartheta", "varsigma",
        // 运算符与关系
        "pm", "mp", "times", "div", "cdot", "cdots", "ldots", "dots", "vdots", "ddots",
        "leq", "le", "geq", "ge", "neq", "ne", "approx", "sim", "simeq", "cong", "equiv",
        "propto", "ll", "gg", "subset", "subseteq", "supset", "supseteq", "in", "ni", "notin",
        "cup", "cap", "emptyset", "varnothing", "forall", "exists", "nexists", "neg", "lnot",
        "land", "lor", "wedge", "vee", "oplus", "otimes", "odot", "perp", "parallel",
        "leftarrow", "rightarrow", "leftrightarrow", "Leftarrow", "Rightarrow", "Leftrightarrow",
        "longrightarrow", "longleftrightarrow", "Longrightarrow", "Longleftrightarrow",
        "to", "gets", "mapsto", "infty", "partial", "nabla", "sum", "prod", "int", "iint", "iiint", "oint",
        "sqrt", "frac", "dfrac", "tfrac", "binom", "dbinom", "choose",
        // 初等几何与推理符号（中文教学场景高频，KaTeX 支持）
        "angle", "triangle", "circ", "degree", "because", "therefore",
        "mid", "nmid", "lfloor", "rfloor", "lceil", "rceil", "langle", "rangle",
        "pmod", "bmod", "boxed", "frown", "prime", "hbar",
        // 上下标与结构
        "left", "right", "big", "Big", "bigg", "Bigg",
        "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "limits",
        "quad", "qquad", "enspace", "thinspace", "medspace", "thickspace",
        "begin", "end", "text", "textbf", "textit", "mathrm", "mathbf", "mathit",
        "mathcal", "mathbb", "mathfrak", "mathscr", "mathsf", "mathtt",
        "boldsymbol", "operatorname", "bm",
        // 重音/标记命令：实测 KaTeX 全部支持，原样保留（旧版删除是误伤，见 dropUnknownCommands 注释）
        "bar", "hat", "vec", "tilde", "dot", "ddot", "widehat", "widetilde",
        "overline", "underline", "underbar",
        "overrightarrow", "overleftarrow", "overset", "underset",
        "xrightarrow", "xleftarrow",
        "substack", "stackrel",
        // 三角函数与常见函数名
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "coth", "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "arg", "det", "gcd", "deg",
        "limsup", "liminf", "hom", "ker", "dim", "Re", "Im",
        // 实测支持、但旧版白名单漏掉（会被误降级成纯文本）
        "lvert", "rvert", "lVert", "rVert", "lbrace", "rbrace",
        "coloneqq", "iff", "implies", "leqslant", "geqslant", "lesssim", "lesssim",
        "bigcup", "bigcap", "coprod", "bigoplus", "bigotimes", "bigvee", "bigwedge",
        "vdots", "ddots", "dotsc", "dotsb", "dotsi", "dotsm",
        "leqslant", "geqslant", "lessgtr", "gtrless", "nleq", "ngeq", "nless", "ngtr",
        "nsubseteq", "nsubset", "supsetneq", "subsetneq", "approxeq", "backsim", "backsimeq",
        "therefore", "because", "square", "blacksquare", "blacktriangle", "triangle",
        "checkmark", "mho", "Finv", "Game", "backprime", "dots", "ldotp", "cdotp",
        "substack", "stackrel", "overset", "underset",
        // 空格与符号
        ",", ";", ":", "!", " ",
        // 字体尺寸（少见但合法）
        "tiny", "scriptsize", "footnotesize", "small", "normalsize", "large", "Large", "LARGE", "huge", "Huge"
    )

    /** 匹配 `\command` 或 `\command*`，只取命令名部分。 */
    private val COMMAND_REGEX = Regex("""\\([a-zA-Z]+)\*?""")

    private val LEFT_DELIMITER_REGEX = Regex("""\\left\s*""")
    private val RIGHT_DELIMITER_REGEX = Regex("""\\right\s*""")

    /**
     * 判断清洗后是否值得尝试渲染。
     *
     * 清洗会带来一处残留风险：`\left(` 被补成 `\left(...\right.` 后，
     * 括号配平了但语义可能不完整。这里不判断语义，只判断
     * 「是否还有 KaTeX 一定拒绝的结构」——
     * 交给 KaTeX 的 `throwOnError: false` 去处理剩余情况。
     */
    fun looksRenderable(input: String): Boolean {
        if (input.isBlank()) return false
        // 还有未配对的 \left 说明补全失败
        val opens = LEFT_DELIMITER_REGEX.findAll(input).count()
        val closes = RIGHT_DELIMITER_REGEX.findAll(input).count()
        return opens <= closes
    }
}
