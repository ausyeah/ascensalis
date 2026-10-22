package com.mistakebook.net.llm

import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度降级链的测试。
 *
 * 钉住三件事：
 * 1. OFF 一个字段都不发；
 * 2. MAX 先试 `max` 再试 `high`（部分中转不认 max，只认 high）；
 * 3. 只有 HTTP 400 才值得换字段——鉴权/限流/5xx 换了也是白烧配额。
 */
class ReasoningFallbackTest {

    @Test
    fun `OFF 不发任何字段`() {
        assertEquals(listOf<Pair<String, String>?>(null), ReasoningFallback.attempts(ReasoningEffort.OFF))
    }

    @Test
    fun `HIGH 按字段名候选链各试一次`() {
        val attempts = ReasoningFallback.attempts(ReasoningEffort.HIGH)
        assertEquals(
            listOf(
                "reasoning_effort" to "high",
                "thinking" to "high",
                "reasoning" to "high",
                null
            ),
            attempts
        )
    }

    @Test
    fun `MAX 先 max 后 high`() {
        val attempts = ReasoningFallback.attempts(ReasoningEffort.MAX)
        // 前三个是 max，后三个是 high，最后兜底 null
        assertEquals(7, attempts.size)
        assertEquals("max", attempts[0]?.second)
        assertEquals("reasoning_effort" to "max", attempts[0])
        assertEquals("thinking" to "max", attempts[1])
        assertEquals("reasoning" to "max", attempts[2])
        assertEquals("reasoning_effort" to "high", attempts[3])
        assertNull(attempts.last())
    }

    @Test
    fun `LOW 与 MEDIUM 用档位值本身`() {
        assertEquals(
            listOf("reasoning_effort" to "low", "thinking" to "low", "reasoning" to "low", null),
            ReasoningFallback.attempts(ReasoningEffort.LOW)
        )
        assertEquals(
            listOf("reasoning_effort" to "medium", "thinking" to "medium", "reasoning" to "medium", null),
            ReasoningFallback.attempts(ReasoningEffort.MEDIUM)
        )
    }

    @Test
    fun `withReasoning 三个字段同一时刻只有一个非空`() {
        val base = ChatRequest(
            model = "m",
            messages = emptyList(),
            stream = true
        )
        val withEffort = base.withReasoning("thinking", "high")
        assertNull(withEffort.reasoningEffort)
        assertEquals("high", withEffort.thinking)
        assertNull(withEffort.reasoning)

        val none = withEffort.withReasoning(null, null)
        assertNull(none.reasoningEffort)
        assertNull(none.thinking)
        assertNull(none.reasoning)
    }

    @Test
 fun `400 才判定为字段不被识别`() {
        val bad = ApiError(kind = ApiErrorKind.UNKNOWN, detail = "HTTP 400")
        assertTrue(ReasoningFallback.isUnknownFieldError(bad))
    }

    @Test
    fun `鉴权限流服务端错误不换字段`() {
        assertFalse(ReasoningFallback.isUnknownFieldError(ApiError(kind = ApiErrorKind.AUTH, detail = "HTTP 401")))
        assertFalse(ReasoningFallback.isUnknownFieldError(ApiError(kind = ApiErrorKind.RATE_LIMIT, detail = "HTTP 429")))
        assertFalse(ReasoningFallback.isUnknownFieldError(ApiError(kind = ApiErrorKind.SERVER, detail = "HTTP 500")))
        assertFalse(ReasoningFallback.isUnknownFieldError(ApiError(kind = ApiErrorKind.BAD_RESPONSE, detail = "HTTP 200")))
        assertFalse(ReasoningFallback.isUnknownFieldError(ApiError(kind = ApiErrorKind.NETWORK, detail = "")))
    }
}
