package com.mistakebook.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长公式主动换行的回归测试。
 *
 * 用户报的现象：「长公式渲染不出来」「渲染出来很小」——根因是整条公式
 * 被 [MathLayout.fit] 等比压进一行。[MathWrap] 把超长公式按顶层断点
 * 拆成多行，每个片段都能按目标字号完整渲染。
 *
 * 这些断言钉死三件事：
 * 1. 短公式一个字符都不动；
 * 2. 多行环境（aligned/matrix）按行拆、不碰 `&` 对齐点和嵌套结构；
 * 3. 单行超长公式在**顶层**运算符处断行，括号组绝不从中间撕开。
 */
class MathWrapTest {

    @Test
    fun shortFormulaStaysIntact() {
        val latex = "x^2 + y^2 = z^2"
        assertEquals(listOf(latex), MathWrap.splitForDisplay(latex, 60))
    }

    @Test
    fun blankInputReturnsSingleBlank() {
        assertEquals(listOf(""), MathWrap.splitForDisplay("   ", 60))
    }

    @Test
    fun tinyBudgetBelowMinStaysIntact() {
        // 预算低于 MIN_SPLIT_CHARS：拆出来的行比原文还难读，宁可由 fit 收缩
        val latex = "a + b + c + d + e + f"
        assertEquals(listOf(latex), MathWrap.splitForDisplay(latex, MathWrap.MIN_SPLIT_CHARS - 1))
    }

    @Test
    fun alignedEnvironmentSplitsByRow() {
        val latex = "\\begin{aligned}f(x) &= a x^2 + b x + c \\\\ g(x) &= d x^2 + e x + f\\end{aligned}"
        val rows = MathWrap.splitForDisplay(latex, 40)
        assertEquals(2, rows.size)
        assertTrue(rows[0].startsWith("\\begin{aligned}"))
        assertTrue(rows[0].endsWith("\\end{aligned}"))
        assertTrue(rows[0].contains("f(x) &= a x^2 + b x + c"))
        assertTrue(rows[1].contains("g(x) &= d x^2 + e x + f"))
        // 每行都必须是完整可渲染的环境，不能是裸行体
        assertTrue(rows.all { it.startsWith("\\begin{aligned}") && it.endsWith("\\end{aligned}") })
    }

    @Test
    fun nestedMatrixInsideEnvIsNotSplit() {
        // 嵌套矩阵内部的 `\\` 是矩阵的行，不是环境的行——绝不能拆
        val latex =
            "\\begin{cases}A = \\begin{pmatrix}1 & 2 \\\\ 3 & 4\\end{pmatrix} \\\\ B = 5\\end{cases}"
        val rows = MathWrap.splitForDisplay(latex, 40)
        assertEquals(2, rows.size)
        assertTrue("嵌套矩阵应完整留在第一行", rows[0].contains("\\begin{pmatrix}1 & 2 \\\\ 3 & 4\\end{pmatrix}"))
        assertTrue(rows[1].contains("B = 5"))
    }

    @Test
    fun singleRowEnvFallsBackToGreedySplit() {
        // 环境只有一行且超长：按普通公式在顶层断点贪心拆
        val latex = "\\begin{aligned}f(x) = a_{1} x^{2n} + a_{2} x^{2n-1} + a_{3} x^{2n-2} + a_{4} x^{2n-3}\\end{aligned}"
        val rows = MathWrap.splitForDisplay(latex, 40)
        assertTrue("应拆成多行", rows.size > 1)
        assertTrue(rows.all { it.isNotBlank() })
    }

    @Test
    fun longSumSplitsAtTopLevelPlus() {
        val latex = "S = a_1 + a_2 + a_3 + a_4 + a_5 + a_6 + a_7 + a_8 + a_9 + a_{10}"
        val rows = MathWrap.splitForDisplay(latex, 30)
        assertTrue(rows.size > 1)
        // 每一行都不超过预算太多（运算符留在行尾允许轻微超出）
        rows.forEach { assertTrue("行过长: $it", it.length <= 30 + 8) }
        // 运算符留在行尾
        assertTrue(rows.first().endsWith("+") || rows.first().endsWith("="))
        // 拼回去内容不丢
        assertEquals(latex.replace(" ", ""), rows.joinToString("").replace(" ", ""))
    }

    @Test
    fun neverSplitsInsideBracesOrBrackets() {
        // `\frac{...}{...}`、`\left(...\right)` 内部深度 > 0，任何顶层断点规则
        // 都不该作用到它们——内容要么整块留在一行，要么整体换行
        val latex = "y = \\frac{a + b + c + d + e + f + g + h + i + j + k}{m + n + p + q + r + s}"
        val rows = MathWrap.splitForDisplay(latex, 30)
        assertTrue(rows.size > 1)
        // 分子分母必须各自完整地待在某一行里，不允许撕开
        val joined = rows.joinToString(" ")
        assertTrue(joined.contains("a + b + c + d + e + f + g + h + i + j + k"))
        assertTrue(joined.contains("m + n + p + q + r + s"))
    }

    @Test
    fun relationCommandBreaksAsWholeToken() {
        val latex = "f(x) \\to g(x) \\to h(x) \\to k(x) \\to m(x) \\to n(x)"
        val rows = MathWrap.splitForDisplay(latex, 30)
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.isNotBlank() })
        // 命令不被从中间截断
        rows.forEach { assertTrue("命令被截断: $it", !it.contains("\\t") || it.contains("\\to")) }
        assertEquals(latex.replace(" ", ""), rows.joinToString("").replace(" ", ""))
    }

    @Test
    fun hardCutWhenNoBreakPoint() {
        // 一长串没有顶层运算符的 token（超长连乘/长串下标），只能硬切
        val latex = "a".repeat(100)
        val rows = MathWrap.splitForDisplay(latex, 40)
        assertTrue(rows.size > 1)
        rows.forEach { assertTrue(it.length <= 40) }
        assertEquals(latex, rows.joinToString(""))
    }

    @Test
    fun doubleBackslashIsBreakPoint() {
        val latex = "a = b + c \\\\ d = e + f + g + h + i + j + k + l + m + n"
        val rows = MathWrap.splitForDisplay(latex, 30)
        assertTrue(rows.size > 1)
        // 关键不是「行里没有 \\」——切点落在 \\ 之后时行尾带一个 \\
        // 完全无害（KaTeX 本来就把它渲染成换行）。要防的是把 \\ 劈成
        // 两半：任何行都不能以**单个**反斜杠结尾，那说明转义被撕开了。
        rows.forEach {
            assertTrue("反斜杠被撕坏: $it", !it.endsWith("\\") || it.endsWith("\\\\"))
        }
        // 内容不丢
        assertEquals(latex.replace(" ", ""), rows.joinToString("").replace(" ", ""))
    }

    @Test
    fun escapedBraceDoesNotCountAsDepth() {
        // `\{`、`\}` 是集合符号不是分组，不能计入括号深度，
        // 否则 depth 永远回不到 0、所有断点失效
        val latex = "\\{x \\mid x = a + b + c + d + e + f + g + h + i + j + k\\}"
        val rows = MathWrap.splitForDisplay(latex, 30)
        assertTrue("应在某处断行", rows.size > 1)
    }

    @Test
    fun repeatedSplitIsStable() {
        // 拆出来的短行再拆必须原样返回（幂等），保证渲染侧重复调用安全
        val latex = "S = a_1 + a_2 + a_3 + a_4 + a_5 + a_6 + a_7 + a_8 + a_9 + a_{10}"
        val once = MathWrap.splitForDisplay(latex, 30)
        val twice = once.flatMap { MathWrap.splitForDisplay(it, 30) }
        assertEquals(once, twice)
    }
}
