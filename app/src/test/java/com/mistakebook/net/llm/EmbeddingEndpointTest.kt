package com.mistakebook.net.llm

import com.mistakebook.data.prefs.LlmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 嵌入接口的地址解析（v0.0.119）。
 *
 * 用户的原话：「你要给我配置向量模型的设置啊，现在这个 url 和 key 都不让我填写」。
 * 拆出独立配置后，拼 URL 这件事就多了三种输入：用户填的地址、当前大模型
 * 配置的地址、以及「已经粘成完整 /embeddings 地址」的那种。三种拼错都只会
 * 变成 404/401，没有任何提示，所以逐条钉死。
 */
class EmbeddingEndpointTest {

    private val profile = LlmProfile(
        baseUrl = "https://api.chat.example/v1",
        apiKey = "chat-key",
        model = "chat-model"
    )

    @Test
    fun `三项都留空时不算配置`() {
        assertNull(EmbeddingEndpoint.resolve(EmbeddingConfig(), profile))
        assertNull(EmbeddingEndpoint.resolve(EmbeddingConfig(model = "bge-m3"), null))
    }

    @Test
    fun `只填模型名时回退到对话配置`() {
        val resolved = EmbeddingEndpoint.resolve(EmbeddingConfig(model = "bge-m3"), profile)!!

        assertEquals("https://api.chat.example/v1/embeddings", resolved.url)
        assertEquals("Bearer chat-key", resolved.authorization)
        assertEquals("bge-m3", resolved.model)
    }

    @Test
    fun `地址与 Key 可独立覆盖`() {
        val resolved = EmbeddingEndpoint.resolve(
            EmbeddingConfig(baseUrl = "https://embed.example/v1", apiKey = "embed-key", model = "bge-m3"),
            profile
        )!!

        assertEquals("https://embed.example/v1/embeddings", resolved.url)
        assertEquals("Bearer embed-key", resolved.authorization)
    }

    @Test
    fun `只覆盖 Key 时地址仍走对话配置`() {
        val resolved = EmbeddingEndpoint.resolve(
            EmbeddingConfig(apiKey = "embed-key", model = "bge-m3"),
            profile
        )!!

        assertEquals("https://api.chat.example/v1/embeddings", resolved.url)
        assertEquals("Bearer embed-key", resolved.authorization)
    }

    @Test
    fun `直接粘完整 embeddings 地址不会拼成两遍`() {
        assertEquals(
            "https://embed.example/v1/embeddings",
            EmbeddingEndpoint.endpointUrl("https://embed.example/v1/embeddings")
        )
        assertEquals(
            "https://embed.example/v1/embeddings",
            EmbeddingEndpoint.endpointUrl("https://embed.example/v1/embeddings/")
        )
    }

    @Test
    fun `结尾斜杠与首尾空白都吃掉`() {
        assertEquals(
            "https://embed.example/v1/embeddings",
            EmbeddingEndpoint.endpointUrl("  https://embed.example/v1/  ")
        )
    }

    @Test
    fun `空白项按未填处理`() {
        val resolved = EmbeddingEndpoint.resolve(
            EmbeddingConfig(baseUrl = "   ", apiKey = "  ", model = " bge-m3 "),
            profile
        )!!

        assertEquals("https://api.chat.example/v1/embeddings", resolved.url)
        assertEquals("Bearer chat-key", resolved.authorization)
        assertEquals("bge-m3", resolved.model)
    }
}