package com.mistakebook.pipeline

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 修复单测。
 *
 * 每一条都对应一种真实的大模型输出形态：修不回来，用户看到的就是
 * 「识别失败」而不是题目。
 */
class JsonRepairTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parses(text: String): Boolean = runCatching {
        json.decodeFromString<RefinedQuestionDto>(text)
    }.isSuccess

    @Test
    fun `字符串里的裸换行被转义成 n`() {
        // 模型写多行题干时直接回车——最高频的一种非法 JSON
        val dirty = "{\"stem\": \"已知 a>0\n求 b 的取值范围\", \"answer\": \"1\"}"
        val fixed = JsonRepair.repair(dirty)
        assertTrue(parses(fixed))
        assertEquals("已知 a>0\n求 b 的取值范围", json.decodeFromString<RefinedQuestionDto>(fixed).stem)
    }

    @Test
    fun `制表符与其它控制字符同样处理`() {
        val dirty = "{\"stem\": \"a\tb\", \"answer\": \"1\"}"
        assertTrue(parses(JsonRepair.repair(dirty)))
    }

    @Test
    fun `字符串里的转义引号不被误判为字符串结束`() {
        val dirty = "{\"stem\": \"他说 \\\"你好\\\" 然后离开\n下一行\", \"answer\": \"A\"}"
        val fixed = JsonRepair.repair(dirty)
        assertTrue(parses(fixed))
        val stem = json.decodeFromString<RefinedQuestionDto>(fixed).stem
        assertTrue(stem.contains("\"你好\""))
        assertTrue(stem.contains("\n"))
    }

    @Test
    fun `被截断的输出补齐闭合引号与括号`() {
        // max_tokens 打满的典型现场：末尾什么都缺
        val dirty = "{\"stem\": \"求极限\", \"options\": [{\"label\": \"A\", \"text\": \"1\""
        val fixed = JsonRepair.repair(dirty)
        assertTrue(parses(fixed))
    }

    @Test
    fun `截断在数组中间也补齐`() {
        val dirty = "{\"stem\": \"x\", \"options\": [{\"label\": \"A\", \"text\": \"1\"}, {\"label\": \"B\", \"text\": \"2"
        val fixed = JsonRepair.repair(dirty)
        assertTrue(parses(fixed))
    }

    @Test
    fun `markdown 围栏被剥掉`() {
        val dirty = "```json\n{\"stem\": \"x\", \"answer\": \"1\"}\n```"
        assertTrue(parses(JsonRepair.repair(dirty)))
    }

    @Test
    fun `尾随逗号被去掉`() {
        val dirty = "{\"stem\": \"x\", \"answer\": \"1\",}"
        assertTrue(parses(JsonRepair.repair(dirty)))
    }

    @Test
    fun `全角引号换成半角`() {
        val dirty = "{\"stem\": \u201C题目\u201D, \"answer\": \"A\"}"
        val fixed = JsonRepair.repair(dirty)
        assertTrue(parses(fixed))
        assertEquals("题目", json.decodeFromString<RefinedQuestionDto>(fixed).stem)
    }

    @Test
    fun `前后有解释文字时截出 JSON 主体`() {
        val dirty = "好的，这是整理结果：\n{\"stem\": \"x\", \"answer\": \"1\"}\n希望对你有帮助"
        assertTrue(parses(JsonRepair.repair(dirty)))
    }

    @Test
    fun `本来合法的 JSON 不被改坏`() {
        val good = "{\"stem\": \"已知 ${'$'}x^2${'$'}，求 ${'$'}y${'$'}\", \"answer\": \"1\", \"difficulty\": 3}"
        val fixed = JsonRepair.repair(good)
        assertTrue(parses(fixed))
        assertEquals("已知 ${'$'}x^2${'$'}，求 ${'$'}y${'$'}", json.decodeFromString<RefinedQuestionDto>(fixed).stem)
    }

    @Test
    fun `完整的提取链路：非法输入也能拿到题干`() {
        val dirty = "```json\n{\"stem\": \"已知 a>0\n求 b\", \"answer\": \"1\",}\n```"
        val outcome = JsonExtractor.extract(raw = dirty, markdown = "兜底", imagePath = "")
        assertEquals(1, outcome.drafts.size)
        // 关键：不是降级（解析成功了）
        assertTrue(!outcome.degraded)
        assertTrue(outcome.drafts.first().stem.contains("求 b"))
    }
}
