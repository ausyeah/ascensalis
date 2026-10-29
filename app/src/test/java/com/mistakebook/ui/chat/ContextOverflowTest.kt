package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文超限判据（v0.0.62 长会话死区修复）。
 *
 * 超限会话每轮必 400、promptTokens 永远拿不到——压缩入口只能靠
 * 失败原因识别。判据覆盖主流服务端原文，漏一个就多一类「永远卡死」的会话。
 */
class ContextOverflowTest {

    @Test
    fun `主流服务端原文命中`() {
        listOf(
            "HTTP 400 | body=this model's maximum context length is 8192 tokens",
            "HTTP 400 | body=Prompt is too long: 200000 tokens > 128000 maximum",
            "HTTP 400 | body=input length exceeds context window",
            "HTTP 400 | body=请求过长，请减少输入内容",
            "HTTP 400 | body=上下文长度超出限制",
            "HTTP 400 | body=Too many tokens in request",
            "HTTP 400 | body=该模型的上下文长度上限为 128000"
        ).forEach { text ->
            assertTrue("$text 应命中超限判据", ChatViewModel.isContextOverflowError(text))
        }
    }

    @Test
    fun `非超限错误不命中`() {
        listOf(
            null,
            "",
            "HTTP 401 | body=invalid api key",
            "HTTP 429 | body=rate limit exceeded",
            "HTTP 500 | body=internal server error",
            "HTTP 400 | body=max_tokens is too large: 131072",
            "HTTP 400 | body=unrecognized request argument supplied: reasoning_effort",
            "网络连接超时"
        ).forEach { text ->
            assertFalse("$text 不该命中超限判据", ChatViewModel.isContextOverflowError(text))
        }
    }
}
