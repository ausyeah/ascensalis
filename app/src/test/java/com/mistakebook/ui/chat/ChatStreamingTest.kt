package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamingTest {

    // ---- trimIncompleteTrailingFormula：流式公式门控 ----

    @Test
    fun plainTextWithoutDollarIsUnchanged() {
        assertEquals("你好，世界。", trimIncompleteTrailingFormula("你好，世界。"))
    }

    @Test
    fun completedInlineFormulaIsKept() {
        val text = "解是 \$x=1\$。"
        assertEquals(text, trimIncompleteTrailingFormula(text))
    }

    @Test
    fun trailingIncompleteInlineFormulaIsTrimmed() {
        assertEquals("解是 ", trimIncompleteTrailingFormula("解是 \$x=1"))
    }

    @Test
    fun trailingIncompleteDisplayFormulaIsTrimmed() {
        assertEquals("推导：", trimIncompleteTrailingFormula("推导：\$\$x^2+"))
    }

    @Test
    fun completedFormulaBeforeIncompleteTailKeepsBothSides() {
        assertEquals(
            "前半 \$a\$ 后半 ",
            trimIncompleteTrailingFormula("前半 \$a\$ 后半 \$b+")
        )
    }

    @Test
    fun dollarInsideCompletedDisplayFormulaDoesNotTriggerTrim() {
        // 块级 `$$` 配对只找 `$$`，内容里的单 `$` 不会干扰
        val text = "推导：\$\$x + \$y\$\$ 结束"
        assertEquals(text, trimIncompleteTrailingFormula(text))
    }

    @Test
    fun trimPointReturnsCompletePrefix() {
        val trimmed = trimIncompleteTrailingFormula("已经说了很多 \$尾巴还没写完")
        assertTrue(trimmed.endsWith("很多 "))
        assertEquals(0, trimmed.count { it == '$' })
    }

    @Test
    fun emptyAndBlankTextAreUnchanged() {
        assertEquals("", trimIncompleteTrailingFormula(""))
        assertEquals("   ", trimIncompleteTrailingFormula("   "))
    }

    // ---- LaTeX 标准定界符（v0.0.24） ----

    @Test
    fun completedLatexInlineFormulaIsKept() {
        val text = "解是 \\(x=1\\)。"
        assertEquals(text, trimIncompleteTrailingFormula(text))
    }

    @Test
    fun trailingIncompleteLatexInlineFormulaIsTrimmed() {
        assertEquals("解是 ", trimIncompleteTrailingFormula("解是 \\(x=1"))
    }

    @Test
    fun trailingIncompleteLatexDisplayFormulaIsTrimmed() {
        assertEquals("推导：", trimIncompleteTrailingFormula("推导：\\[x^2+"))
    }

    @Test
    fun completedLatexFormulaBeforeIncompleteTailKeepsBothSides() {
        assertEquals(
            "前半 \\(a\\) 后半 ",
            trimIncompleteTrailingFormula("前半 \\(a\\) 后半 \\(b+")
        )
    }

    @Test
    fun mixedDelimitersAllGateTheirOwnTail() {
        assertEquals(
            "a \$x\$ b \\(y\\) c ",
            trimIncompleteTrailingFormula("a \$x\$ b \\(y\\) c \\[z+")
        )
    }

    @Test
    fun loneBackslashInTextDoesNotTriggerTrim() {
        val text = "Windows 路径 C:\\dir\\file 正常显示"
        assertEquals(text, trimIncompleteTrailingFormula(text))
    }
}
