package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarkdownTruncator 截断测试：重点钉「截断点必须避开数学区」——
 * 旧实现头尾各截一半会把 `$…$` 公式拦腰切断，交给渲染层产出残缺公式。
 */
class MarkdownTruncatorTest {

    @Test
    fun `不超长原样返回`() {
        val r = MarkdownTruncator.truncate("短文本", 100)
        assertFalse(r.truncated)
        assertEquals("短文本", r.text)
    }

    @Test
    fun `截断点不落在数学区内部`() {
        // headroom=600 恰好落在长公式行（549..851）内部：
        // 旧实现 take(600) 会把 `$…$` 拦腰切断；新实现收拢到公式行之前的行末
        val filler = "字".repeat(60) + "\n" // 61 字符
        val longFormula = "$" + "x".repeat(300) + "$" // 302 字符
        val markdown = filler.repeat(9) + longFormula + filler.repeat(30)
        val r = MarkdownTruncator.truncate(markdown, 1200)
        assertTrue(r.truncated)
        val separator = "……（内容过长已截断）……"
        val head = r.text.substringBefore(separator)
        val tail = r.text.substringAfter(separator)
        // 头尾两段的 `$` 计数都必须为偶数（公式没被拦腰切断）
        assertEquals(0, head.count { it == '$' } % 2)
        assertEquals(0, tail.count { it == '$' } % 2)
    }

    @Test
    fun `安全边界收拢到行末`() {
        val text = "abc\n\$def\$\nghi\n"
        // limit=5 落在 "$def$" 行内 → 收拢到它之前的行末（4）
        assertEquals(4, MarkdownTruncator.mathSafeBoundary(text, 5))
        // limit=9 同样落在该行内（行末是 10）→ 仍是 4
        assertEquals(4, MarkdownTruncator.mathSafeBoundary(text, 9))
        // limit=10 覆盖到 "$def$" 行末：该行 $ 成对、净翻转 0 → 行末即安全边界
        assertEquals(10, MarkdownTruncator.mathSafeBoundary(text, 10))
    }

    @Test
    fun `$$整体算一次翻转而不是逐字符`() {
        // "$$" 行：整体计数 = 1 次翻转（开启数学区、未闭合）→ 行末不安全；
        // 逐字符错误实现会翻两次（回到区外）→ 误把行末当安全边界
        val text = "前文\n\$\$\n后文\n"
        assertEquals(3, MarkdownTruncator.mathSafeBoundary(text, 8))
        // 覆盖 "$$" 与"后文"两行后仍未闭合 → 边界保持在前文行末
        assertEquals(3, MarkdownTruncator.mathSafeBoundary(text, 13))
    }
}
