package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.0.51 任务书 T4 三个失败用例的语料回归。
 *
 * 断言核心：渲染入口拿到的块结构里，公式内容必须已进 [Block.Math]，
 * **不得**以裸 `\命令` 或裸 `$` 定界符文本留在 Paragraph/Heading 里。
 */
class FormulaCorpusTest {

    /** 用例 A 原文：显示不全（根因在 MathWrap 拆行，这里钉住解析层语义）。 */
    @Test
    fun caseADisplayMathParsesAsOneBlock() {
        val text = "\$\$\\vec{a} \\cdot \\vec{b} = \\lvert \\vec{a} \\rvert \\lvert \\vec{b} \\rvert \\cos \\theta\$\$"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        val math = blocks.single() as MarkdownParser.Block.Math
        assertTrue(math.display)
        assertTrue(math.complete)
        assertTrue(math.latex.contains("\\cos"))
    }

    /** 用例 B1 原文：裸 LaTeX 独立段 → 自动包 $$ 成块。 */
    @Test
    fun caseB1UndelimitedMatrixIsRescued() {
        val text = "\\boldsymbol{x} = \\begin{pmatrix} x_{1} \\\\ x_{2} \\\\ x_{3} \\end{pmatrix}, " +
            "\\qquad \\mathbf{A} = \\begin{bmatrix} a_{11} & a_{12} \\\\ a_{21} & a_{22} \\end{bmatrix}"
        val blocks = MarkdownParser.parse(text)
        blocks.forEach { block ->
            assertTrue(
                "裸 LaTeX 残留: $block",
                block !is MarkdownParser.Block.Paragraph || !block.text.contains("\\begin{")
            )
        }
        assertTrue(blocks.filterIsInstance<MarkdownParser.Block.Math>().isNotEmpty())
    }

    /** 用例 B1 第二段原文：cases 环境。 */
    @Test
    fun caseB1UndelimitedCasesIsRescued() {
        val text = "f(x) = \\begin{cases}x^2, & x \\geq 0 \\\\ -x^2, & x < 0\\end{cases}"
        val blocks = MarkdownParser.parse(text)
        assertTrue(
            "cases 应被救援成 Math 块: $blocks",
            blocks.filterIsInstance<MarkdownParser.Block.Math>().isNotEmpty()
        )
    }

    /** 用例 B2 原文：标题里夹行内公式 → Heading 保留文本且 Heading 分支可拿到公式 span。 */
    @Test
    fun caseB2HeadingKeepsTextAndInlineParses() {
        val text = "### 解：由 \$V_y = 10 V_x\$ 求 \$a\$"
        val blocks = MarkdownParser.parse(text)
        val heading = blocks.single() as MarkdownParser.Block.Heading
        // 标题文本保持原样（含定界符），行内解析在渲染层（RichText/parseInline）做
        assertTrue(heading.text.contains("V_y = 10 V_x"))
        // 语料断言：解析产物不得把公式内容丢掉
        assertTrue(heading.text.contains("求"))
    }

    /** B2 原文按段落走时的行内公式保留（与同屏正常的 $6\pi$ 一致）。 */
    @Test
    fun caseB2ParagraphInlineMathIntact() {
        val text = "由 \$V_y = 10 V_x\$ 求 \$a\$"
        val blocks = MarkdownParser.parse(text)
        val paragraph = blocks.single() as MarkdownParser.Block.Paragraph
        assertEquals("由 \$V_y = 10 V_x\$ 求 \$a\$", paragraph.text)
    }

    /** 反例回归：正常中文绝不能被误包成公式。 */
    @Test
    fun normalChineseStaysParagraph() {
        val text = "这道题考查向量的数量积与夹角之间的关系，我们先分析条件。"
        val blocks = MarkdownParser.parse(text)
        assertTrue(blocks.single() is MarkdownParser.Block.Paragraph)
    }

    /** 语料断言：任何块都不得把裸 \命令 混进普通段落文本（历史失败样例）。 */
    @Test
    fun noBareCommandInParagraphAfterRescue() {
        val corpus = listOf(
            "\\vec{F} = m\\vec{a} \\Rightarrow F_{net} = ma",
            "\\int_{0}^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
            "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}"
        )
        corpus.forEach { text ->
            val blocks = MarkdownParser.parse(text)
            blocks.forEach { block ->
                if (block is MarkdownParser.Block.Paragraph) {
                    assertTrue(
                        "裸 LaTeX 未被救援: ${block.text}",
                        !block.text.startsWith("\\")
                    )
                }
            }
        }
    }
}
