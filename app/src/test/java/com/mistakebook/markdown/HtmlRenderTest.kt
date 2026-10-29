package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML 围栏渲染 + SVG 抽取（v0.0.57）。
 *
 * 用户反馈「流式正常、结束显示源码」：根因是 ```html 围栏一直落到 Code 块。
 * 这里钉住三件事：HTML 被识别成 Html 块、危险内容被清洗、SVG 能从
 * 「解释文字 + 围栏」里被完整抠出来。
 */
class HtmlRenderTest {

    // ---- HTML 围栏解析 ----

    @Test
    fun `html 围栏解析成 Html 块而不是代码块`() {
        val md = """
            下面是对照表：

            ```html
            <table><tr><th>科目</th><th>分数</th></tr>
            <tr><td>数学</td><td>130</td></tr></table>
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        val html = blocks.filterIsInstance<MarkdownParser.Block.Html>()
        assertEquals(1, html.size)
        assertTrue(html[0].source.contains("<table"))
        // 不应再出现 Code 块
        assertTrue(blocks.none { it is MarkdownParser.Block.Code })
    }

    @Test
    fun `html 围栏里的 table 不被拆成原生表格块`() {
        val md = """
            ```html
            <div style="color:#333"><table><tr><td>甲</td></tr></table></div>
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertTrue(blocks.none { it is MarkdownParser.Block.Table })
        assertTrue(blocks.any { it is MarkdownParser.Block.Html })
    }

    @Test
    fun `html 围栏内容以 svg 开头时仍走 SVG 块`() {
        val md = """
            ```html
            <svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="20"/></svg>
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertTrue(blocks.any { it is MarkdownParser.Block.Svg })
        assertTrue(blocks.none { it is MarkdownParser.Block.Html })
    }

    @Test
    fun `普通代码块不受影响`() {
        val md = """
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        val code = blocks.filterIsInstance<MarkdownParser.Block.Code>()
        assertEquals(1, code.size)
        assertEquals("kotlin", code[0].language)
    }

    // ---- 清洗 ----

    @Test
    fun `清洗删除脚本 iframe 与事件属性`() {
        val dirty = """
            <div onclick="steal()">hi</div>
            <script>alert(1)</script>
            <iframe src="https://evil"></iframe>
            <img src="https://evil/x.png" alt="x">
        """.trimIndent()
        val clean = HtmlSanitizer.sanitize(dirty)
        assertFalse(clean.contains("<script", ignoreCase = true))
        assertFalse(clean.contains("<iframe", ignoreCase = true))
        assertFalse(clean.contains("onclick", ignoreCase = true))
        assertFalse(clean.contains("https://evil", ignoreCase = true))
        assertTrue(clean.contains("hi"))
    }

    @Test
    fun `清洗保留样式与表格`() {
        val html = """<style>.a{color:red}</style><table><tr><td>1</td></tr></table>"""
        val clean = HtmlSanitizer.sanitize(html)
        assertTrue(clean.contains("<style>"))
        assertTrue(clean.contains("<table>"))
    }

    @Test
    fun `清洗剥掉 html 外壳并保留 head 里的样式`() {
        val html = """<!DOCTYPE html><html><head><style>.x{}</style></head>
            <body><p>正文</p></body></html>"""
        val clean = HtmlSanitizer.sanitize(html)
        assertFalse(clean.contains("<html", ignoreCase = true))
        assertFalse(clean.contains("<body", ignoreCase = true))
        assertTrue(clean.contains("<style>"))
        assertTrue(clean.contains("正文"))
    }

    @Test
    fun `文档外壳带 viewport 与配色`() {
        val doc = HtmlDocument.wrap("<p>hi</p>", 0xFF112233.toInt(), 0xFFF5F0E8.toInt())
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("#112233"))
        assertTrue(doc.contains("hi"))
    }

    // ---- SVG 抽取 ----

    @Test
    fun `从解释文字与围栏中抽出 svg`() {
        val raw = """
            下面是示意图：
            ```svg
            <svg viewBox="0 0 100 100"><line x1="0" y1="0" x2="10" y2="10"/></svg>
            ```
            希望有帮助。
        """.trimIndent()
        val svg = extractSvgDocument(raw)
        assertNotNull(svg)
        assertTrue(svg!!.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertFalse(svg.contains("下面是示意图"))
    }

    @Test
    fun `嵌套 svg 按配对深度取最外层`() {
        val raw = "<svg viewBox=\"0 0 10 10\"><svg><rect/></svg><circle/></svg>"
        val svg = extractSvgDocument(raw)!!
        assertTrue(svg.contains("<circle"))
        assertTrue(svg.trim().endsWith("</svg>"))
    }

    @Test
    fun `没有 svg 时返回 null`() {
        assertNull(extractSvgDocument("just text"))
        assertNull(extractSvgDocument(""))
    }

    @Test
    fun `svg 宽高比支持 viewBox 与 width height`() {
        assertEquals(400f / 300f, svgAspectRatio("<svg viewBox=\"0 0 400 300\"></svg>")!!, 0.001f)
        assertEquals(2f, svgAspectRatio("<svg width=\"200\" height=\"100\"></svg>")!!, 0.001f)
        assertNull(svgAspectRatio("<svg></svg>"))
    }
}
