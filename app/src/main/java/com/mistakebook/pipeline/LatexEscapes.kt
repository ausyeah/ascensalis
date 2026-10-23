package com.mistakebook.pipeline

/**
 * LaTeX 与 JSON 转义序列的冲突处理。
 *
 * ## 问题
 * 大模型在 JSON 字符串里写 LaTeX 时，`\t` `\r` `\n` `\b` `\f` 恰好都是
 * **合法的 JSON 转义序列**，解析器会照单全收变成控制字符：
 *
 * ```
 * 源码   \to \text \theta \times \tan \top
 * 实际   TAB+后缀        （反斜杠没了）
 *
 * 源码   \right \rho \rangle
 * 实际   CR+后缀
 *
 * 源码   \neq \nabla \nu \ne \not
 * 实际   LF+后缀
 *
 * 源码   \begin \boxed \beta
 * 实际   BS+后缀
 *
 * 源码   \frac（写成 \f 形式时）
 * 实际   FF+后缀
 * ```
 *
 * 而 `\lim` `\left` `\ln` `\infty` 里的 `\l` `\i` 不是合法转义，所以完好无损——
 * 造成「同一个公式里有的正常有的被毁」，最难排查。
 *
 * 模型本应写 `\\to`（JSON 文本里 `\\` 解析成 `\`），但实际输出经常混用单双反斜杠。
 * **提示词约束不可靠，必须在解析层兜住。**
 *
 * ## 解法
 * 两层，配合使用：
 *
 * 1. [protectLatexEscapes] —— **解析前**在原始 JSON 文本里，把「看起来是
 *    LaTeX 命令」的单反斜杠补成双反斜杠。信息完整保留，JSON 解析后正好还原。
 * 2. [restoreLatexControlChars] —— **解析后**兜底：万一还有控制字符漏网，
 *    且它出现在 `$...$` 数学区里（LaTeX 数学区不可能有裸 TAB/CR/BS/FF），
 *    就把它还原成对应的命令。
 *
 * 两层都不需要猜测命令名——命令名就在被吃掉的那个字母之后（如 `\to` 的 `o`）。
 */
object LatexEscapes {

    /**
     * 会与 JSON 转义冲突的字母。`"` `\` `/` 是 JSON 自己的转义，不能碰。
     *
     * `u` 特殊：合法 JSON 是 `\uXXXX`（4 位十六进制）。若后接的不是 4 位十六进制，
     * 说明模型想写的是 `\underbrace` `\underline` `\bigcup` 这类命令。
     */
    private const val AMBIGUOUS = "trnbf"

    /**
     * 已知 LaTeX 命令的前缀集合，用来判断「这个转义首字母其实是个命令」。
     *
     * 用**前缀**匹配（而非全名）是有意的：只要 `\to` 能认出来，
     * `\rightarrow`、`\top` 也就顺带覆盖了——模型很少写出精确的完整命令名，
     * 截断的形式更常见。
     */
    private val LATEX_COMMAND_PREFIXES: List<String> = listOf(
        // \t 系
        "to", "text", "textbf", "textit", "texttt", "tfrac", "theta", "times",
        "tan", "top", "therefore", "tbinom", "triangle", "thick", "thin",
        // \r 系
        "right", "rho", "rangle", "rfloor", "rfloor", "risingdotseq", "rightarrow",
        // \n 系
        "neq", "ne", "nabla", "nu", "notin", "nmid", "not", "nabla", "nleq",
        "ngeq", "nsim", "nparallel", "nvdash", "nsubseteq", "nsubset",
        // \b 系
        "begin", "boxed", "beta", "bar", "big", "bigl", "bigr", "Bigl",
        "Bigr", "brace", "boldsymbol", "binom", "because", "bmod", "backslash",
        "bigtriangleup", "bigsqcup", "bullet", "braket",
        // \f 系
        "frac", "dfrac", "tfrac", "forall", "flat", "lfloor", "frown", "frac12"
    )

    /**
     * 判断 `\X...` 里的 X 是否其实是一个 LaTeX 命令的首字母。
     *
     * 判据是**后缀能否对上已知命令的前缀**——这比「X 是不是转义字母」精确得多：
     * `\neq` 的 `n` 后面跟着 `eq`，能对上；真正的换行 `\n后面是中文` 对不上任何命令。
     */
    private fun looksLikeLatexCommand(source: String, startIndex: Int): Boolean {
        // startIndex 指向反斜杠后的那个字母
        val letter = source[startIndex]
        // 从**这个字母本身**开始取——漏掉它就永远匹配不上命令名
        val rest = source.substring(startIndex)

        if (letter == 'u') {
            // \uXXXX 是合法 unicode 转义；不是就是 \underbrace 之类
            val hex = rest.substring(1).take(4)
            return !(hex.length == 4 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
        }
        if (letter !in AMBIGUOUS) return false
        // 判断的是「从命令首字母开始的这段文本」是否以某个已知命令开头。
        return LATEX_COMMAND_PREFIXES.any { it.length > 1 && rest.startsWith(it) }
    }

    /**
     * 在 **JSON 解析之前**修好 LaTeX 里的反斜杠。
     *
     * 只在 JSON 字符串字面量内部操作；键名、结构符号、以及真正的
     * 转义（`\"` `\\` `\/`）都原样保留。
     *
     * 已正确双写的 `\\to` 不会被二次处理——第二个反斜杠后面是 `\t`，
     * 而我们只在「反斜杠后面紧跟命令字母」时才补，序列 `\\\t` 里
     * 第一个反斜杠被识别为 `\\` 转义，第二个才参与判定，逻辑上互不干扰。
     */
    fun protectLatexEscapes(raw: String): String {
        if (raw.indexOf('\\') < 0) return raw
        val out = StringBuilder(raw.length + 32)
        var i = 0
        var inString = false
        while (i < raw.length) {
            val ch = raw[i]
            if (!inString) {
                if (ch == '"') inString = true
                out.append(ch)
                i++
                continue
            }
            if (ch == '"') {
                inString = false
                out.append(ch)
                i++
                continue
            }
            if (ch != '\\' || i + 1 >= raw.length) {
                out.append(ch)
                i++
                continue
            }
            val next = raw[i + 1]
            // JSON 自身的转义：引号、反斜杠、斜杠，必须原样保留
            if (next == '"' || next == '\\' || next == '/') {
                out.append(ch).append(next)
                i += 2
                continue
            }
            if (looksLikeLatexCommand(raw, i + 1)) {
                // 补一层反斜杠，让 JSON 解析后还原成命令而不是控制字符
                out.append('\\').append('\\').append(next)
                i += 2
                continue
            }
            out.append(ch).append(next)
            i += 2
        }
        return out.toString()
    }

    /**
     * 解析**之后**的兜底：把残留的 LaTeX 控制字符还原成命令。
     *
     * ## 为什么要这一层
     * [protectLatexEscapes] 依赖命令前缀表，表外的写法仍可能漏。
     * 而一旦解析完成，`\t` 已经变成 TAB，**信息是丢的**——只能靠后面还剩下的
     * 字母反推（`\to` 吃掉反斜杠后剩下 `o`）。好在字母还在，所以能救回来。
     *
     * ## 为什么只在 `$...$` 里做
     * LaTeX 数学区里不可能出现裸 TAB / 回车 / 退格 / 换页；
     * 而正文（中文说明文字）里出现制表符是完全正常的。
     * 所以限定在数学区内，既安全又不误伤。
     *
     * 换行（LF）**不做还原**：`\neq` 与「公式里的真实换行」无法区分，
     * 猜错会把好公式改坏。而 `\neq` 在数学区里换行是极罕见的写法，
     * 让它保留 `\neq` 被吃掉的样子，比把整段多行公式改坏划算。
     *
     * @param mathOnly `true` = 只在 `$...$` 数学区内修复，调用方传的是**带分隔符的整段文本**；
     *   `false` = 整串就是纯 LaTeX，调用方拿到的**分隔符已被剥掉**。
     *
     * ## 这个开关不是可选优化，是必需的
     *
     * 渲染链路里公式是这样传下来的：
     * ```
     * RichText  → InlineSpan.Math(rest.substring(2, end).trim())   ← 剥掉 $
     * PdfExporter → mathRenderer.render(token.latex, token.display)
     * ```
     * 也就是说 [com.mistakebook.math.MathRenderer] 拿到的是**公式内部内容**，
     * 一个 `$` 都没有。若此时仍按「数学区内」判定，`inMath` 永远为 false，
     * 整个修复**一次都不会触发**——而且不报错，只是静默失效。
     *
     * 这正是本项目反复吃过的亏：写完不验证路径，写得很自信，跑起来是死的。
     */
    fun restoreLatexControlChars(text: String, mathOnly: Boolean = true): String {
        if (text.none { it.code in 8..13 }) return text
        val out = StringBuilder(text.length)
        var i = 0
        var inMath = !mathOnly
        while (i < text.length) {
            val ch = text[i]
            if (mathOnly && ch == '$') {
                // `$$...$$`（显示公式）必须当成**一个**分隔符。
                // 若按单 `$` 处理就会开关两次，中间整段被判成「正文」，
                // 修复对显示公式完全失效——而显示公式在解析结果里很常见。
                if (i + 1 < text.length && text[i + 1] == '$') {
                    inMath = !inMath
                    out.append(ch).append('$')
                    i += 2
                    continue
                }
                inMath = !inMath
                out.append(ch)
                i++
                continue
            }
            // LaTeX 标准定界符 `\( \)` `\[ \]`：与 `$` 一样切换数学区。
            // 只认 `$` 的话，`\( a<TAB>b \)` 里的制表符不会被还原，
            // 公式到了 KaTeX 就是解析失败。
            if (mathOnly && ch == '\\' && i + 1 < text.length &&
                (text[i + 1] == '(' || text[i + 1] == ')' ||
                    text[i + 1] == '[' || text[i + 1] == ']')
            ) {
                inMath = !inMath
                out.append(ch).append(text[i + 1])
                i += 2
                continue
            }
            // 只处理 8..13 里的非换行字符，且**后接 ASCII 字母**。
            // 后接字母这一条是关键判据：反斜杠被吃掉后命令名还在
            // （`\to` -> TAB+`o`），而正常中文排版里的制表符后面跟的是汉字。
            // 即使 mathOnly 判错了（中文正文里混进了不成对的 `$`），
            // 这条判据也能把绝大多数误伤挡掉。
            val eligible = ch.code in 8..13 && ch != '\n' &&
                i + 1 < text.length && text[i + 1].isAsciiLetter()
            if (inMath && eligible) {
                when (ch) {
                    '\t' -> out.append("\\t")
                    '\r' -> out.append("\\r")
                    '\u0008' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else -> out.append(ch)
                }
                i++
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun Char.isAsciiLetter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z'

    /**
     * `\right` 被整段删掉后留下的孤儿 `ight`，还原成 `\right`。
     *
     * ## 这是什么
     *
     * v0.0.3 的 `normalizeBreaks` 有一行 `'r' -> { i += 2 }`，
     * 把 LaTeX 命令 `\right` 的**反斜杠和字母 r 一起删掉**，
     * 只剩 `ight]`。信息是真的没了，光看剩余字符无法通用还原。
     *
     * 但这个残留有极强上下文信号：**`ight` 本身不是合法的 LaTeX**，
     * 而 `\left` 后面跟一个裸 `ight]` 只可能是 `\right]` 掉了反斜杠。
     * 所以可以做一次针对性还原。
     *
     * ## 为什么只救 `ight`，不救别的
     *
     * 同类残留还有 `imes`(\times)、`ext`(\text)、`heta`(\theta) 等。
     * 它们没被救，**不是因为没想到，而是因为判据不可靠**：
     * `ext` / `imes` 有可能是合法的字母序列，`ight` 几乎没有歧义。
     * 宁可漏修，也不要把好公式改坏——后者更糟。
     *
     * 真正的修复在源头：[protectLatexEscapes]（解析前保护）
     * 与不再破坏的 `normalizeBreaks`。本函数只为**救回历史数据**而存在。
     *
     * ## 前后断言为什么这么写
     *
     * - 前面排除**字母与反斜杠**：已经正确的 `\right` 里 `ight` 前是 `r`，
     *   必须排除，否则会变成 `\\right`——越修越坏。
     * - 前面**允许数字**：`f_{12}ight)` 里 `ight` 前是 `2`，真机数据就是这个形态。
     * - 后面要求紧跟定界符：`ight` 单独出现时无法与普通字母区分。
     */
    private val ORPHANED_RIGHT = Regex("(?<![A-Za-z\\\\])ight(?=\\s*[)\\]}|.,;])")

    fun restoreOrphanedRight(text: String): String {
        if (!text.contains("ight")) return text
        // 必须用 lambda 形式的替换：字符串重载会把替换串里的 `\` 当转义处理，
        // `"\\right"` 会被吃掉反斜杠变成 `right`——公式里就少了个反斜杠，
        // 而这恰恰是本函数要修的东西，属于把修复变成新 bug。
        return ORPHANED_RIGHT.replace(text) { "\\right" }
    }

    /**
     * 渲染前的总入口：控制字符还原 + 孤儿定界符还原。
     *
     * 两个修复都在这里汇合，调用方不必关心各自的前置条件。
     */
    fun repairForDisplay(text: String, mathOnly: Boolean = true): String =
        restoreOrphanedRight(restoreLatexControlChars(text, mathOnly))
}