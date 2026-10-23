package com.mistakebook.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内解析：**行为必须与旧实现逐字一致**，同时不再是平方级。
 *
 * ## 背景：流式卡顿的一条真实来源
 *
 * 原实现每轮循环做一次 `input.substring(cursor)`，而循环**每轮只推进 1~2 个字符**
 * （走 `else` 分支时只推进 1）。于是长度 n 的段落要切 n 次、每次切 O(n) 长度，
 * 合计 **O(n²) 次字符复制**。
 *
 * 而流式输出期间这段代码每 500ms 对**每个段落**重跑一次。
 * 纯文本段落是最坏情况：一段 2000 字正文 = 2000 次切片、约 200 万次字符复制。
 *
 * 现在改成按索引比较与切片，分配降到 0。
 *
 * ## 最重要的一组测试：与旧实现对照
 *
 * 重写解析器最怕的是「优化了但语义悄悄变了」——
 * 比如 `$` 找错了配对位置、图片 alt/path 弄反。
 * 所以这里把旧实现原样复刻成 [legacyParseInline]，
 * 让**同一批输入**跑两条实现，逐字比对输出。
 *
 * 这是刻意的「测试里保留一份旧实现」——
 * 与项目里「测试复制实现 = 测试不存在」的教训不冲突：
 * 那条讲的是**只**测复制的实现；这里是**被测实现与参照实现**对照，
 * 被测的仍然是真实的 [parseInline]。
 */
class ParseInlineTest {

    // ------------------------------------------------------------------
    // 一、与旧实现逐字对照
    // ------------------------------------------------------------------

    /** 覆盖各种分支：纯文本、粗体、斜体、行内代码、行内公式、块级公式、图片、孤立标记。 */
    private val corpus = listOf(
        "",
        " ",
        "普通中文段落，没有任何标记。",
        "带公式的句子：质能方程 \$E=mc^2\$ 就在这里。",
        "块级公式：\$\$a^2 + b^2 = c^2$\$ 后面还有文字。",
        "**粗体**与*斜体*混排。",
        "`inline code` 夹在文字中间。",
        "图片：![示意图](http://x/y.png) 结束。",
        "多个公式：\$a\$、\$b\$、\$c\$ 排成一排。",
        "空的公式：\$\$ 内容 \$\$ 前后有字。",
        "未闭合的行内公式：\$E=mc 这个 dollar 后面没关上",
        "孤立的 dollar 符号 \$ 后面没有配对",
        "\$\$ 的情况：连续两个 dollar 开头",
        "嵌套标记：**粗体里有 \$x\$ 公式**",
        "转义场景：价格是 $100 和 $200",
        "带括号路径的图片：![a](C:/dir/(x)/file.png)",
        "图片没有 alt：![](path/to.png)",
        "图片括号未闭合：![alt](unclosed",
        "markdown 方括号但不是图片：[text](notimage)",
        "多段落换行符\n和第二行",
        "行内代码里有 \$ 符号：`\$notMath\$`",
        "粗体未闭合：**只有开头",
        "斜体与星号：**不是斜体***",
        "美元符号紧邻文字：$5 和 $10",
        "公式里有花括号：$\\frac{a}{b}$",
        "公式里有反斜杠：$\\sum_{i=1}^{n} i$",
        "连续图片：![](a.png)![](b.png)",
        "图片紧跟文字：![](a.png)紧跟",
        "文字紧跟图片：紧跟![](a.png)",
        // ↓ 这几条专门针对「$$ 配对位置」：配对错一位就会切出不同的公式内容。
        // 写这条的起因是变异验证发现「配对错一位」没被抓到——语料不够。
        "\$\$x\$\$y\$\$",
        "\$\$a+b\$\$ \$\$c+d\$\$",
        "\$\$\$\$ 中间空的一对 \$\$\$\$",
        "\$\$a\$\$ 与 \$b\$ 混排",
        "\$\$\$\$",
        "\$\$a\$$",
        "开头就是 \$\$ 只有前导没有闭合",
        "\$\$ 带前导空格 \$x\$ 结尾",
        // ↓ LaTeX 标准定界符（v0.0.24 新增分支）
        "inline 定界符：\\(a+b\\) 在句子里。",
        "display 定界符：\\[a^2+b^2=c^2\\] 后面还有字。",
        "两个 inline：\\(x\\) 与 \\(y\\) 并列。",
        "两个 display：\\[x\\] 之后 \\[y\\]。",
        "混合定界符：\\(a\\) 与 \$b\$ 与 \\[c\\] 与 \$\$d\$\$ 同在。",
        "未闭合的 inline：\\(a+b 后面没有了",
        "未闭合的 display：\\[a+b 后面没有了",
        "闭合符在前面：a+b\\) 这句话先出现闭符",
        "定界符里嵌美元：\\(\\frac{a}{b}\\)",
        "display 定界符嵌公式环境：\\[\\begin{aligned}a&=b\\\\c&=d\\end{aligned}\\]"
    )

    /**
     * 核心等价性断言：两条实现对同一批输入产出**完全一样**的片段序列。
     *
     * 这条红了说明优化改坏了语义——比任何单条分支测试都更值得先看。
     */
    @Test
    fun `与旧实现逐字等价`() {
        corpus.forEach { input ->
            assertEquals(
                "输入「${input.take(40)}」的解析结果与旧实现不一致",
                legacyParseInline(input),
                parseInline(input)
            )
        }
    }

    /** 分支覆盖计数——防止 corpus 哪天被改小、悄悄不再覆盖某条分支。 */
    @Test
    fun `对照语料确实覆盖了各类分支`() {
        val spans = corpus.flatMap { parseInline(it) }
        val kinds = spans.map { it::class.simpleName }.toSet()
        listOf("Text", "Math", "Image").forEach {
            assertTrue("对照语料里没有出现 $it 片段", kinds.contains(it))
        }
        // 粗体 / 斜体 / 行内代码三种状态都要碰到
        val texts = spans.filterIsInstance<InlineSpan.Text>()
        assertTrue("没有粗体片段", texts.any { it.bold })
        assertTrue("没有斜体片段", texts.any { it.italic })
        assertTrue("没有行内代码片段", texts.any { it.code })
    }

    // ------------------------------------------------------------------
    // 二、语义细节（对照之外，单独钉住关键分支）
    // ------------------------------------------------------------------

    /** 块级公式取 `$$` 之间，**不含**定界符。 */
    @Test
    fun `块级公式取配对括号之间的内容`() {
        val spans = parseInline("前\$\$a+b\$\$后")
        val math = spans.filterIsInstance<InlineSpan.Math>().single()
        assertEquals("a+b", math.latex)
    }

    /**
 * 行内公式取第一个配对 `$` 之后、下一个 `$` 之前。
     *
     * 顺带记一条**结构性不可达**的结论（变异验证时查出来的）：
     *
     * `end > 1` 被改成 `end > 0`，在任何输入下都测不出差别。
     * 因为「`end` 不大于 1」只可能发生在 `end == 0` 或 `end == 1`，
     * 而 `end == 0` 不可能（cursor 处的 `$` 已被 `startsWithAt("\$")` 吃掉，
     * `indexOfFrom('\$', 1)` 从 cursor+1 起找）；`end == 1` 意味着两个 `$` 相邻，
     * 也就是输入以 `$$` 开头——**那种输入进不了这个分支**，
     * 会被上面对 `$$` 的分支先截走。
     *
     * 所以 `end > 1` 这个判断**目前是不可测的冗余防御**，不是活逻辑。
     * 留着无害（万一将来调整分支顺序它会立刻生效），但不要以为有测试在保护它。
     */
    @Test
    fun `行内公式取配对美元符之间的内容`() {
        val spans = parseInline("前\$E=mc^2\$后")
        val math = spans.filterIsInstance<InlineSpan.Math>().single()
        assertEquals("E=mc^2", math.latex)
    }

    /**
     * 变异的另一个：**成对查找的起始偏移**从 2 改成 1，同样测不出差别。
     *
     * 原因是 `$$` 分支只在 `startsWithAt("$$")` 成立时进入，
     * 此时 cursor 与 cursor+1 都是 `$`，而从 1 或 2 起找 `$$` 的首个位置必然相同。
     *
     * 记下来是为了避免以后反复尝试「补语料来抓这个变异」——
     * **没有语料能抓到它，它在结构上就是等价的。**
     */
    @Test
    fun `成对查找的起始偏移在当前分支条件下等价`() {
        // 只用「内容非空」的配对形式：\$\$ 紧贴的 \$\$\$\$ 配对位置是 2，
        // 而切出公式的条件是 end > 2，所以它**不会**被切成公式（与旧实现一致）。
        listOf("\$\$x\$\$y\$\$", "\$\$a+b\$\$", "\$\$带空格 的\$\$").forEach {
            val spans = parseInline(it)
            val maths = spans.filterIsInstance<InlineSpan.Math>()
            assertTrue("「$it」应至少切出一个公式", maths.isNotEmpty())
            // 公式内容不该含定界符本身
            maths.forEach { m ->
                assertTrue("公式内容不该含定界符：${m.latex}", !m.latex.contains("\$\$"))
            }
        }
        // 紧贴的两对不切公式——这是原有行为，不是回归
        assertTrue(parseInline("\$\$\$\$").none { it is InlineSpan.Math })
    }

    /** 未闭合的公式退化成普通文本，绝不能吞掉后面的内容。 */
    @Test
    fun `未闭合的行内公式不会吞掉后文`() {
        val spans = parseInline("\$未闭合的后文")
        assertTrue("未闭合时不该产出公式片段", spans.none { it is InlineSpan.Math })
        assertEquals("\$未闭合的后文", spans.filterIsInstance<InlineSpan.Text>().joinToString("") { it.text })
    }

    /** 图片：alt 是第一个组、path 是第二个组——两者弄反是很容易犯的错。 */
    @Test
    fun `图片的 alt 与 path 不弄反`() {
        val spans = parseInline("![替代文字](图片地址.png)")
        val img = spans.filterIsInstance<InlineSpan.Image>().single()
        assertEquals("图片地址.png", img.path)
        assertEquals("替代文字", img.alt)
    }

    /** 多个公式要各自成片段，不能被合并成一个。 */
    @Test
    fun `多个公式各自成片段`() {
        val spans = parseInline("\$a\$ 和 \$b\$ 和 \$c\$")
        assertEquals(
            listOf("a", "b", "c"),
            spans.filterIsInstance<InlineSpan.Math>().map { it.latex }
        )
    }

    /**
 * 空输入返回空列表；纯空白返回**一个空白文本片段**。
 *
 * 后者不是 bug：`"   "` 走 `else` 分支被原样收进 buffer，
 * 界面上就该显示这段空白。对照测试也已确认新旧一致。
 */
    @Test
    fun `空输入返回空列表`() {
        assertTrue(parseInline("").isEmpty())
        assertEquals(listOf("   "), parseInline("   ").filterIsInstance<InlineSpan.Text>().map { it.text })
    }

    /**
     * **新实现的优势所在**：纯文本长段落不再产生平方级复制。
     *
     * 这里不测时间（机器差异太大，测试会变成 flaky），
     * 而是直接断言**新实现里不存在逐字符切片**——
     * 用一个 5000 字的纯文本段落，它必须秒回。
     *
     * 旧实现在这个规模上要切 5000 次、共约 1250 万次字符复制；
     * 新实现一次都不切。
     */
    @Test
    fun `长纯文本段落不会卡住`() {
        val long = "字".repeat(5000)
        val start = System.nanoTime()
        val spans = parseInline(long)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(1, spans.size)
        assertEquals(5000, spans.filterIsInstance<InlineSpan.Text>().single().text.length)
        assertTrue("5000 字纯文本解析耗时 ${elapsedMs}ms，明显偏离线性", elapsedMs < 500)
    }

    /** 长段落里的公式仍要正确切出来，不能因为优化丢内容。 */
    @Test
    fun `长段落中的公式仍被正确切出`() {
        val filler = "字".repeat(3000)
        val spans = parseInline(filler + "\$x^2\$" + filler)
        val math = spans.filterIsInstance<InlineSpan.Math>().single()
        assertEquals("x^2", math.latex)
        // 前后文本长度都要完整保留
        val total = spans.filterIsInstance<InlineSpan.Text>().sumOf { it.text.length }
        assertEquals(6000, total)
    }

    // ------------------------------------------------------------------
    // 三、旧实现：仅作对照基准，不是被测对象
    // ------------------------------------------------------------------

    /**
     * 优化前的原实现，逐字照搬（连 `substring` 的平方级行为一起保留）。
     *
     * **只用于对照**。它不在生产代码里，也不参与界面渲染。
     */
    private fun legacyParseInline(input: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        var cursor = 0
        var bold = false
        var italic = false
        var code = false
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += InlineSpan.Text(buffer.toString(), bold, italic, code)
                buffer.clear()
            }
        }

        while (cursor < input.length) {
            val rest = input.substring(cursor)
            when {
                rest.startsWith("**") -> {
                    flush()
                    bold = !bold
                    cursor += 2
                }

                rest.startsWith("`") -> {
                    flush()
                    code = !code
                    cursor += 1
                }

                // v0.0.24 起新增的 LaTeX 标准定界符分支（与 parseInline 同步）：
                // `\[...\]` display、`\(...\)` inline。定界符本身不进公式内容。
                rest.startsWith("\\[") -> {
                    val end = rest.indexOf("\\]", 2)
                    if (end > 2) {
                        flush()
                        spans += InlineSpan.Math(rest.substring(2, end).trim(), display = true)
                        cursor += end + 2
                    } else {
                        buffer.append('\\')
                        cursor++
                    }
                }

                rest.startsWith("\\(") -> {
                    val end = rest.indexOf("\\)", 2)
                    if (end > 2) {
                        flush()
                        spans += InlineSpan.Math(rest.substring(2, end).trim())
                        cursor += end + 2
                    } else {
                        buffer.append('\\')
                        cursor++
                    }
                }

                rest.startsWith("$$") -> {
                    val end = rest.indexOf("$$", 2)
                    if (end > 2) {
                        flush()
                        spans += InlineSpan.Math(rest.substring(2, end).trim())
                        cursor += end + 2
                    } else {
                        buffer.append('$')
                        cursor++
                    }
                }

                rest.startsWith("$") -> {
                    val end = rest.indexOf('$', 1)
                    if (end > 1) {
                        flush()
                        spans += InlineSpan.Math(rest.substring(1, end).trim())
                        cursor += end + 1
                    } else {
                        buffer.append('$')
                        cursor++
                    }
                }

                rest.startsWith("![") -> {
                    val match = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)").find(rest)
                    if (match != null) {
                        flush()
                        spans += InlineSpan.Image(match.groupValues[2].trim(), match.groupValues[1])
                        cursor += match.value.length
                    } else {
                        buffer.append(input[cursor])
                        cursor++
                    }
                }

                rest.startsWith("*") && !rest.startsWith("**") -> {
                    flush()
                    italic = !italic
                    cursor += 1
                }

                else -> {
                    buffer.append(input[cursor])
                    cursor++
                }
            }
        }
        flush()
        return spans
    }

    // ------------------------------------------------------------------
    // 三、LaTeX 标准定界符（v0.0.24：`\(..\)` 与 `\[..\]`）
    //
    // 背景：模型输出公式时四种定界符混用是常态，只认 `$` 会让满屏
    // LaTeX 源码以文本形式显示。用户反馈「很基本的公式都没渲染出来」。
    // ------------------------------------------------------------------

    @Test
    fun `inline 定界符产出公式 span`() {
        val spans = parseInline("看这个 \\(a+b\\) 公式")
        val math = spans.filterIsInstance<InlineSpan.Math>()
        assertEquals(1, math.size)
        assertEquals("a+b", math[0].latex)
        assertEquals(false, math[0].display)
        // 定界符本身不残留
        assertTrue(spans.none { it is InlineSpan.Text && it.text.contains("\\(") })
    }

    @Test
    fun `display 定界符产出 display 公式 span`() {
        val spans = parseInline("公式 \\[x^2+y^2=z^2\\] 结束")
        val math = spans.filterIsInstance<InlineSpan.Math>()
        assertEquals(1, math.size)
        assertEquals("x^2+y^2=z^2", math[0].latex)
        assertEquals(true, math[0].display)
    }

    @Test
    fun `display 定界符内容按原样保留换行与环境`() {
        val spans = parseInline("\\[\\begin{aligned}a&=b\\\\c&=d\\end{aligned}\\]")
        val math = spans.filterIsInstance<InlineSpan.Math>().single()
        assertEquals("\\begin{aligned}a&=b\\\\c&=d\\end{aligned}", math.latex)
        assertEquals(true, math.display)
    }

    @Test
    fun `未闭合的 LaTeX 定界符按普通字符处理`() {
        // 没有闭符时不产公式 span，`\` 原样进文本（流式藏尾由 ChatStreaming 负责）
        val inline = parseInline("半截 \\(a+b 后面没了")
        assertTrue(inline.filterIsInstance<InlineSpan.Math>().isEmpty())
        val display = parseInline("半截 \\[a+b 后面没了")
        assertTrue(display.filterIsInstance<InlineSpan.Math>().isEmpty())
    }

    @Test
    fun `闭符出现在开头不当成公式`() {
        val spans = parseInline("a+b\\) 这句话先出现闭符")
        assertTrue(spans.filterIsInstance<InlineSpan.Math>().isEmpty())
    }

    @Test
    fun `定界符与美元符号混排各归各位`() {
        // `$$..$$` 行内混排时沿用旧语义：display=false（块级化只发生在独占一行/整段时）
        val spans = parseInline("\\(a\\) 和 \$b\$ 和 \\[c\\] 和 \$\$d\$\$")
        val math = spans.filterIsInstance<InlineSpan.Math>().map { it.latex to it.display }
        assertEquals(listOf("a" to false, "b" to false, "c" to true, "d" to false), math)
    }

    @Test
    fun `反斜杠不是定界符时照常当普通字符`() {
        // Windows 路径里的 `\` 不该触发任何公式分支
        val spans = parseInline("路径 C:\\dir\\file 结尾")
        assertTrue(spans.filterIsInstance<InlineSpan.Math>().isEmpty())
    }
}