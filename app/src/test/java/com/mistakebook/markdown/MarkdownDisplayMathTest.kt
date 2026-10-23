package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **跨行 `$$` 公式块**的回归测试。
 *
 * ## 这个 bug 的形状
 *
 * 解析器原来只支持「`$$` 与内容同一行」：`$$x=1$$`。
 * 遇到独占一行的 `$$` 时算出空 latex，然后 `if (latex.isNotEmpty())` 不成立，
 * **什么都不加**——整块推导被静默丢弃，中间那行掉进段落分支以裸 LaTeX 显示。
 *
 * 用户看到的现象就是「气泡里的 latex 不渲染」。
 *
 * ## 为什么题目区没事、气泡有事
 *
 * 识别链路的提示词（`PromptTemplates.LATEX_FORMAT_APPENDIX`）明文禁止
 * 在公式内换行，所以它给过来的 `$$` 永远是单行，永远走不到这个洞。
 * 聊天的提示词早先只说「独立成行用 $$...$$」，**没有禁换行**，
 * 而 LLM 输出
 *
 * ```
 * $$
 * \begin{aligned}
 * a &= b \\
 * c &= d
 * \end{aligned}
 * $$
 * ```
 *
 * 是常规写法，必然踩中。
 *
 * 这类「一边正常一边坏」的 bug，靠读代码很难想到；测试把它钉下来。
 */
class MarkdownDisplayMathTest {

    private fun parse(text: String) = MarkdownParser.parse(text)

    private fun mathBlocks(text: String) =
        parse(text).filterIsInstance<MarkdownParser.Block.Math>()

    // ------------------------------------------------------- 跨行（本次修的）

    @Test
    fun `独占一行的美元对包裹单行公式`() {
        val blocks = mathBlocks("$$\nx = 1\n$$")
        assertEquals("应产出一个公式块", 1, blocks.size)
        assertEquals("x = 1", blocks[0].latex)
        assertTrue("独立公式", blocks[0].display)
        assertTrue("闭合公式", blocks[0].complete)
    }

    @Test
    fun `跨行的多步推导`() {
        val text = "$$\n\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}\n$$"
        val blocks = mathBlocks(text)
        assertEquals(1, blocks.size)
        // 内容要完整保留，包括中间的换行
        assertTrue("含 aligned 环境", blocks[0].latex.contains("\\begin{aligned}"))
        assertTrue("含第一行", blocks[0].latex.contains("a &= b"))
        assertTrue("含第二行", blocks[0].latex.contains("c &= d"))
        assertTrue("含结束标记", blocks[0].latex.contains("\\end{aligned}"))
    }

    @Test
    fun `跨行公式前后的正文不能丢`() {
        val text = "推导如下：\n$$\nx = 1\n$$\n所以答案是 2。"
        val blocks = parse(text)
        assertEquals(1, blocks.count { it is MarkdownParser.Block.Math })
        val paragraphs = blocks.filterIsInstance<MarkdownParser.Block.Paragraph>()
        assertEquals(2, paragraphs.size)
        assertTrue("前文", paragraphs[0].text.contains("推导如下"))
        assertTrue("后文", paragraphs[1].text.contains("答案是 2"))
    }

    @Test
    fun `闭合美元对之后同一行的内容算正文`() {
        val blocks0 = mathBlocks("$$\nx = 1\n" + d("") + " 后面这段是正文")
        assertEquals(1, blocks0.size)
        assertEquals("x = 1", blocks0[0].latex)
        val paragraphs = parse("$$\nx = 1\n" + d("") + " 后面这段是正文")
            .filterIsInstance<MarkdownParser.Block.Paragraph>()
        assertTrue("闭合后的内容不能被吞", paragraphs.any { it.text.contains("后面这段是正文") })
    }

    // 注意：下面这几条都要在字符串里写美元符号。
    // Kotlin 模板将 `$` 当代入开头，连着写 `$$$x` 会被当成
    // “一个美元符 + 插值 $$x”，编译期就报 unresolved reference。
    // 所以这里用 `d()` 拼：部分字符串写法可读性不受影响。
    private fun d(body: String) = "$" + body + "$"

    @Test
    fun `行尾美元对后面还有正文`() {
        val blocks = mathBlocks(d(d("x = 1")) + " 尾巴")
        assertEquals(1, blocks.size)
        assertEquals("x = 1", blocks[0].latex)
    }

    // ------------------------------------------------------- 单行（不能回归）

    @Test
    fun `单行双美元对照旧能用`() {
        val blocks = mathBlocks(d(d("x = \\frac{1}{2}")))
        assertEquals(1, blocks.size)
        assertEquals("x = \\frac{1}{2}", blocks[0].latex)
    }

    @Test
    fun `行内单美元对仍然只进段落`() {
        // 行内公式走 parseInline，不该变成独立块
        val inline = d("x^2")
        val blocks = parse("前面 $inline 后面")
        assertEquals(0, blocks.count { it is MarkdownParser.Block.Math })
        val paragraph = blocks.filterIsInstance<MarkdownParser.Block.Paragraph>().single()
        assertTrue("行内公式保留在段落里", paragraph.text.contains(inline))
    }

    @Test
    fun `两个跨行公式块各自独立`() {
        val text = "$$\na = 1\n$$\n中间说明\n$$\nb = 2\n$$"
        val blocks = mathBlocks(text)
        assertEquals(2, blocks.size)
        assertEquals("a = 1", blocks[0].latex)
        assertEquals("b = 2", blocks[1].latex)
    }

    // ------------------------------------------------------- 流式（半截状态）

    /**
     * 流式输出时正文尾端会停在 `$$x = ` 这种半截状态。
     * 它仍产出未闭合公式块，但标记为不完整：界面先显示源码，闭合后再交给 KaTeX 渲染。
     */
    @Test
    fun `未闭合公式保留源码预览`() {
        val blocks = mathBlocks("推导：\n$$\nx = \\frac{1}{2")
        assertEquals("半截公式也要出块", 1, blocks.size)
        assertTrue("内容保留", blocks[0].latex.contains("\\frac{1}{2"))
        assertEquals(false, blocks[0].complete)
    }

    @Test
    fun `只有开标记没有内容时不产出空块`() {
        // 空块会让渲染层去画一个 0 宽的东西，还会污染 collectMathKeys
        assertEquals(0, mathBlocks("$$\n\n$$").size)
    }

    // ------------------------------------------------------- 不影响其它块

    @Test
    fun `代码块里的美元对不当公式`() {
        val text = "$$\nmath\n$$\n```\n$$\nnot math\n$$\n```"
        val blocks = parse(text)
        val code = blocks.filterIsInstance<MarkdownParser.Block.Code>().single()
        assertTrue("代码内容完整", code.text.contains("$$"))
        assertEquals("只有前面那个是真公式", 1, blocks.count { it is MarkdownParser.Block.Math })
    }

    @Test
    fun `列表项里的行内公式不受影响`() {
        val first = d("a+b")
        val second = d("c+d")
        val blocks = parse("- 第一项 $first\n- 第二项 $second")
        val items = blocks.filterIsInstance<MarkdownParser.Block.ListItem>()
        assertEquals(2, items.size)
        assertTrue(items[0].text.contains(first))
        assertEquals(0, blocks.count { it is MarkdownParser.Block.Math })
    }

    // ------------------------------------------- LaTeX 标准定界符（v0.0.24）

    @Test
    fun `单行 display 定界符产出块级公式`() {
        val blocks = parse("\\[a^2+b^2=c^2\\] 后面还有字")
        val math = blocks.filterIsInstance<MarkdownParser.Block.Math>()
        assertEquals(1, math.size)
        assertEquals("a^2+b^2=c^2", math[0].latex)
        assertEquals(true, math[0].display)
        assertTrue("闭合后的正文不能丢", blocks.any { it is MarkdownParser.Block.Paragraph })
    }

    @Test
    fun `行内混排的 display 定界符留在段落由 parseInline 切分`() {
        // 行首不是 `\[`（前面有文字）：不进块级分支，整段保留，
        // 行内公式 span 由 RichText 的 parseInline 切——两级解析各司其职。
        val blocks = parse("看这个：\\[a^2+b^2=c^2\\] 后面还有字")
        assertEquals(0, blocks.count { it is MarkdownParser.Block.Math })
        val paragraph = blocks.filterIsInstance<MarkdownParser.Block.Paragraph>().single()
        assertTrue(paragraph.text.contains("\\[a^2+b^2=c^2\\]"))
    }

    @Test
    fun `整段只是一个 display 定界符公式`() {
        val blocks = parse("\\[\\frac{1}{2}\\]")
        val math = blocks.filterIsInstance<MarkdownParser.Block.Math>().single()
        assertEquals("\\frac{1}{2}", math.latex)
        assertEquals(true, math.display)
    }

    @Test
    fun `整段只是一个 inline 定界符公式`() {
        val blocks = parse("\\(x\\to0\\)")
        val math = blocks.filterIsInstance<MarkdownParser.Block.Math>().single()
        assertEquals("x\\to0", math.latex)
        assertEquals(false, math.display)
    }

    @Test
    fun `未闭合的 display 定界符不建块按正文走`() {
        val blocks = parse("半截 \\[a+b 后面没有了")
        assertEquals(0, blocks.count { it is MarkdownParser.Block.Math })
    }

    @Test
    fun `段落里混排的 inline 定界符仍留在段落文本`() {
        // 行内混排不进块级分支，由 parseInline 在段落内切公式 span
        val blocks = parse("设 \\(x=1\\) 时成立")
        assertEquals(0, blocks.count { it is MarkdownParser.Block.Math })
        assertTrue(blocks[0] is MarkdownParser.Block.Paragraph)
    }
}