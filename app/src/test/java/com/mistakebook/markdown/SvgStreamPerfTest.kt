package com.mistakebook.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.0.54 后续优化 · Task B：SVG 流式未闭合状态的重解析开销。
 *
 * 两处优化 + 其安全/一致性保证：
 * 1. [SvgSanitizer] 按 source 缓存清洗结果（纯函数）——堵住**闭合后**每个 chunk 的重复全量清洗；
 * 2. [MarkdownParser] 对**超长未闭合** SVG 先不产出（降级占位）——堵住**闭合前**每个 chunk 的 O(n²)。
 *
 * 计数器（[SvgSanitizer.sanitizeCallCount] / [SvgSanitizer.sanitizeCharCount]）仅供本测试断言
 * 「清洗工作量有上界」，每次用例前 [SvgSanitizer.resetForTest] 清零。
 *
 * 用 [MarkdownParser.parse]（非 parseCached）驱动，避免解析缓存把重复清洗掩盖掉。
 */
class SvgStreamPerfTest {

    /** 造一段含 N 条 <line> 的 SVG；每条约 55 字符，800 条 ≈ 44KB（超过 32KB 上限）。 */
    private fun bigSvg(lines: Int): String {
        val sb = StringBuilder("<svg viewBox=\"0 0 400 300\">")
        for (i in 1..lines) sb.append("<line x1=\"$i\" y1=\"0\" x2=\"$i\" y2=\"300\" stroke=\"#333\"/>")
        sb.append("</svg>")
        return sb.toString()
    }

    private fun svgBlocks(text: String) =
        MarkdownParser.parse(text).filterIsInstance<MarkdownParser.Block.Svg>()

    // ---------------------------------------------------------------- 缓存：闭合后不再重复清洗

    @Test
    fun `sanitize 纯函数结果按 source 缓存`() {
        SvgSanitizer.resetForTest()
        val s = "<svg viewBox=\"0 0 10 10\"><circle r=\"1\" onclick=\"x()\"/></svg>"
        val a = SvgSanitizer.sanitize(s)
        val b = SvgSanitizer.sanitize(s)
        assertEquals("纯函数：同输入同输出", a, b)
        assertFalse(a.contains("onclick"))
        assertEquals("进入 sanitize 两次", 2, SvgSanitizer.sanitizeCallCount)
        assertEquals("但字符只过一遍正则（第二次命中缓存）", s.length.toLong(), SvgSanitizer.sanitizeCharCount)
    }

    @Test
    fun `闭合后每 chunk 追加正文_闭合 svg 只清洗一次`() {
        SvgSanitizer.resetForTest()
        val svg = "<svg viewBox=\"0 0 400 300\">" +
            (1..100).joinToString("") { "<line x1=\"$it\" y1=\"0\" x2=\"$it\" y2=\"300\"/>" } +
            "</svg>"
        val closed = "```svg\n$svg\n```"
        // 第一个 chunk：svg 已闭合 → 全量清洗一次
        MarkdownParser.parse(closed)
        val afterFirst = SvgSanitizer.sanitizeCharCount
        assertTrue("首次应清洗过这段 svg", afterFirst >= svg.length)
        // 之后 50 个 chunk 只在 svg 后面追加无关正文，svg 源码不变 → 全部命中缓存
        repeat(50) { i -> MarkdownParser.parse(closed + "\n\n追加说明第 $i 段，与上图无关。") }
        assertEquals("闭合 svg 不应被重复全量清洗（应命中缓存）", afterFirst, SvgSanitizer.sanitizeCharCount)
    }

    // ---------------------------------------------------------------- 降级：超长未闭合不重复清洗

    @Test
    fun `超长未闭合 svg 流式期间不产出_闭合后恢复`() {
        SvgSanitizer.resetForTest()
        val svg = bigSvg(800)
        assertTrue("测试 svg 必须超过 32KB 上限", svg.length > 32 * 1024)

        var emitted = 0
        val step = svg.length / 30 + 1
        var i = step
        while (i < svg.length) {
            emitted += svgBlocks("```svg\n" + svg.substring(0, i)).size
            i += step
        }
        assertEquals("超长未闭合 svg 流式期间不应产出图形块（降级占位）", 0, emitted)
        assertEquals("未闭合阶段不应清洗这段 svg", 0L, SvgSanitizer.sanitizeCharCount)

        // 闭合后恢复：整段清洗产出完整图
        val closed = svgBlocks("```svg\n$svg\n```")
        assertEquals(1, closed.size)
        assertTrue(closed[0].source.contains("</svg>"))
        assertTrue(closed[0].source.contains("xmlns"))
    }

    @Test
    fun `裸 svg 未闭合超长也不产出_闭合后恢复`() {
        SvgSanitizer.resetForTest()
        val svg = bigSvg(800)
        val unclosed = svg.substring(0, svg.length - 10) // 去掉 </svg> 及尾巴，保持未闭合
        assertTrue(unclosed.length > 32 * 1024)
        assertTrue(unclosed.startsWith("<svg"))
        assertEquals("裸 svg 超长未闭合不产出", 0, svgBlocks(unclosed).size)

        val closed = svgBlocks(svg)
        assertEquals(1, closed.size)
        assertTrue(closed[0].source.contains("</svg>"))
    }

    // ---------------------------------------------------------------- 行为保持：小 svg / 非 svg 代码

    @Test
    fun `小 svg 未闭合仍照旧有内容就先渲染`() {
        SvgSanitizer.resetForTest()
        val svg = "<svg viewBox=\"0 0 400 300\"><line x1=\"0\" y1=\"0\" x2=\"400\" y2=\"300\"/>"
        val blocks = svgBlocks("```svg\n$svg")
        assertEquals("小 svg 未闭合应有内容就先渲染", 1, blocks.size)
        assertTrue(blocks[0].source.contains("<line"))
    }

    @Test
    fun `非 svg 代码块未闭合不受 svg 上限影响`() {
        val big = "x = " + (1..2000).joinToString("") { "$it + " } + "1"
        val blocks = MarkdownParser.parse("```python\n$big").filterIsInstance<MarkdownParser.Block.Code>()
        assertEquals("python 代码块未闭合仍照旧产出（不走 svg 降级）", 1, blocks.size)
        assertTrue(blocks[0].text.contains("x ="))
    }

    @Test
    fun `闭合时产出完整 svg_不因上限截断`() {
        val svg = bigSvg(800)
        val closed = svgBlocks("```svg\n$svg\n```")[0].source
        assertTrue("闭合路径不走降级，尾部 </svg> 必须在", closed.contains("</svg>"))
        assertTrue(closed.contains("<line"))
        assertTrue(closed.contains("xmlns"))
    }

    // ---------------------------------------------------------------- 安全底线

    @Test
    fun `流式各阶段危险内容都被清掉`() {
        SvgSanitizer.resetForTest()
        val dirty = "<svg viewBox=\"0 0 400 300\">" +
            "<script>alert(1)</script>" +
            "<line onclick=\"steal()\" onload=\"x\" stroke=\"#333\"/>" +
            "<image href=\"https://evil.example/t.png\"/>" +
            "<a href=\"javascript:alert(1)\"><circle r=\"5\"/></a>" +
            "<rect width=\"9\" height=\"9\"/>"
        val stages = listOf(
            "```svg\n" + dirty.substring(0, 20),          // 只有半个开标签
            "```svg\n" + dirty.substring(0, dirty.length / 2),
            "```svg\n" + dirty,                            // 未闭合（无 </svg>）
            "```svg\n" + dirty + "</svg>\n```"             // 闭合
        )
        stages.forEach { text ->
            svgBlocks(text).forEach { blk ->
                val s = blk.source
                assertFalse("阶段不应残留 script: ${text.take(30)}", s.contains("script", ignoreCase = true))
                assertFalse(s.contains("onclick"))
                assertFalse(s.contains("onload"))
                assertFalse(s.contains("evil.example"))
                assertFalse(s.contains("javascript:"))
            }
        }
        // 完整阶段的正常几何与 xmlns 补齐仍在
        val full = svgBlocks(stages[2])[0].source
        assertTrue(full.contains("<line"))
        assertTrue(full.contains("<rect"))
        assertTrue(full.contains("xmlns"))
    }
}
