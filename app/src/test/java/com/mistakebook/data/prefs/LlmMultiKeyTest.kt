package com.mistakebook.data.prefs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多 Key / 多模型的能力测试。
 *
 * 用户诉求：「不能给一个 URL 配置多个 key，从而实现一个 provider 供应多种
 * 模型，适配中转站实用」。
 *
 * 中转站（one-api / new-api）的典型形态是**一个 baseUrl、多份 key**，
 * 每份 key 的可用模型与额度都不一样。所以：
 * - 主 key 之外可以有附加 key（`extraKeys`）；
 * - 每个附加 key 声明自己管哪些模型；
 * - 请求时按模型名反查该用哪个 key（`resolveKey`）。
 */
class LlmMultiKeyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun profile(
        apiKey: String = "sk-main",
        model: String = "gpt-4o",
        extra: List<LlmKeyEntry> = emptyList(),
        models: List<String> = emptyList()
    ) = LlmProfile(
        id = "p1",
        name = "中转站",
        baseUrl = "https://relay.example.com/v1",
        apiKey = apiKey,
        model = model,
        models = models,
        extraKeys = extra
    )

    @Test
    fun `没有附加 key 时用主 key`() {
        val p = profile()
        assertEquals("sk-main", p.resolveKey("gpt-4o"))
        assertEquals("sk-main", p.resolveKey("任何模型"))
    }

    @Test
    fun `绑定到模型的附加 key 优先于主 key`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b", models = listOf("deepseek-chat")))
        )
        assertEquals("sk-b", p.resolveKey("deepseek-chat"))
        // 没被绑定的模型仍然走主 key
        assertEquals("sk-main", p.resolveKey("gpt-4o"))
    }

    @Test
    fun `模型名匹配忽略大小写与首尾空格`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b", models = listOf("DeepSeek-Chat")))
        )
        assertEquals("sk-b", p.resolveKey("deepseek-chat"))
        assertEquals("sk-b", p.resolveKey("  DEEPSEEK-CHAT  "))
    }

    @Test
    fun `主 key 为空时回退到第一个可用附加 key`() {
        val p = profile(
            apiKey = "",
            extra = listOf(LlmKeyEntry(key = "sk-b", models = listOf("x")))
        )
        assertEquals("sk-b", p.resolveKey("别的模型"))
    }

    @Test
    fun `模型没绑定任何 key 时返回空串表示未配置`() {
        val p = profile(apiKey = "", extra = emptyList())
        assertEquals("", p.resolveKey("gpt-4o"))
    }

    @Test
    fun `多个 key 绑定同一模型时取最后一个匹配的`() {
        // 设置页往列表上方加新条目来「覆盖」旧绑定，所以后写的优先。
        val p = profile(
            extra = listOf(
                LlmKeyEntry(key = "sk-old", models = listOf("gpt-4o")),
                LlmKeyEntry(key = "sk-new", models = listOf("gpt-4o"))
            )
        )
        assertEquals("sk-new", p.resolveKey("gpt-4o"))
    }

    @Test
    fun `availableModels 汇总主模型_模型列表_各 key 绑定且去重`() {
        val p = profile(
            model = "gpt-4o",
            models = listOf("o1", "gpt-4o", "claude-3"),
            extra = listOf(
                LlmKeyEntry(key = "sk-b", models = listOf("deepseek-chat", "gpt-4o"))
            )
        )
        assertEquals(
            listOf("gpt-4o", "o1", "claude-3", "deepseek-chat"),
            p.availableModels()
        )
    }

    @Test
    fun `allKeys 主 key 在前`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b", models = listOf("x")))
        )
        assertEquals(listOf("sk-main", "sk-b"), p.allKeys().map { it.key })
    }

    @Test
    fun `存量配置没有多 key 字段时仍能读出且 key 解析走主 key`() {
        val legacy =
            """[{"id":"a","name":"DeepSeek","baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-x","model":"deepseek-chat"}]"""
        val profiles = json.decodeFromString<List<LlmProfile>>(legacy)
        val p = profiles.first()
        assertTrue("缺字段时应为空池", p.extraKeys.isEmpty())
        assertTrue("缺字段时应为空模型列表", p.models.isEmpty())
        assertEquals("sk-x", p.resolveKey("deepseek-chat"))
        assertEquals(listOf("deepseek-chat"), p.availableModels())
    }

    @Test
    fun `多 key 配置往返序列化不丢`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b", models = listOf("deepseek-chat"))),
            models = listOf("gpt-4o", "o1")
        )
        val back = json.decodeFromString(
            ListSerializer(LlmProfile.serializer()),
            json.encodeToString(ListSerializer(LlmProfile.serializer()), listOf(p))
        ).first()
        assertEquals(1, back.extraKeys.size)
        assertEquals("sk-b", back.extraKeys.first().key)
        assertEquals(listOf("deepseek-chat"), back.extraKeys.first().models)
        assertEquals(listOf("gpt-4o", "o1"), back.models)
        assertEquals("sk-b", back.resolveKey("deepseek-chat"))
    }

    @Test
    fun `normalized 只补 v1 不影响 key 池`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b", models = listOf("x")))
        ).copy(baseUrl = "https://relay.example.com")
        val n = p.normalized()
        assertEquals("https://relay.example.com/v1", n.baseUrl)
        assertEquals(1, n.extraKeys.size)
        assertFalse(n.extraKeys.first().models.isEmpty())
    }
}
