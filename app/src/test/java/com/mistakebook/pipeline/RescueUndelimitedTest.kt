package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 裸 LaTeX 救援（v0.0.51 用例 B1）的启发式测试。
 *
 * 正例来自真机失败原文；反例是「绝不能被误包成公式」的正文——
 * 误包比显示源码更糟：正常中文会被 KaTeX 拒掉或渲染成乱码。
 */
class RescueUndelimitedTest {

    // ---- 正例：真机失败原文 ----

    @Test
    fun caseB1MatrixBlockIsWrapped() {
        val text = "\\boldsymbol{x} = \\begin{pmatrix} x_{1} \\\\ x_{2} \\\\ x_{3} \\end{pmatrix}, " +
            "\\qquad \\mathbf{A} = \\begin{bmatrix} a_{11} & a_{12} \\\\ a_{21} & a_{22} \\end{bmatrix}"
        val rescued = LatexSanitizer.rescueUndelimited(text)
        assertNotNull("矩阵块应被救援", rescued)
        assertEquals("$$${text.trim()}$$", rescued)
    }

    @Test
    fun caseB1CasesBlockIsWrapped() {
        val text = "f(x) = \\begin{cases}x^2, & x \\geq 0 \\\\ -x^2, & x < 0\\end{cases}"
        assertNotNull(LatexSanitizer.rescueUndelimited(text))
    }

    @Test
    fun commandLeadingLineIsWrapped() {
        assertNotNull(LatexSanitizer.rescueUndelimited("\\vec{a} \\cdot \\vec{b} = \\cos\\theta"))
        assertNotNull(LatexSanitizer.rescueUndelimited("\\frac{1}{2} + \\frac{3}{4} = x_{n}"))
    }

    // ---- 反例：正文绝不能被包 ----

    @Test
    fun plainChineseSentenceIsNotWrapped() {
        assertNull(LatexSanitizer.rescueUndelimited("这道题考查向量的数量积与夹角之间的关系"))
        assertNull(LatexSanitizer.rescueUndelimited("我们先分析题目的条件，然后逐步推导出结论"))
    }

    @Test
    fun narrativeWithTrailingPunctuationIsNotWrapped() {
        assertNull(LatexSanitizer.rescueUndelimited("所以 \\alpha = 30^\\circ。"))
        assertNull(LatexSanitizer.rescueUndelimited("下面给出完整解答："))
        assertNull(LatexSanitizer.rescueUndelimited("由 ${'$'}V_y = 10 V_x${'$'} 求 ${'$'}a${'$'}，代入可得"))
    }

    @Test
    fun fewTokensIsNotWrapped() {
        // 只有 1 个命令、无结构令牌：密度不达标
        assertNull(LatexSanitizer.rescueUndelimited("这个结果是 \\alpha"))
    }

    @Test
    fun longChineseNarrativeIsNotWrapped() {
        // 命令密度勉强够、但中间有长中文叙述——正文
        assertNull(
            LatexSanitizer.rescueUndelimited(
                "根据题目条件我们可以知道向量 \\vec{a} 与向量 \\vec{b} 的数量积就等于它们的模长乘以夹角的余弦值"
            )
        )
    }

    @Test
    fun alreadyDelimitedIsNotHandledHere() {
        assertNull(LatexSanitizer.rescueUndelimited("\$\$x = 1\$\$"))
        assertNull(LatexSanitizer.rescueUndelimited("\\[y = 2\\]"))
    }

    @Test
    fun blankAndTinyInputAreRejected() {
        assertNull(LatexSanitizer.rescueUndelimited(""))
        assertNull(LatexSanitizer.rescueUndelimited("  "))
        assertNull(LatexSanitizer.rescueUndelimited("\\alpha"))
    }
}
