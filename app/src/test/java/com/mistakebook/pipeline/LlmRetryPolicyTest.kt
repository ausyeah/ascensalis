package com.mistakebook.pipeline

import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRetryPolicyTest {

    @Test
    fun `authentication rate limit and cancellation are not retried`() {
        listOf(ApiErrorKind.AUTH, ApiErrorKind.RATE_LIMIT, ApiErrorKind.CANCELLED)
            .forEach { assertFalse("$it must not be retried", shouldRetryLlmCall(it)) }
    }

    @Test
    fun `transient and recoverable errors remain retryable`() {
        listOf(
            ApiErrorKind.NETWORK,
            ApiErrorKind.TIMEOUT,
            ApiErrorKind.SERVER,
            ApiErrorKind.BAD_RESPONSE,
            ApiErrorKind.UNKNOWN
        ).forEach { assertTrue("$it may be retried", shouldRetryLlmCall(it)) }
    }

    @Test
    fun `only HTTP 400 enables the next request variant`() {
        assertTrue(
            shouldTryNextLlmVariant(ApiError(ApiErrorKind.BAD_RESPONSE, detail = "HTTP 400"))
        )
        // 生产形态（v0.0.61 回归）：sendOnce 会把 body 片段拼进 detail，
        // 旧的精确判等在这里永远为 false，降级阶梯整条死掉。
        assertTrue(
            "带 body 的生产形态 detail 也必须命中",
            shouldTryNextLlmVariant(
                ApiError(
                    ApiErrorKind.BAD_RESPONSE,
                    detail = "HTTP 400 | body={\"error\":{\"message\":\"response_format is not supported\"}}"
                )
            )
        )
        listOf(
            ApiError(ApiErrorKind.NETWORK),
            ApiError(ApiErrorKind.SERVER, detail = "HTTP 500"),
            ApiError(ApiErrorKind.SERVER, detail = "HTTP 500 | body=..."),
            ApiError(ApiErrorKind.BAD_RESPONSE, detail = "大模型未返回内容")
        ).forEach { assertFalse("$it must not switch request variants", shouldTryNextLlmVariant(it)) }
    }
}
