package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变式题容错解析的回归测试（F1 举一反三）。
 *
 * 覆盖面与 JsonExtractor 的容错哲学对齐：围栏、废话、缺字段、
 * 缺 label、difficulty 越界、题干为空、纯垃圾。
 */
class VariantQuestionParserTest {

    @Test
    fun `纯 JSON 直接解析`() {
        val raw = """
            {"subject":"数学","title":"变式","knowledge_points":["数列"],
             "stem":"已知数列 2,4,8，求第 4 项。","options":[],
             "answer":"16","analysis":"公比为 2。\n第 4 项 = 8×2 = 16","difficulty":3}
        """.trimIndent()
        val dto = VariantQuestionParser.parse(raw)!!
        assertEquals("数学", dto.subject)
        assertEquals("已知数列 2,4,8，求第 4 项。", dto.stem)
        assertEquals("16", dto.answer)
        assertEquals(3, dto.difficulty)
    }

    @Test
    fun `带 json 围栏解析`() {
        val raw = """
            ```json
            {"stem":"变式题干","answer":"A","options":[{"label":"A","text":"1"}],"difficulty":2}
            ```
        """.trimIndent()
        val dto = VariantQuestionParser.parse(raw)!!
        assertEquals("变式题干", dto.stem)
        assertEquals(2, dto.difficulty)
    }

    @Test
    fun `围栏外混入说明文字截花括号解析`() {
        val raw = "好的，这是变式题：\n{\"stem\":\"x\"}\n希望有帮助"
        assertEquals("x", VariantQuestionParser.parse(raw)!!.stem)
    }

    @Test
    fun `缺选项与缺难度给默认值`() {
        val raw = """{"stem":"只有题干"}"""
        val dto = VariantQuestionParser.parse(raw)!!
        assertTrue(dto.options.isEmpty())
        assertEquals(3, dto.difficulty)
    }

    @Test
    fun `缺 label 按序补大写字母`() {
        val raw = """
            {"stem":"题干","options":[
                {"text":"甲"},{"label":"C","text":"乙"},{"text":"丙"}]}
        """.trimIndent()
        val options = VariantQuestionParser.parse(raw)!!.options
        assertEquals("A", options[0].label)
        assertEquals("C", options[1].label)
        assertEquals("C", options[2].label)
    }

    @Test
    fun `difficulty 越界钳位到 1 到 5`() {
        val high = VariantQuestionParser.parse("""{"stem":"s","difficulty":9}""")!!
        val low = VariantQuestionParser.parse("""{"stem":"s","difficulty":0}""")!!
        assertEquals(5, high.difficulty)
        assertEquals(1, low.difficulty)
    }

    @Test
    fun `题干为空视为解析失败`() {
        assertNull(VariantQuestionParser.parse("""{"stem":"  "}"""))
        assertNull(VariantQuestionParser.parse("""{"answer":"只有答案"}"""))
    }

    @Test
    fun `纯垃圾输入返回 null 而不是抛异常`() {
        assertNull(VariantQuestionParser.parse(""))
        assertNull(VariantQuestionParser.parse("这不是 JSON"))
        assertNull(VariantQuestionParser.parse("{ 残缺的"))
    }

    @Test
    fun `未知字段被忽略`() {
        val raw = """{"stem":"s","future_field":{"nested":1}}"""
        assertEquals("s", VariantQuestionParser.parse(raw)!!.stem)
    }
}
