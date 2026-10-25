package com.mistakebook.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anki CSV 构建器（F7）的纯函数测试。
 */
class AnkiCsvBuilderTest {

    @Test
    fun `表头与三列结构`() {
        val csv = AnkiCsvBuilder.build(
            listOf(AnkiCsvBuilder.CardRow("题干", "答案", listOf("数学", "数列")))
        )
        val lines = csv.trim().lines()
        assertEquals("Front,Back,Tags", lines[0])
        assertEquals("题干,答案,数学 数列", lines[1])
    }

    @Test
    fun `含逗号引号换行的字段被转义`() {
        assertEquals("\"a,b\"", AnkiCsvBuilder.escape("a,b"))
        assertEquals("\"he said \"\"hi\"\"\"", AnkiCsvBuilder.escape("he said \"hi\""))
        assertEquals("plain", AnkiCsvBuilder.escape("plain"))
    }

    @Test
    fun `换行转 br 保留 LaTeX`() {
        val field = AnkiCsvBuilder.field("求 ${'$'}x^2${'$'} 的值\n（保留两位小数）\r\n谢谢")
        assertEquals("求 ${'$'}x^2${'$'} 的值<br>（保留两位小数）<br>谢谢", field)
    }

    @Test
    fun `标签空白折叠为下划线`() {
        assertEquals("圆锥曲线_第二定义", AnkiCsvBuilder.ankiTag("圆锥曲线 第二定义"))
        assertEquals("数列", AnkiCsvBuilder.ankiTag("  数列  "))
    }

    @Test
    fun `卡片行拼装：题干加选项、答案加分隔线解析`() {
        val row = AnkiCsvBuilder.cardRow(
            stem = "下列正确的是",
            options = listOf("A" to "1", "B" to "2"),
            answer = "B",
            analysis = "代入即可",
            tags = listOf("数学", "")
        )
        assertEquals("下列正确的是<br><br>A. 1<br>B. 2", row.front)
        assertEquals("B<br><br>----<br>代入即可", row.back)
        // 空标签被丢弃
        assertEquals(listOf("数学"), row.tags)
    }

    @Test
    fun `无选项无解析的边界`() {
        val row = AnkiCsvBuilder.cardRow("题干", emptyList(), "答", "", listOf("t"))
        assertEquals("题干", row.front)
        assertEquals("答", row.back)
    }

    @Test
    fun `整体 CSV 中文与转义共存`() {
        val csv = AnkiCsvBuilder.build(
            listOf(
                AnkiCsvBuilder.CardRow("题,干", "答\"案", listOf("数学")),
                AnkiCsvBuilder.CardRow("题干2", "答案2", emptyList())
            )
        )
        assertTrue(csv.contains("\"题,干\",\"答\"\"案\",数学"))
        assertTrue(csv.trim().endsWith("题干2,答案2,"))
    }
}
