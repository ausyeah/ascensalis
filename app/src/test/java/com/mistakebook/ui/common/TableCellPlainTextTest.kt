package com.mistakebook.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 表格单元格降级的回归测试。
 *
 * ## 用户反馈
 *
 * 「约束 AI 不要在表格里面输出 latex，这样会导致渲染问题」。
 *
 * 提示词里禁了（`ChatPrompts.SYSTEM`），但**模型不一定听**——
 * 所以渲染层要有兜底，否则模型一不听话，单元格就显示成一串
 * `$\frac{1}{2}$` 噪声。
 *
 * 这条测试钉住「至少降级成可读文本」这个下限。
 * 它**不**保证公式渲染正确——表格里本来就不渲染公式，
 * 窄单元格里塞位图必然压到看不清。
 */
class TableCellPlainTextTest {

    // ------------------------------------------------------- 剥美元符号

    @Test
    fun `行内公式剥掉美元符号`() {
        assertEquals("x^2 + 1", tableCellToPlainText("\$x^2 + 1\$"))
    }

    @Test
    fun `块级公式剥掉双美元符号`() {
        assertEquals("x = 1", tableCellToPlainText("$\$x = 1\$\$"))
    }

    @Test
    fun `单元格里的反斜杠命令去前缀`() {
        // \frac{x}{y} 先去掉命令名里的反斜杠，再套分式规则
        val out = tableCellToPlainText("\$\\frac{x}{y}\$")
        assertFalse("不该残留反斜杠：$out", out.contains("\\"))
    }

    @Test
    fun `希腊字母命令变可读`() {
        val out = tableCellToPlainText("\$\\alpha\$")
        assertEquals("alpha", out)
    }

    @Test
    fun `分式变斜杠形式`() {
        assertEquals("1/2", tableCellToPlainText("\$\\frac{1}{2}\$"))
    }

    @Test
    fun `未闭合的美元符号也剥掉`() {
        // 流式输出到一半时可能出现，用户至少看到 x^2 而不是 $x^2
        assertEquals("x^2", tableCellToPlainText("\$x^2"))
    }

    @Test
    fun `多个公式在一个单元格里`() {
        val out = tableCellToPlainText("\$a\$ 和 \$b\$")
        assertEquals("a 和 b", out)
    }

    // ------------------------------------------------------- 剥 Markdown 强调

    @Test
    fun `粗体标记去掉`() {
        assertEquals("重点", tableCellToPlainText("**重点**"))
    }

    @Test
    fun `斜体标记去掉`() {
        assertEquals("重点", tableCellToPlainText("*重点*"))
    }

    @Test
    fun `行内代码标记去掉`() {
        assertEquals("x + 1", tableCellToPlainText("`x + 1`"))
    }

    @Test
    fun `强调加公式一起处理`() {
        val out = tableCellToPlainText("**\$x\$**")
        assertEquals("x", out)
    }

    // ------------------------------------------------------- 普通文本不受影响

    @Test
    fun `纯文本原样返回`() {
        assertEquals("函数单调性", tableCellToPlainText("函数单调性"))
    }

    @Test
    fun `空字符串`() {
        assertEquals("", tableCellToPlainText(""))
    }

    @Test
    fun `只有空白`() {
        assertEquals("", tableCellToPlainText("   "))
    }

    /** 普通数学符号（没有美元符号包裹）不该被破坏。 */
    @Test
    fun `无美元符号的数学符号保持原样`() {
        assertEquals("x ≤ 1", tableCellToPlainText("x ≤ 1"))
        assertEquals("α + β", tableCellToPlainText("α + β"))
        assertEquals("a ≥ b", tableCellToPlainText("a ≥ b"))
    }

    /**
     * 不该把正常内容吃掉。
     *
     * 降级逻辑最容易出的错是**过度清洗**——把用户要的内容一起删了。
     */
    @Test
    fun `不会吃掉正常内容`() {
        listOf(
            "定义域为 R",
            "在 (0, +∞) 上单调递增",
            "y = 2x + 1",
            "3.14",
        ).forEach { input ->
            assertEquals("普通内容不应被改动：$input", input, tableCellToPlainText(input))
        }
    }

    /** 分隔符 `|` 是 Markdown 表格语法本身，不应出现在单元格里。 */
    @Test
    fun `空单元格安全`() {
        assertEquals("", tableCellToPlainText(""))
    }

    // ------------------------------------- LaTeX 标准定界符（v0.0.24）

    @Test
    fun `inline 定界符降级成可读文本`() {
        assertEquals("a/b", tableCellToPlainText("\\(\\frac{a}{b}\\)"))
    }

    @Test
    fun `display 定界符降级成可读文本`() {
        assertEquals("a/b", tableCellToPlainText("\\[\\frac{a}{b}\\]"))
    }

    @Test
    fun `定界符与美元混排都降级`() {
        assertEquals(
            "x 与 y",
            tableCellToPlainText("\\(x\\) 与 \$y\$")
        )
    }
}