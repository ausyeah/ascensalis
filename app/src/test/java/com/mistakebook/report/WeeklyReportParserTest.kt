package com.mistakebook.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 周报输出解析（F2）的容错测试。
 */
class WeeklyReportParserTest {

    private val markdown =
        "## 本周概览\n本周新增 5 道错题，复习 12 次。\n## 错因画像\n计算失误偏多。\n" +
            "## 薄弱知识点\n数列、导数。\n## 下周建议\n每天过 10 道到期错题。"

    @Test
    fun `标准 JSON 提取 report_markdown`() {
        val raw = """{"report_markdown": "${markdown.replace("\n", "\\n")}"}"""
        assertEquals(markdown, WeeklyReportParser.parse(raw))
    }

    @Test
    fun `带围栏的 JSON 也能解`() {
        val raw = "```json\n{\"report_markdown\": \"${markdown.replace("\n", "\\n")}\"}\n```"
        assertEquals(markdown, WeeklyReportParser.parse(raw))
    }

    @Test
    fun `围栏外混废话截花括号`() {
        val raw = "好的，这是周报：\n{\"report_markdown\": \"${markdown.replace("\n", "\\n")}\"}\n祝好"
        assertEquals(markdown, WeeklyReportParser.parse(raw))
    }

    @Test
    fun `模型无视 JSON 直接吐 Markdown 时兜底收下`() {
        assertEquals(markdown, WeeklyReportParser.parse(markdown))
    }

    @Test
    fun `裸 Markdown 缺二级标题不收`() {
        assertNull(WeeklyReportParser.parse("一段足够长但没有标题结构的普通文本，" .repeat(10)))
    }

    @Test
    fun `太短的输出视为失败`() {
        assertNull(WeeklyReportParser.parse("""{"report_markdown": "## 短"}"""))
        assertNull(WeeklyReportParser.parse("## 短"))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(WeeklyReportParser.parse(""))
        assertNull(WeeklyReportParser.parse("   "))
    }
}
