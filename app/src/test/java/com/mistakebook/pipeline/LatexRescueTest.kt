package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **KaTeX 渲染失败模式**的回归测试（v0.0.50）。
 *
 * ## 这些用例从哪来
 *
 * 全部用仓库内打包的 `app/src/main/assets/katex/katex.min.js`（KaTeX 0.16.11）
 * 在 Node 里实测过：先确认原始输入会抛 ParseError，再确认清洗后不再抛。
 *
 * 真机反馈的三类现象，逐条对应：
 * 1. 「模型输出 LaTeX 不渲染」——`\left{` 裸花括号 / align 系环境 / 环境外 `&`；
 * 2. 「表格里的公式不渲染」——表格单元格降级成纯文本（另见 TableCellSpansTest）；
 * 3. 「黑体里面的内容没渲染出来」——模型把正文混进数学块，
 *    KaTeX 报「Can't use function '$' in math mode」。
 */
class LatexRescueTest {

    // ------------------------------------- \left{ / \right} 裸花括号（截图 1）

    @Test
    fun `裸花括号定界符补反斜杠`() {
        val cleaned = LatexSanitizer.clean("\\left{ x \\right}")
        assertTrue("应补成 \\left\\{：$cleaned", cleaned.contains("\\left\\{"))
        assertTrue("应补成 \\right\\}：$cleaned", cleaned.contains("\\right\\}"))
    }

    @Test
    fun `已经转义的定界符不被二次处理`() {
        val cleaned = LatexSanitizer.clean("\\left\\{ x \\right\\}")
        assertEquals("\\left\\{ x \\right\\}", cleaned)
    }

    @Test
    fun `积分区间里的裸花括号`() {
        val cleaned = LatexSanitizer.clean("\\int_0^\\pi \\left{t^2 \\sin t \\right}")
        assertTrue(cleaned.contains("\\left\\{"))
        assertTrue(cleaned.contains("\\right\\}"))
    }

    // --------------------------------- align 系环境（行内模式下 KaTeX 拒绝）

    @Test
    fun `align 环境改写成 aligned`() {
        val cleaned = LatexSanitizer.clean("\\begin{align} a &= b \\end{align}")
        assertTrue("应改名为 aligned：$cleaned", cleaned.contains("\\begin{aligned}"))
        assertTrue(cleaned.contains("\\end{aligned}"))
    }

    @Test
    fun `align 星号环境改写成 aligned`() {
        val cleaned = LatexSanitizer.clean("\\begin{align*} a &= b \\end{align*}")
        assertTrue(cleaned.contains("\\begin{aligned}"))
        assertFalse("不该残留 align*：$cleaned", cleaned.contains("align*"))
    }

    @Test
    fun `alignat 环境改写并吃掉列数参数`() {
        val cleaned = LatexSanitizer.clean("\\begin{alignat}{2} a &= b \\end{alignat}")
        assertTrue(cleaned.contains("\\begin{aligned}"))
        assertFalse("列数参数应被吃掉：$cleaned", cleaned.contains("{2}"))
    }

    @Test
    fun `split 环境改写成 aligned`() {
        val cleaned = LatexSanitizer.clean("\\begin{split} a &= b \\end{split}")
        assertTrue(cleaned.contains("\\begin{aligned}"))
    }

    // ------------------------------------------------- 环境外裸 &

    @Test
    fun `环境外的对齐符删除`() {
        assertEquals("a = b", LatexSanitizer.clean("a &= b"))
    }

    @Test
    fun `环境内的对齐符保留`() {
        val cleaned = LatexSanitizer.clean("\\begin{aligned} a &= b \\end{aligned}")
        assertTrue("环境内的 & 必须保留：$cleaned", cleaned.contains("&"))
    }

    // ------------------------------------- 数学模式里的裸 $（截图 2 / 黑体）

    @Test
    fun `数学块里的裸美元符号被删除`() {
        // KaTeX 原文报：Can't use function '$' in math mode
        val cleaned = LatexSanitizer.clean("由 ${'$'}V_y = 10 V_x${'$'} 求 ${'$'}a${'$'}")
        assertFalse("不该残留裸 $：$cleaned", cleaned.contains('$'))
        assertTrue("内容要保住：$cleaned", cleaned.contains("V_y"))
    }

    @Test
    fun `text 里的嵌套数学被重排成文本与数学交替`() {
        // KaTeX 在 \text{} 里把 $ 当普通字符印出来，界面就会出现「带字面 $ 的乱码」。
        val cleaned = LatexSanitizer.clean("\\text{由 ${'$'}V_y = 10 V_x${'$'} 求 }a")
        assertFalse("text 内的 $ 应被拆掉：$cleaned", cleaned.contains('$'))
        assertTrue("数学部分保留：$cleaned", cleaned.contains("V_y = 10 V_x"))
        assertTrue("文本部分保留：$cleaned", cleaned.contains("由"))
    }

    @Test
    fun `text 里的嵌套数学重排后仍是合法 LaTeX`() {
        val cleaned = LatexSanitizer.clean("\\text{由 ${'$'}V_y = 10 V_x${'$'} 求 }a")
        // 花括号必须成对
        var depth = 0
        cleaned.forEach {
            when (it) {
                '{' -> depth++
                '}' -> depth--
            }
            assertTrue("花括号不能为负：$cleaned", depth >= 0)
        }
        assertEquals("花括号必须配平：$cleaned", 0, depth)
    }

    // ------------------------------------------------------ 其它不支持写法

    @Test
    fun `tabular 环境改写成 array`() {
        val cleaned = LatexSanitizer.clean("\\begin{tabular}{cc} a & b \\end{tabular}")
        assertTrue(cleaned.contains("\\begin{array}"))
        assertFalse(cleaned.contains("tabular"))
    }

    @Test
    fun `multirow 整段剥除`() {
        val cleaned = LatexSanitizer.clean("\\multirow{2}{*}{x}")
        assertFalse("不该残留 multirow：$cleaned", cleaned.contains("multirow"))
    }

    @Test
    fun `tag 编号被删除`() {
        val cleaned = LatexSanitizer.clean("\\tag{1} x = 1")
        assertFalse(cleaned.contains("tag"))
    }

    // --------------------------------- 实测支持的命令不许被降级（提示词放开）

    @Test
    fun `Big 与 boxed 保持原样`() {
        // 提示词曾把它们列为「禁用」，实测 KaTeX 全部支持——
        // 降级反而把能渲染的公式改坏了。
        assertEquals("\\Big| x \\Big|", LatexSanitizer.clean("\\Big| x \\Big|"))
        assertEquals("\\boxed{x=1}", LatexSanitizer.clean("\\boxed{x=1}"))
    }

    @Test
    fun `空格命令保持原样`() {
        val cleaned = LatexSanitizer.clean("a\\,b\\;c\\!d")
        assertEquals("a\\,b\\;c\\!d", cleaned)
    }

    @Test
    fun `dfrac 与 boldsymbol 保持原样`() {
        assertEquals("\\dfrac{1}{2}", LatexSanitizer.clean("\\dfrac{1}{2}"))
        assertEquals("\\boldsymbol{x}", LatexSanitizer.clean("\\boldsymbol{x}"))
    }

    @Test
    fun `绝对值与范数命令保持原样`() {
        assertEquals("\\lVert x \\rVert", LatexSanitizer.clean("\\lVert x \\rVert"))
        assertEquals("\\lvert x \\rvert", LatexSanitizer.clean("\\lvert x \\rvert"))
    }

    @Test
    fun `coloneqq 与 iff 保持原样`() {
        assertEquals("\\coloneqq", LatexSanitizer.clean("\\coloneqq"))
        assertEquals("\\iff", LatexSanitizer.clean("\\iff"))
    }

    @Test
    fun `text 中文保持原样`() {
        assertEquals("\\text{中文}", LatexSanitizer.clean("\\text{中文}"))
    }

    // ------------------------------------------------------ 纯函数自检

    @Test
    fun `空输入不炸`() {
        assertEquals("", LatexSanitizer.clean(""))
        assertEquals("   ", LatexSanitizer.clean("   "))
    }

    @Test
    fun `清洗结果花括号配平`() {
        listOf(
            "\\left{ x \\right}",
            "\\text{由 ${'$'}V_y${'$'} 求 }a",
            "\\begin{tabular}{cc} a & b \\end{tabular}",
            "\\frac{1}{2",
        ).forEach { input ->
            var depth = 0
            LatexSanitizer.clean(input).forEach {
                when (it) {
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            assertEquals("输入：$input", 0, depth)
        }
    }
}
