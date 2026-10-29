package com.mistakebook.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 断行算法的回归测试（v0.0.59）。
 *
 * 防的是「折行失效」：旧实现 flush 后仍往已提交的旧行列表追加，
 * 第一个换行点之后的文字全部挤进第一行并被页边裁掉，新行永远为空。
 * 算法抽成纯函数后，这里用录制型 sink 在 JVM 上穷举正反例。
 */
class TextWrapTest {

    /** 每个字符宽 1 的确定性测量，行宽直接用字数推。 */
    private val measureByCount: (String) -> Float = { it.length.toFloat() }

    /** 录制型 sink：append/flush 全记下来供断言。 */
    private class RecordingSink : PdfComposeSink {
        val lines = mutableListOf<MutableList<Pair<String, Float>>>()
        private val items = mutableListOf<Pair<String, Float>>()
        override var currentLineWidth = 0f
        override val hasContent: Boolean get() = items.isNotEmpty()
        override fun append(text: String, width: Float, fontSize: Float, color: Int) {
            items.add(text to width)
            currentLineWidth += width
        }

        override fun flushLine() {
            if (items.isEmpty()) return
            lines.add(items.toMutableList())
            items.clear()
            currentLineWidth = 0f
        }
    }

    private fun RecordingSink.lineWidths() = lines.map { line -> line.sumOf { it.second.toDouble() } }
    private fun RecordingSink.totalChars() = lines.sumOf { line -> line.sumOf { it.first.length } }

    @Test
    fun `超一行宽的中文题干折成多行且每行不超宽`() {
        val sink = RecordingSink()
        PdfTextWrap.appendText(sink, "题".repeat(100), 12f, 1, 36f, measureByCount)
        sink.flushLine()
        assertEquals(3, sink.lines.size) // 36 + 36 + 28
        assertTrue(sink.lineWidths().all { it <= 36.0 })
        assertEquals(100, sink.totalChars())
    }

    @Test
    fun `第一个换行点之后的内容落到新行而不是挤回第一行`() {
        // 直接复现旧 bug 的场景：40 字中文、行宽 36 → 必须是 36+4 两行；
        // 旧行为是第一行 40 字溢出、第二行空（flushLine 对空行直接 return）。
        val sink = RecordingSink()
        PdfTextWrap.appendText(sink, "甲".repeat(40), 12f, 1, 36f, measureByCount)
        sink.flushLine()
        assertEquals(2, sink.lines.size)
        assertTrue("第二行必须非空", sink.lines[1].isNotEmpty())
        assertEquals(36, sink.lines[0].sumOf { it.first.length })
        assertEquals(4, sink.lines[1].sumOf { it.first.length })
    }

    @Test
    fun `西文单词不断开且超宽单词独占一行`() {
        val sink = RecordingSink()
        PdfTextWrap.appendText(sink, "short superlongword", 12f, 1, 10f, measureByCount)
        sink.flushLine()
        // "short " 占 6，superlongword(13) 放不下 → 整词换行独占一行（不可再切）
        assertEquals(2, sink.lines.size)
        assertEquals("short ", sink.lines[0].joinToString("") { it.first })
        assertEquals("superlongword", sink.lines[1].joinToString("") { it.first })
    }

    @Test
    fun `中英混排断行保持词与字边界`() {
        val sink = RecordingSink()
        PdfTextWrap.appendText(sink, "已知ab和cd两数", 12f, 1, 6f, measureByCount)
        sink.flushLine()
        // 逐字推演（每字符宽 1，行宽 6）：「已知ab和」占 5，接「cd」超宽 → 换行；
        // 「cd」作为完整词不被拆开，落在第二行开头，后接「数」。
        assertEquals(2, sink.lines.size)
        assertEquals("已知ab和", sink.lines[0].joinToString("") { it.first })
        assertEquals("cd两数", sink.lines[1].joinToString("") { it.first })
    }

    @Test
    fun `空文本与纯空白不产生空行`() {
        val sink = RecordingSink()
        PdfTextWrap.appendText(sink, "", 12f, 1, 36f, measureByCount)
        PdfTextWrap.appendText(sink, "  ", 12f, 1, 36f, measureByCount)
        sink.flushLine()
        // 空白字符也算内容（与旧算法一致）：两个空格占一行
        assertEquals(1, sink.lines.size)
        assertEquals(2, sink.totalChars())
    }

    @Test
    fun `flush 后继续 append 落进新行`() {
        val sink = RecordingSink()
        PdfTextWrap.appendText(sink, "第一段", 12f, 1, 36f, measureByCount)
        sink.flushLine()
        PdfTextWrap.appendText(sink, "第二段", 12f, 1, 36f, measureByCount)
        sink.flushLine()
        assertEquals(2, sink.lines.size)
        assertEquals(3, sink.lines[0].sumOf { it.first.length })
        assertEquals(3, sink.lines[1].sumOf { it.first.length })
    }
}
