package com.mistakebook.net.llm

import com.mistakebook.data.prefs.LlmKeyEntry
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败转移（一把 key 不行就换下一把）的判据与行为测试。
 *
 * 用户诉求：「一个 URL 下多个 key 都勾选了同一个模型，失败后应该自己
 * 换一个 key 尝试」。
 *
 * 这里只测**策略**：哪些错误算「这把 key 不行」（才值得换）、换的时候
 * 按什么顺序换、非 key 问题要不要换。策略是纯函数 + 一个协程函数，
 * 不碰网络，所以能直接单测。
 */
class KeyFailoverTest {

    private fun profile(
        apiKey: String = "sk-main",
        model: String = "deepseek-chat",
        extra: List<LlmKeyEntry> = emptyList(),
        modelKeyChain: Map<String, List<String>> = emptyMap()
    ) = LlmProfile(
        id = "p1",
        name = "中转站",
        baseUrl = "https://relay.example.com/v1",
        apiKey = apiKey,
        model = model,
        extraKeys = extra,
        modelKeyChain = modelKeyChain
    )

    /** 三把 key 的链，主 key 打头。 */
    private fun threeKeys() = profile(
        extra = listOf(
            LlmKeyEntry(id = "b", key = "sk-b"),
            LlmKeyEntry(id = "c", key = "sk-c")
        ),
        modelKeyChain = mapOf(
            "deepseek-chat" to listOf(LlmProfile.MAIN_KEY_ID, "b", "c")
        )
    )

    private fun failure(kind: ApiErrorKind) =
        ApiResult.Failure(ApiError(kind, serverMessage = "boom"))

    // ---------------- isKeyFailure：哪些错误值得换 key ----------------

    @Test
    fun `鉴权失败算 key 的问题`() {
        assertTrue(isKeyFailure(ApiError(ApiErrorKind.AUTH)))
    }

    @Test
    fun `额度受限算 key 的问题`() {
        assertTrue(isKeyFailure(ApiError(ApiErrorKind.RATE_LIMIT)))
    }

    @Test
    fun `服务端错误不算 key 的问题`() {
        // 换一把 key 原样再犯，只会把其余 key 的额度一起烧掉
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.SERVER)))
    }

    @Test
    fun `网络与超时不算 key 的问题`() {
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.NETWORK)))
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.TIMEOUT)))
    }

    @Test
    fun `请求体问题与取消不算 key 的问题`() {
        // 每把 key 收到的都是同一个请求体；取消是用户点了停止
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.BAD_RESPONSE)))
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.CANCELLED)))
    }

    @Test
    fun `未知错误与未配 key 不算 key 的问题`() {
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.UNKNOWN)))
        assertFalse(isKeyFailure(ApiError(ApiErrorKind.NO_KEY)))
    }

    // ---------------- withKeyFailover：换不换、按什么顺序换 ----------------

    @Test
    fun `第一把就成功只发一次`() = runBlocking {
        val p = threeKeys()
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            ApiResult.Success("ok")
        }
        assertEquals("ok", (result as ApiResult.Success).data)
        assertEquals(listOf("sk-main"), tried)
    }

    @Test
    fun `鉴权失败后换下一把 key 原样重发`() = runBlocking {
        val p = threeKeys()
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            // 主 key 被吊销，第二把好的
            if (key == "sk-main") failure(ApiErrorKind.AUTH) else ApiResult.Success("ok")
        }
        assertEquals("ok", (result as ApiResult.Success).data)
        assertEquals(listOf("sk-main", "sk-b"), tried)
    }

    @Test
    fun `额度受限也换 key`() = runBlocking {
        val p = threeKeys()
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            if (key == "sk-b") failure(ApiErrorKind.RATE_LIMIT)
            else ApiResult.Success("ok")
        }
        assertEquals("ok", (result as ApiResult.Success).data)
        // 第一把就成功，不会碰到第二把
        assertEquals(listOf("sk-main"), tried)
        // 换一个入口：让第一把也失败，验证换到第二把成功
        val tried2 = mutableListOf<String>()
        val r2 = withKeyFailover(profile(
            extra = listOf(
                LlmKeyEntry(id = "b", key = "sk-b"),
                LlmKeyEntry(id = "c", key = "sk-c")
            ),
            modelKeyChain = mapOf(
                "deepseek-chat" to listOf(LlmProfile.MAIN_KEY_ID, "b", "c")
            )
        )) { key ->
            tried2 += key
            when (key) {
                "sk-main" -> failure(ApiErrorKind.AUTH)
                "sk-b" -> failure(ApiErrorKind.RATE_LIMIT)
                else -> ApiResult.Success("ok")
            }
        }
        assertEquals("ok", (r2 as ApiResult.Success).data)
        assertEquals(listOf("sk-main", "sk-b", "sk-c"), tried2)
    }

    @Test
    fun `所有 key 都不行时把最后一把的失败交出去`() = runBlocking {
        val p = threeKeys()
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            failure(ApiErrorKind.AUTH)
        }
        assertTrue(result is ApiResult.Failure)
        assertEquals(
            ApiErrorKind.AUTH,
            (result as ApiResult.Failure).error.kind
        )
        assertEquals(listOf("sk-main", "sk-b", "sk-c"), tried)
    }

    @Test
    fun `错误与 key 无关时不换 key`() = runBlocking {
        val p = threeKeys()
        // 服务端 5xx：换 key 原样再犯，不该白烧另外两把的额度
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            failure(ApiErrorKind.SERVER)
        }
        assertTrue(result is ApiResult.Failure)
        assertEquals(listOf("sk-main"), tried)
    }

    @Test
    fun `网络错误不换 key`() = runBlocking {
        val p = threeKeys()
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            failure(ApiErrorKind.NETWORK)
        }
        assertEquals(ApiErrorKind.NETWORK, (result as ApiResult.Failure).error.kind)
        assertEquals(listOf("sk-main"), tried)
    }

    @Test
    fun `中间换了 key 但后面的错误与 key 无关时立刻停`() = runBlocking {
        // 第一把 401 → 换第二把 → 第二把 400（请求体不认）：
        // 再换第三把也是同一个 400，停在这里
        val p = threeKeys()
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            if (key == "sk-main") failure(ApiErrorKind.AUTH)
            else failure(ApiErrorKind.BAD_RESPONSE)
        }
        assertEquals(ApiErrorKind.BAD_RESPONSE, (result as ApiResult.Failure).error.kind)
        assertEquals(listOf("sk-main", "sk-b"), tried)
    }

    @Test
    fun `只有一把 key 时失败就失败，不会重发`() = runBlocking {
        val p = profile(apiKey = "sk-main")
        val tried = mutableListOf<String>()
        val result = withKeyFailover(p) { key ->
            tried += key
            failure(ApiErrorKind.AUTH)
        }
        assertEquals(ApiErrorKind.AUTH, (result as ApiResult.Failure).error.kind)
        assertEquals(listOf("sk-main"), tried)
    }

    @Test
    fun `一把 key 都没有时直接报未配 key`() = runBlocking {
        val p = profile(apiKey = "", extra = emptyList())
        val result = withKeyFailover(p) { ApiResult.Success("不该被调到") }
        assertEquals(ApiErrorKind.NO_KEY, (result as ApiResult.Failure).error.kind)
    }

    @Test
    fun `链上重复的同一把 key 只试一次`() = runBlocking {
        // 用户把同一把 key 粘了两遍：去重在 keyCandidates 里做，
        // 这里钉死「不会因此多烧一次额度」
        val p = profile(
            extra = listOf(LlmKeyEntry(id = "b", key = "sk-b")),
            modelKeyChain = mapOf("deepseek-chat" to listOf("b", LlmProfile.MAIN_KEY_ID, "b"))
        )
        val tried = mutableListOf<String>()
        withKeyFailover(p) { key ->
            tried += key
            failure(ApiErrorKind.AUTH)
        }
        assertEquals(listOf("sk-b", "sk-main"), tried)
    }

    @Test
    fun `成功那次用的是链上对应位置的 key`() = runBlocking {
        // 请求头必须带上「这一轮」的 key，不能每轮都拿回同一把废 key
        val p = threeKeys()
        val sent = mutableListOf<String>()
        withKeyFailover(p) { key ->
            sent += "Bearer $key"
            if (key == "sk-b") ApiResult.Success("ok")
            else failure(ApiErrorKind.AUTH)
        }
        assertEquals(listOf("Bearer sk-main", "Bearer sk-b"), sent)
    }
}
