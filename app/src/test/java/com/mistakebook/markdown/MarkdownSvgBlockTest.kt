package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **SVG 图形块识别**的回归测试。
 *
 * ## 这个 bug 的形状（v0.0.54 用户反馈）
 *
 * 「思考过程里的 SVG 能正常渲染，正式对话里却不能」——思考/正文走的是
 * 同一条 RichText 渲染路径，分叉不在渲染层，在**解析层**：
 * 解析器早先只认 ` ```svg ` 围栏，而聊天系统提示词并没有教模型这个约定
 * （那是错题识别提示词里的规矩）。于是模型在正文里直接吐裸 `<svg>` 标签
 * 时，整段 XML 掉进段落分支以源码显示；思考里恰好带了围栏，看起来就是
 * 「思考能渲染、正文不能」。
 *
 * 修法：解析层把三种形态都收进 [MarkdownParser.Block.Svg]——
 * 围栏 svg、xml/html/空语言围栏里以 `<svg` 开头的内容、以及**不带任何
 * 围栏的裸 `<svg>…</svg>` 块**。
 */
class MarkdownSvgBlockTest {

    private fun parse(text: String) = MarkdownParser.parse(text)

    private fun svgBlocks(text: String) =
        parse(text).filterIsInstance<MarkdownParser.Block.Svg>()

    private fun codeBlocks(text: String) =
        parse(text).filterIsInstance<MarkdownParser.Block.Code>()

    private val svgBody = "<svg viewBox=\"0 0 400 300\">" +
        "<line x1=\"0\" y1=\"0\" x2=\"400\" y2=\"300\" stroke=\"black\"/></svg>"

    // ----------------------------------------------- 裸 <svg> 块（本次修的核心）

    @Test
    fun `裸 svg 块不需要围栏也能识别为图形`() {
        val blocks = svgBlocks("如下图所示：\n$svgBody\n这就是受力分析。")
        assertEquals("应产出一个 SVG 块", 1, blocks.size)
        assertTrue(blocks[0].source.contains("<svg"))
    }

    @Test
    fun `裸 svg 多行展开完整收集到闭合标签`() {
        val text = "<svg viewBox=\"0 0 400 300\">\n" +
            "  <circle cx=\"100\" cy=\"100\" r=\"50\"/>\n" +
            "</svg>"
        val blocks = svgBlocks(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].source.contains("</svg>"))
        // 清洗器应补上 xmlns（AndroidSVG 没有命名空间会解析失败）
        assertTrue(blocks[0].source.contains("xmlns"))
    }

    @Test
    fun `闭合标签同一行剩余正文不丢失`() {
        val blocks = parse("$svgBody 后面还有说明文字")
        assertEquals(1, svgBlocks("$svgBody 后面还有说明文字").size)
        val paragraphs = blocks.filterIsInstance<MarkdownParser.Block.Paragraph>()
        assertTrue(paragraphs.any { it.text.contains("后面还有说明文字") })
    }

    @Test
    fun `流式尾端未闭合的裸 svg 也出块`() {
        val blocks = svgBlocks("<svg viewBox=\"0 0 400 300\">\n<line x1=\"0\" y1=\"0\"")
        assertEquals("未闭合也要先占位（与未闭合 \$\$ 同一策略）", 1, blocks.size)
    }

    // ----------------------------------------------- 围栏形态

    @Test
    fun `svg 语言标记围栏照常识别`() {
        val blocks = svgBlocks("```svg\n$svgBody\n```")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `空语言围栏里以 svg 开头的内容按图形走`() {
        val blocks = svgBlocks("```\n$svgBody\n```")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `xml 围栏里以 svg 开头的内容按图形走`() {
        val blocks = svgBlocks("```xml\n$svgBody\n```")
        assertEquals(1, blocks.size)
    }

    @Test
    fun `非 svg 语言的围栏不误判成图形`() {
        val text = "```python\nprint('<svg>not a figure</svg>')\n```"
        assertEquals("python 代码块仍是代码", 0, svgBlocks(text).size)
        assertEquals(1, codeBlocks(text).size)
    }

    @Test
    fun `空语言围栏里普通代码仍是代码`() {
        val text = "```\njust some text\n```"
        assertEquals(0, svgBlocks(text).size)
        assertEquals(1, codeBlocks(text).size)
    }
}
