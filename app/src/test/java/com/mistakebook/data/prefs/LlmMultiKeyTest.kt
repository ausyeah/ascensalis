package com.mistakebook.data.prefs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多 Key / 模型池的能力测试。
 *
 * 用户诉求：「不能给一个 URL 配置多个 key，从而实现一个 provider 供应多种
 * 模型，适配中转站实用」。
 *
 * 中转站（one-api / new-api）的典型形态是**一个 baseUrl、多份 key**，
 * 每份 key 的可用模型和额度都不一样。所以：
 * - 主 key 之外可以有附加 key（`extraKeys`），每把独立查看、独立删除；
 * - 拉模型列表时逐把 key 探测，合并成模型池，并自动生成
 *   model -> keyId 路由表（`modelKeys`）；
 * - 请求时按模型名查路由表找出该用的 key（`resolveKey`）。
 *
 * 用户**不再**为每把 key 手填「负责哪些模型」——那是探测的活。
 */
class LlmMultiKeyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun profile(
        apiKey: String = "sk-main",
        model: String = "gpt-4o",
        extra: List<LlmKeyEntry> = emptyList(),
        models: List<String> = emptyList(),
        modelKeys: Map<String, String> = emptyMap()
    ) = LlmProfile(
        id = "p1",
        name = "中转站",
        baseUrl = "https://relay.example.com/v1",
        apiKey = apiKey,
        model = model,
        models = models,
        extraKeys = extra,
        modelKeys = modelKeys
    )

    @Test
    fun `没有附加 key 时用主 key`() {
        val p = profile()
        assertEquals("sk-main", p.resolveKey("gpt-4o"))
        assertEquals("sk-main", p.resolveKey("任何模型"))
    }

    @Test
    fun `路由表绑定到附加 key 时优先于主 key`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b")),
            modelKeys = mapOf("deepseek-chat" to "b")
        )
        assertEquals("sk-b", p.resolveKey("deepseek-chat"))
        // 没被绑定的模型仍然走主 key
        assertEquals("sk-main", p.resolveKey("gpt-4o"))
    }

    @Test
    fun `模型名匹配忽略大小写与首尾空格`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b")),
            modelKeys = mapOf("DeepSeek-Chat" to "b")
        )
        assertEquals("sk-b", p.resolveKey("deepseek-chat"))
        assertEquals("sk-b", p.resolveKey("  DEEPSEEK-CHAT  "))
    }

    @Test
    fun `路由表指向的 key 被删后回退主 key`() {
        // key-b 已从配置里删掉，路由表还指着它 —— 不能解析失败，回退主 key
        val p = profile(modelKeys = mapOf("deepseek-chat" to "b"))
        assertEquals("sk-main", p.resolveKey("deepseek-chat"))
    }

    @Test
    fun `主 key 为空时回退到第一个可用附加 key`() {
        val p = profile(
            apiKey = "",
            extra = listOf(
                LlmKeyEntry(id = "b", key = "sk-b"),
                LlmKeyEntry(id = "c", key = "sk-c")
            )
        )
        assertEquals("sk-b", p.resolveKey("别的模型"))
    }

    @Test
    fun `模型没绑定任何 key 且无主 key 时返回空串表示未配置`() {
        val p = profile(apiKey = "", extra = emptyList())
        assertEquals("", p.resolveKey("gpt-4o"))
    }

    @Test
    fun `availableModels 只含主模型与勾选的模型`() {
        val p = profile(
            model = "gpt-4o",
            models = listOf("o1", "gpt-4o", "claude-3"),
            // 附加 key 不再挂模型：它的存在不该往对话页塞选项
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b"))
        )
        assertEquals(
            listOf("gpt-4o", "o1", "claude-3"),
            p.availableModels()
        )
    }

    @Test
    fun `allKeys 主 key 在前`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b"))
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
        assertTrue("缺字段时应为空路由表", p.modelKeys.isEmpty())
        assertEquals("sk-x", p.resolveKey("deepseek-chat"))
        assertEquals(listOf("deepseek-chat"), p.availableModels())
    }

    @Test
    fun `多 key 配置往返序列化不丢`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b")),
            models = listOf("gpt-4o", "o1"),
            modelKeys = mapOf("deepseek-chat" to "b")
        )
        val back = json.decodeFromString(
            ListSerializer(LlmProfile.serializer()),
            json.encodeToString(ListSerializer(LlmProfile.serializer()), listOf(p))
        ).first()
        assertEquals(1, back.extraKeys.size)
        assertEquals("sk-b", back.extraKeys.first().key)
        assertEquals(listOf("gpt-4o", "o1"), back.models)
        assertEquals(mapOf("deepseek-chat" to "b"), back.modelKeys)
        assertEquals("sk-b", back.resolveKey("deepseek-chat"))
    }

    @Test
    fun `normalized 只补 v1 不影响 key 池`() {
        val p = profile(
            extra = listOf(LlmKeyEntry(key = "sk-b"))
        ).copy(baseUrl = "https://relay.example.com")
        val n = p.normalized()
        assertEquals("https://relay.example.com/v1", n.baseUrl)
        assertEquals(1, n.extraKeys.size)
    }

    // ------------------------------------------------------------------
    // 多 key 整合拉列表后的路由保证
    //
    // 用户诉求：「并不是在每个 key 下去负责某一个模型，而是把所有的 key
    // 路由到一起……我用哪个模型，就调用对应的 key 去获取」。
    //
    // 内部做法：拉列表时逐把 key 探测，只把**主 key 看不见**的模型绑到
    // 能看见它的附加 key 上（路由表）。下面钉死的是这条链路的结果：
    // 合并池里的每一个模型，resolveKey 都得给出一把能真正调它的 key。
    // ------------------------------------------------------------------

    @Test
    fun `主 key 有固定 id，来源标注才对得上号`() {
        val p = profile()
        val main = p.allKeys().first()
        assertEquals(LlmProfile.MAIN_KEY_ID, main.id)
        // 每次调用 id 都一样：否则「这个模型来自哪个 key」无法稳定回溯
        assertEquals(main.id, p.allKeys().first().id)
    }

    @Test
    fun `主 key 能调的模型路由到主 key`() {
        // 探测结果：主 key 与 key-b 都看得见 gpt-4o → 路由表指向主 key
        val p = profile(
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b")),
            modelKeys = mapOf("gpt-4o" to LlmProfile.MAIN_KEY_ID)
        )
        assertEquals("sk-main", p.resolveKey("gpt-4o"))
    }

    @Test
    fun `只有附加 key 看得见的模型路由到它，调用时命中该 key`() {
        // 探测结果：主 key 只看到 gpt-4o，deepseek-chat 只有 key-b 有
        val p = profile(
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b")),
            modelKeys = mapOf(
                "gpt-4o" to LlmProfile.MAIN_KEY_ID,
                "deepseek-chat" to "b"
            )
        )
        assertEquals("sk-main", p.resolveKey("gpt-4o"))
        assertEquals("sk-b", p.resolveKey("deepseek-chat"))
    }

    @Test
    fun `三把 key 各管一批模型，逐模型路由正确`() {
        val p = profile(
            extra = listOf(
                LlmKeyEntry(id = "b", key = "sk-b"),
                LlmKeyEntry(id = "c", key = "sk-c")
            ),
            modelKeys = mapOf(
                "deepseek-chat" to "b",
                "claude-3-5-sonnet" to "c",
                "gpt-4o-mini" to "c"
            )
        )
        assertEquals("sk-b", p.resolveKey("deepseek-chat"))
        assertEquals("sk-c", p.resolveKey("claude-3-5-sonnet"))
        assertEquals("sk-c", p.resolveKey("gpt-4o-mini"))
        // 没绑的一律落到主 key
        assertEquals("sk-main", p.resolveKey("o1"))
    }

    @Test
    fun `合并池里每个模型都至少有一把 key 能调`() {
        val pool = listOf("gpt-4o", "deepseek-chat", "claude-3-5-sonnet")
        val p = profile(
            models = pool,
            extra = listOf(
                LlmKeyEntry(id = "b", key = "sk-b"),
                LlmKeyEntry(id = "c", key = "sk-c")
            ),
            modelKeys = mapOf(
                "deepseek-chat" to "b",
                "claude-3-5-sonnet" to "c"
            )
        )
        // 合并后的池子：勾选任意一个都能解析出非空 key
        pool.forEach { m ->
            assertTrue("模型 $m 应能解析出可用 key", p.resolveKey(m).isNotBlank())
            assertTrue("模型 $m 应有 key 可服务", p.keysServing(m).isNotEmpty())
        }
    }

    @Test
    fun `keysServing 列出绑定 key 与主 key`() {
        val p = profile(
            extra = listOf(
                LlmKeyEntry(id = "b", key = "sk-b"),
                LlmKeyEntry(id = "c", key = "sk-c")
            ),
            modelKeys = mapOf("deepseek-chat" to "c")
        )
        // gpt-4o：没绑附加 key，只有主 key
        assertEquals(1, p.keysServing("gpt-4o").size)
        // deepseek-chat：主 key + 绑定的 key-c
        assertEquals(2, p.keysServing("deepseek-chat").size)
        // 空模型名：无从判断，不给出任何 key
        assertTrue(p.keysServing("  ").isEmpty())
    }

    @Test
    fun `未勾选的模型不出现在 availableModels`() {
        // 拉列表合并出 10 个模型，只勾了 2 个 —— 对话页只该看到这 2 个 + 主模型
        val p = profile(
            model = "gpt-4o",
            models = listOf("gpt-4o", "deepseek-chat")
        )
        val options = p.availableModels()
        assertFalse(options.contains("o1"))
        assertFalse(options.contains("claude-3-5-sonnet"))
        assertEquals(listOf("gpt-4o", "deepseek-chat"), options)
    }
}
