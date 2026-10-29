package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LatexSanitizer] 回归测试。
 *
 * 每个 case 都对应一个**真机上观察到的坏输出**，不是凭空构造的边界值。
 * 改动清洗逻辑前先跑这个。
 *
 * 方法名用英文：中文 backtick 方法名在部分 JVM 环境下会导致初始化错误。
 */
class LatexSanitizerTest {

    @Test
    fun katexSupportedCommandsStayInWhitelist() {
        // v0.0.61 回归：这些命令 KaTeX 实测支持（assets/katex/katex.min.js
        // renderToString 全部通过），漏出白名单会把**合法公式降级成乱码**
        // （\cancel{2} → "cancel{2}"，字母直接印进公式），与历史 \vec 误删同类。
        val samples = listOf(
            "\\cancel{2}", "\\bcancel{2}", "\\sout{错误}",
            "x + \\overbrace{a+b}^{n}", "\\underbrace{a+b}_{n}",
            "\\tbinom{n}{k}", "\\mathopen( x \\mathclose)",
            "\\overleftrightarrow{AB}", "\\underbrace{x}_{\\text{原因}}"
        )
        samples.forEach { latex ->
            assertEquals("$latex 不应被清洗", latex, LatexSanitizer.dropUnknownCommands(latex))
        }
    }

    @Test
    fun unclosedBraceIsClosed() {
        // 真机案例：模型输出 F\left(\frac{y}{x} 后就断了
        assertEquals("\\frac{1}{2}", LatexSanitizer.balanceBraces("\\frac{1}{2"))
        assertEquals(
            "F\\left(\\frac{y}{x}",
            LatexSanitizer.balanceBraces("F\\left(\\frac{y}{x}")
        )
    }

    @Test
    fun balancedBraceUnchanged() {
        val input = "\\frac{a+b}{c-d}"
        assertEquals(input, LatexSanitizer.balanceBraces(input))
    }

    @Test
    fun extraClosingBraceIsTolerated() {
        // 不抛异常：多一个 } 时保留原样交给 KaTeX 自己处理
        val input = "\\frac{1}{2}}"
        assertEquals(input, LatexSanitizer.balanceBraces(input))
    }

    @Test
    fun unpairedLeftGetsRightDot() {
        // 真机案例：$F\left(\frac{y}{x},\frac{z}{x}\right) = 0$ 整条渲染失败
        val input = "F\\left(\\frac{y}{x}"
        val cleaned = LatexSanitizer.balanceDelimiters(input)
        assertTrue(cleaned.contains("\\right."))
        assertTrue(LatexSanitizer.looksRenderable(cleaned))
    }

    @Test
    fun pairedLeftRightUnchanged() {
        val input = "\\left( x \\right)"
        assertEquals(input, LatexSanitizer.balanceDelimiters(input))
    }

    @Test
    fun moreRightsThanLeftsUnchanged() {
        val input = "x \\right)"
        assertEquals(input, LatexSanitizer.balanceDelimiters(input))
    }

    @Test
    fun decorativeUnknownCommandDegradesToText() {
        // 装饰性命令：降级为正体文本，只是排版变朴素，含义不变。
        // 注意：\bm \degree 已实测 KaTeX 支持，移入白名单原样保留（见 accentCommandsPreserved），
        // 这里换成真正不认识的 \cellcolor 当代表。
        assertEquals("cellcolor{red}", LatexSanitizer.dropUnknownCommands("\\cellcolor{red}"))
    }

    @Test
    fun accentCommandsPreserved() {
        // 2026-10 行为变更：\bar \hat \vec 等重音命令实测 KaTeX 全部支持，
        // 旧版"直接删除"会把能渲染的公式改坏（向量箭头凭空消失）。
        // 现在原样保留。测试方法即实测方法：用仓库 assets/katex/katex.min.js
        // 在 node 里逐条 renderToString 验证过行内/块级都通过。
        assertEquals("\\bar{x}", LatexSanitizer.dropUnknownCommands("\\bar{x}"))
        assertEquals("\\hat{y}", LatexSanitizer.dropUnknownCommands("\\hat{y}"))
        assertEquals("\\vec v", LatexSanitizer.dropUnknownCommands("\\vec v"))
        assertEquals("\\degree", LatexSanitizer.dropUnknownCommands("\\degree"))
        assertEquals("\\bm{x}", LatexSanitizer.dropUnknownCommands("\\bm{x}"))
        assertEquals("\\angle ABC", LatexSanitizer.dropUnknownCommands("\\angle ABC"))
        assertEquals("30^\\circ", LatexSanitizer.dropUnknownCommands("30^\\circ"))
        assertEquals("\\sum\\limits_{i=1}^{n}", LatexSanitizer.dropUnknownCommands("\\sum\\limits_{i=1}^{n}"))
        assertEquals("a \\because b \\therefore c", LatexSanitizer.dropUnknownCommands("a \\because b \\therefore c"))
    }

    @Test
    fun accentPreservationKeepsSurroundingFormula() {
        // 重音命令保留后不能破坏整条公式的其他部分
        val cleaned = LatexSanitizer.dropUnknownCommands("F\\bar{x} + \\frac{1}{2}")
        assertTrue(cleaned.contains("\\bar{x}"))
        assertTrue(cleaned.contains("\\frac{1}{2}"))
        assertTrue(cleaned.contains("F"))
    }

    @Test
    fun standardCommandsFullyPreserved() {
        val input = "\\frac{\\sqrt{x}}{\\sum_{i=1}^{n} \\alpha_i}"
        assertEquals(input, LatexSanitizer.dropUnknownCommands(input))
    }

    @Test
    fun oddDollarCountIsStripped() {
        val dollar = "$"
        // 配对已断时全删，比留着当普通字符更干净
        assertEquals("x", LatexSanitizer.stripStrayDollar("x" + dollar))
        assertEquals("x", LatexSanitizer.stripStrayDollar(dollar + "x"))
    }

    @Test
    fun evenDollarCountUnchanged() {
        val input = "a" + "$" + "b" + "$" + "c"
        assertEquals(input, LatexSanitizer.stripStrayDollar(input))
    }

    @Test
    fun fullPipelineHandlesTypicalBrokenInput() {
        val broken = "F\\left(\\frac{y}{x},\\frac{z}{x}\\right) = 0"
        val cleaned = LatexSanitizer.clean(broken)
        assertTrue("should be renderable", LatexSanitizer.looksRenderable(cleaned))
    }

    @Test
    fun emptyInputDoesNotCrash() {
        assertEquals("", LatexSanitizer.clean(""))
        assertEquals("   ", LatexSanitizer.clean("   "))
        assertFalse(LatexSanitizer.looksRenderable(""))
    }

    @Test
    fun mixedCommandsHandledByTheirOwnRule() {
        // 未知的降级保留字母；白名单内命令原样保留
        val cleaned = LatexSanitizer.dropUnknownCommands("\\foo \\bar{x}")
        assertTrue("未知命令降级保留文字", cleaned.contains("foo"))
        assertTrue("白名单命令原样保留", cleaned.contains("\\bar{x}"))
        assertTrue("上一符号本身保留", cleaned.contains("x"))
    }

    // ===== rescueUnsupported：把必然失败的写法改成等价可渲染形式 =====
    //
    // 以下每条失败模式都用仓库 katex.min.js 实测复现过（node 跑 renderToString），
    // 真机聊天里的红色源码主要就来自这几个来源。

    @Test
    fun labelIsStripped() {
        // \label KaTeX 不支持（Undefined control sequence），且是纯元数据，删除不改变含义。
        // 直接调 rescue 会留下双空格（压缩发生在 dropUnknownCommands），断言按实际行为写；
        // 走完整 clean() 的效果见 fullPipelineRescuesTypicalChatOutput。
        assertEquals("a  = b", LatexSanitizer.rescueUnsupported("a \\label{eq:1} = b"))
        assertEquals("a = b", LatexSanitizer.clean("a \\label{eq:1} = b"))
        assertEquals("a = b", LatexSanitizer.clean("a \\nonumber = b"))
        assertEquals("a = b", LatexSanitizer.clean("a \\notag = b"))
    }

    @Test
    fun chemistryDegradesToText() {
        // \ce 依赖 mhchem 扩展，本包 KaTeX 未带；降级为 \text 保持内容可读
        assertEquals("\\text{H2O}", LatexSanitizer.rescueUnsupported("\\ce{H2O}"))
    }

    @Test
    fun unsupportedEnvironmentRenamed() {
        // multline → gathered（逐行排）；flalign → aligned（按 & 对齐），行语义不变
        assertEquals(
            "\\begin{gathered} a \\\\ b \\end{gathered}",
            LatexSanitizer.rescueUnsupported("\\begin{multline} a \\\\ b \\end{multline}")
        )
        assertEquals(
            "\\begin{aligned} a &= 1 \\end{aligned}",
            LatexSanitizer.rescueUnsupported("\\begin{flalign} a &= 1 \\end{flalign}")
        )
        assertEquals(
            "\\begin{aligned*} a \\end{aligned*}",
            LatexSanitizer.rescueUnsupported("\\begin{flalign*} a \\end{flalign*}")
        )
    }

    @Test
    fun strayAmpersandOutsideEnvironmentRemoved() {
        // 真机最高频失败：模型写 a &= b 但没有 \begin 包裹 → KaTeX 整条报错。
        // & 在环境外没有数学含义，删掉后公式照常渲染。
        assertEquals("a = b", LatexSanitizer.rescueUnsupported("a &= b"))
        // & 前后都有空格时删除会留双空格，压缩发生在 dropUnknownCommands；
        // 完整管线的最终效果用 clean() 断言
        assertEquals("x  = y", LatexSanitizer.rescueUnsupported("x & = y"))
        assertEquals("x = y", LatexSanitizer.clean("x & = y"))
    }

    @Test
    fun ampersandInsideEnvironmentKept() {
        // 环境内的 & 是对齐语义，必须保留
        val input = "\\begin{aligned} a &= 1 \\\\ b &= 2 \\end{aligned}"
        assertEquals(input, LatexSanitizer.rescueUnsupported(input))
        // 环境改名后同样含 \begin{，& 不受裸 & 清理影响
        val renamed = LatexSanitizer.rescueUnsupported("\\begin{flalign} a &= 1 \\end{flalign}")
        assertTrue(renamed.contains("&"))
    }

    @Test
    fun fullPipelineRescuesTypicalChatOutput() {
        // 端到端：label + 裸 & + 重音命令一次清洗后应完整保留可渲染结构
        val cleaned = LatexSanitizer.clean("v &= \\vec{F}/m \\label{eq:2}")
        assertTrue(cleaned.contains("\\vec"))
        assertTrue(cleaned.contains("= "))
        assertFalse(cleaned.contains("&"))
        assertFalse(cleaned.contains("label"))
        assertTrue("should be renderable", LatexSanitizer.looksRenderable(cleaned))
    }
}
