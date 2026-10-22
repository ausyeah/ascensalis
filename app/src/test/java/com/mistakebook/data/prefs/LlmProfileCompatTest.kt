package com.mistakebook.data.prefs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存量接入配置的兼容性测试。
 *
 * `supportsVision` 是后加的字段。用户的 Key / URL / 模型名都存在同一份 JSON 里，
 * 解析一旦失败就是**整套配置全丢**——所以必须钉住「老配置仍能读出来」。
 */
class LlmProfileCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `老配置没有 supportsVision 字段时默认按识图处理`() {
        val legacy = """[{"id":"a","name":"DeepSeek","baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-x","model":"deepseek-chat"}]"""
        val profiles = json.decodeFromString<List<LlmProfile>>(legacy)
        assertEquals(1, profiles.size)
        val p = profiles.first()
        assertEquals("sk-x", p.apiKey)
        assertEquals("deepseek-chat", p.model)
        assertTrue("缺字段时应默认 true（保持原行为）", p.supportsVision)
    }

    @Test
    fun `显式 false 能被读出来`() {
        val raw = """[{"id":"a","baseUrl":"u","apiKey":"k","model":"m","supportsVision":false}]"""
        val profiles = json.decodeFromString<List<LlmProfile>>(raw)
        assertFalse(profiles.first().supportsVision)
    }

    @Test
    fun `往返序列化保留 supportsVision`() {
        val p = LlmProfile(
            id = "a",
            name = "n",
            baseUrl = "u",
            apiKey = "k",
            model = "m",
            supportsVision = false
        )
        val back = json.decodeFromString<List<LlmProfile>>(json.encodeToString(ListSerializer(LlmProfile.serializer()), listOf(p)))
        assertFalse(back.first().supportsVision)
    }
}
