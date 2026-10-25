package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 路线单测：安全清洗 + 宽高比 + Markdown 围栏分流。
 *
 * 安全清洗必须测：模型输出是不可信输入，过滤逻辑写错一个正则，
 * 就是「图出不来」或者「脚本执行了」——两头都不能接受。
 */
class SvgSanitizerTest {

    private val triangle = """
        <svg viewBox="0 0 400 300">
          <line x1="50" y1="250" x2="350" y2="250" stroke="#333"/>
          <text x="200" y="270" font-size="14">AB</text>
        </svg>
    """.trimIndent()

    @Test
    fun `script 整段被删`() {
        val dirty = """<svg><script>alert(1)</script><line stroke="#333"/></svg>"""
        val clean = SvgSanitizer.sanitize(dirty)
        assertFalse(clean.contains("script", ignoreCase = true))
        assertTrue(clean.contains("<line"))
    }

    @Test
    fun `foreignObject 整段被删`() {
        val dirty = """<svg><foreignObject><div onclick="x">hi</div></foreignObject><line/></svg>"""
        val clean = SvgSanitizer.sanitize(dirty)
        assertFalse(clean.contains("foreignObject", ignoreCase = true))
        assertFalse(clean.contains("<div"))
        assertTrue(clean.contains("<line"))
    }

    @Test
    fun `外链 image 元素被删`() {
        val dirty = """<svg><image href="https://evil.com/t.png"/><line/></svg>"""
        val clean = SvgSanitizer.sanitize(dirty)
        assertFalse(clean.contains("evil.com"))
        assertTrue(clean.contains("<line"))
    }

    @Test
    fun `事件属性被删`() {
        val dirty = """<svg><line onclick="alert(1)" onload="x()" stroke="#333"/></svg>"""
        val clean = SvgSanitizer.sanitize(dirty)
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("onload"))
        // 正常属性要留住
        assertTrue(clean.contains("stroke"))
    }

    @Test
    fun `javascript 伪协议被清空而不是删元素`() {
        val dirty = """<svg><a href="javascript:alert(1)"><line stroke="#333"/></a></svg>"""
        val clean = SvgSanitizer.sanitize(dirty)
        assertFalse(clean.contains("javascript:"))
        assertTrue(clean.contains("<line"))
    }

    @Test
    fun `缺 xmlns 时补上`() {
        // 模型输出经常省 xmlns，而 AndroidSVG 没命名空间会直接解析失败
        val clean = SvgSanitizer.sanitize(triangle)
        assertTrue(clean.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        // 补在 svg 开标签上，不破坏其它内容
        assertTrue(clean.contains("viewBox"))
    }

    @Test
    fun `已有 xmlns 不重复添加`() {
        val withNs = triangle.replace("<svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"")
        val clean = SvgSanitizer.sanitize(withNs)
        assertEquals(1, Regex("xmlns\\s*=").findAll(clean).count())
    }

    @Test
    fun `空白输入返回空串`() {
        assertEquals("", SvgSanitizer.sanitize("   "))
    }

    // ---- svgAspectRatio ----

    @Test
    fun `viewBox 宽高比取后两个数`() {
        // viewBox = min-x min-y width height：宽高是**后两个**，不是前两个
        assertEquals(400f / 300f, svgAspectRatio("""<svg viewBox="0 0 400 300">""")!!, 0.001f)
    }

    @Test
    fun `viewBox 用逗号分隔也能解析`() {
        assertEquals(2f, svgAspectRatio("""<svg viewBox='0,0,600,300'>""")!!, 0.001f)
    }

    @Test
    fun `无 viewBox 或格式错时返回 null`() {
        assertNull(svgAspectRatio("<svg width=\"400\"></svg>"))
        assertNull(svgAspectRatio("""<svg viewBox="0 0">"""))
        assertNull(svgAspectRatio("not svg at all"))
    }

    // ---- MarkdownParser 围栏分流 ----

    @Test
    fun `svg 围栏解析成 Svg 块`() {
        val blocks = MarkdownParser.parse("```svg\n$triangle\n```")
        val svg = blocks.filterIsInstance<MarkdownParser.Block.Svg>()
        assertEquals(1, svg.size)
        assertTrue(svg.first().source.contains("<line"))
        // 清洗在解析时就做了：渲染层拿到的一定是干净 SVG
        assertTrue(svg.first().source.contains("xmlns"))
    }

    @Test
    fun `普通代码块仍是 Code 块且带语言标记`() {
        val blocks = MarkdownParser.parse("```python\nprint(1)\n```")
        val code = blocks.filterIsInstance<MarkdownParser.Block.Code>()
        assertEquals(1, code.size)
        assertEquals("python", code.first().language)
        assertEquals("print(1)", code.first().text)
        // 不能被误判成 SVG
        assertTrue(blocks.none { it is MarkdownParser.Block.Svg })
    }

    @Test
    fun `无语言标记的代码块 language 为空串`() {
        val blocks = MarkdownParser.parse("```\nplain\n```")
        val code = blocks.filterIsInstance<MarkdownParser.Block.Code>()
        assertEquals(1, code.size)
        assertEquals("", code.first().language)
    }

    @Test
    fun `流式未闭合的 svg 围栏也先渲染`() {
        // 生成期间围栏可能还没闭合，有内容就该出图而不是什么都不显示
        val blocks = MarkdownParser.parse("```svg\n<svg viewBox=\"0 0 400 300\"><line/>")
        assertTrue(blocks.any { it is MarkdownParser.Block.Svg })
    }
}
