package com.mistakebook.ui.common

import com.mistakebook.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 表格内公式渲染的回归测试（v0.0.50）。
 *
 * ## 背景
 *
 * v0.0.49 之前，表格单元格里的一切内容都被降级成纯文本
 * （[tableCellToPlainText]），所以 `$x^2$` 显示成字面的 `x^2`，
 * 更糟的是 `\frac{1}{2}` 会被剥成 `1/2` 后彻底丢掉公式形态。
 *
 * 现在表格单元格走完整的行内渲染路径（[tableCellSpans]），
 * 公式被切成 [InlineSpan.Math] 交给 KaTeX 出图。
 */
class TableCellSpansTest {

    @Test
    fun `表格单元格里的行内公式被切成数学片段`() {
        val spans = tableCellSpans("令 \$x^2 + y^2 = z^2\$ 成立")
        val math = spans.filterIsInstance<InlineSpan.Math>()
        assertEquals("应切出一个公式片段", 1, math.size)
        assertEquals("x^2 + y^2 = z^2", math.first().latex)
        assertTrue("公式前后应保留文字", spans.any { it is InlineSpan.Text })
    }

    @Test
    fun `表格单元格里的文字不残留美元符号`() {
        val spans = tableCellSpans("值是 \$42\$")
        val text = spans.filterIsInstance<InlineSpan.Text>().joinToString("") { it.text }
        assertTrue("文字里不该有 $：$text", !text.contains('$'))
    }

    @Test
    fun `纯文字单元格不产生公式片段`() {
        val spans = tableCellSpans("普通文字")
        assertTrue(spans.none { it is InlineSpan.Math })
    }

    @Test
    fun `单元格里多个公式都被保留`() {
        val spans = tableCellSpans("\$a\$ 与 \$b\$")
        assertEquals(2, spans.filterIsInstance<InlineSpan.Math>().size)
    }

    @Test
    fun `表格块级的公式收集能拿到单元格里的公式`() {
        val md = """
            | 名称 | 公式 |
            | --- | --- |
            | 勾股 | ${'$'}a^2+b^2=c^2${'$'} |
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        val table = blocks.filterIsInstance<MarkdownParser.Block.Table>().firstOrNull()
        assertTrue("应解析出表格", table != null)
        val cells = table!!.rows.flatten()
        assertTrue("单元格应含公式文本", cells.any { it.contains('$') })
    }

    @Test
    fun `表格单元格里的文字仍做可读化处理`() {
        // 纯文字里的残留 LaTeX 仍要剥掉反斜杠命令
        val spans = tableCellSpans("\\alpha")
        val text = spans.filterIsInstance<InlineSpan.Text>().joinToString("") { it.text }
        assertTrue("应剥掉反斜杠：$text", !text.contains("\\alpha"))
    }
}
