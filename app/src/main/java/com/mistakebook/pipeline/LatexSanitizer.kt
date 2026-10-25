package com.mistakebook.pipeline

/**
 * LaTeX 清洗器：把大模型输出的「大概意思对、但语法不合法」的 LaTeX 修到能渲染。
 *
 * ## 为什么必须有这一层
 * 提示词已经严格约束过（见 [PromptTemplates.LATEX_FORMAT_APPENDIX]），
 * 但**不能只靠提示词**。真机上观察到的失败模式是系统性的、可枚举的：
 * 缺右括号、`\frac` 分子分母没配对花括号、下标后面丢了内容等。
 * 这些都是「差一两个字符」的错误，把它们交给 KaTeX 只会得到一段红字源码。
 *
 * 原则：**只做「确定不改变语义」的修补**，不做猜测性补全。
 * 宁可少修，也不要把公式改成另一个意思——那比显示源码更危险。
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
     * 逐条实测（LatexSanitizerTest 注释里记录了方法）：**这些命令 KaTeX
     * 全部原生支持**，行内/块级都渲染正常。删掉它们等于把能渲染的公式
     * 主动改坏——`\vec{v}` 显示成 `v`，向量箭头凭空消失。
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
     * 1. `\label{…}` / `\nonumber`：交叉引用元数据，数学含义为零，KaTeX 不支持 → 删除。
     * 2. `\ce{X}`：化学式（mhchem 扩展），本包 KaTeX 未带 → 降级为 `\text{X}`。
     * 3. `\begin{multline}` → `\begin{gathered}`、`\begin{flalign}` → `\begin{aligned}`：
     *    KaTeX 没有这两个环境，但行语义相同（逐行排 / 按 & 对齐）。
     * 4. 环境外裸 `&`：`a &= b` 不带任何 `\begin{…}` 包裹时，KaTeX 直接整条报错
     *    （真机聊天里最高频的红色源码来源）。`&` 在环境外没有数学含义，删掉即可。
     *
     * 以上每条失败模式都用仓库内 katex.min.js 实测过（见 LatexSanitizerTest 注释）。
     */
    fun rescueUnsupported(input: String): String {
        var out = LABEL_REGEX.replace(input, "")
        out = out.replace(CE_REGEX) { "\\text{${it.groupValues[1]}}" }
        out = ENV_FLALIGN.replace(out) { m -> m.value.replace("flalign", "aligned") }
        out = ENV_MULTLINE.replace(out) { m -> m.value.replace("multline", "gathered") }
        // 只在**完全没有环境**时才动 &：环境里的 & 是对齐语义，必须保留。
        // 环境改名发生在前面，改完的名字同样含 \begin{，判断不受影响。
        if (!out.contains("\\begin{")) {
            out = out.replace("&", "")
        }
        return out
    }

    /** `\label{…}`（花括号可缺省）与无参数的 `\nonumber` / `\notag`（编号开关，聊天里无意义）。 */
    private val LABEL_REGEX = Regex("""\\(?:label|nonumber|notag)\s*(?:\[[^\]]*\])?(?:\{[^{}]*\})?""")

    /** 化学式 `\ce{…}`（mhchem 扩展未打包）。只嵌套一层花括号，够用。 */
    private val CE_REGEX = Regex("""\\ce\s*\{([^{}]*)\}""")

    /** KaTeX 没有的环境，改写成行语义相同的已支持环境。begin 与 end 都要改，带 `*` 变体。 */
    private val ENV_FLALIGN = Regex("""\\(?:begin|end)\{flalign\*?""")
    private val ENV_MULTLINE = Regex("""\\(?:begin|end)\{multline\*?""")

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
        "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "arg", "det", "gcd", "deg",
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
