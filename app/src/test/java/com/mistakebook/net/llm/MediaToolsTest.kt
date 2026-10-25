package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体工具单测：参数解析与工具装配。
 *
 * 参数解析是模型输出 → 代码执行之间唯一的接口，模型给什么都可能：
 * 多余空白、包裹引号、整段不是 JSON。解析崩了就是整轮对话失败。
 */
class MediaToolsTest {

    @Test
    fun `取到字符串参数`() {
        assertEquals(
            "请完整描述这张图",
            MediaTools.argument("""{"question":"请完整描述这张图"}""", "question")
        )
    }

    @Test
    fun `参数带多余空白时被 trim`() {
        assertEquals("abc", MediaTools.argument("""{"prompt":"  abc  "}""", "prompt"))
    }

    @Test
    fun `字段不存在返回 null`() {
        assertNull(MediaTools.argument("""{"other":"x"}""", "prompt"))
    }

    @Test
    fun `整段不是 JSON 时返回 null 而不是抛异常`() {
        assertNull(MediaTools.argument("这不是 JSON", "prompt"))
        assertNull(MediaTools.argument("", "prompt"))
        assertNull(MediaTools.argument("   ", "prompt"))
    }

    @Test
    fun `参数值是对象时返回 null`() {
        assertNull(MediaTools.argument("""{"prompt":{"a":1}}""", "prompt"))
    }

    @Test
    fun `空字符串参数视为缺失`() {
        assertNull(MediaTools.argument("""{"prompt":"   "}""", "prompt"))
    }

    @Test
    fun `工具按配置装配`() {
        assertTrue(MediaTools.all(visionConfigured = false, imageConfigured = false).isEmpty())
        assertEquals(
            listOf(MediaTools.DESCRIBE_IMAGE),
            MediaTools.all(visionConfigured = true, imageConfigured = false).map { it.function.name }
        )
        assertEquals(
            listOf(MediaTools.GENERATE_IMAGE),
            MediaTools.all(visionConfigured = false, imageConfigured = true).map { it.function.name }
        )
        assertEquals(
            listOf(MediaTools.DESCRIBE_IMAGE, MediaTools.GENERATE_IMAGE),
            MediaTools.all(visionConfigured = true, imageConfigured = true).map { it.function.name }
        )
    }

    @Test
    fun `两个工具都声明了必填参数`() {
        for (tool in MediaTools.all(true, true)) {
            val params = tool.function.parameters.toString()
            assertTrue(params.contains("required"))
        }
    }

    @Test
    fun `生图工具描述里明确排除数学几何`() {
        // 几何/函数图走 SVG，生图画不准——这条约束写在工具描述里，
        // 是模型唯一的决策依据
        assertTrue(MediaTools.generateImageTool().function.description.contains("SVG"))
    }
}
