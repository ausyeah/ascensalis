package com.mistakebook.pipeline

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ChatResponse
import com.mistakebook.net.llm.Choice
import com.mistakebook.net.llm.ChoiceMessage
import com.mistakebook.net.llm.LlmApi
import com.mistakebook.net.llm.isMaxTokensLimitError
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * LLM max_tokens 超限自动降档（T8）。
 *
 * 部分服务商输出上限 8k，请求带 16384 会每级降级阶梯都 400 才失败。
 * 用伪 [LlmApi] 记录每次请求的 max_tokens，验证降档请求序列。
 */
class LlmMaxTokensDowngradeTest {

    private class FakeApi(
        private val responses: MutableList<Response<ChatResponse>>,
        private val requests: MutableList<Int> = mutableListOf()
    ) : LlmApi {
        override suspend fun chatCompletions(
            url: String,
            authorization: String,
            request: ChatRequest
        ): Response<ChatResponse> {
            requests += request.max_tokens
            return responses.removeFirstOrNull()
                ?: error("伪 API 没有更多响应了（第 ${requests.size} 次请求）")
        }

        override suspend fun listModels(
            url: String,
            authorization: String
        ): Response<com.mistakebook.net.llm.ModelsResponse> =
            Response.success(com.mistakebook.net.llm.ModelsResponse())
    }

    private fun ok(content: String = "{\"questions\":[]}"): Response<ChatResponse> = Response.success(
        ChatResponse(choices = listOf(Choice(message = ChoiceMessage(role = "assistant", content = content))))
    )

    private fun http400(body: String): Response<ChatResponse> = Response.error(
        400,
        body.toResponseBody("application/json".toMediaType())
    )

    private val profile = LlmProfile(
        name = "测试",
        baseUrl = "https://example.com/v1",
        apiKey = "sk-test",
        model = "test-model"
    )

    @Test
    fun `400 提到 max_tokens 时第二次请求减半`() = runBlocking {
        val responses = mutableListOf(
            http400("""{"error":{"message":"max_tokens is too large: 16384 tokens > 8192 limit"}}"""),
            ok()
        )
        val requests = mutableListOf<Int>()
        val client = LlmClient(FakeApi(responses, requests))

        val result = client.refineJson(
            profile = profile,
            systemPrompt = "system",
            userPrompt = "user",
            imageFile = null,
            longEdgePx = 1280,
            attachImage = false,
            maxTokens = 16384
        )

        assertTrue("降档重试应成功", result is com.mistakebook.net.ApiResult.Success)
        assertEquals(listOf(16384, 8192), requests)
    }

    @Test
    fun `降档仍失败返回中文报错且不再走阶梯`() = runBlocking {
        val errorBody = """{"error":{"message":"Maximum context length exceeded"}}"""
        val responses = mutableListOf(
            http400(errorBody),
            http400(errorBody)
        )
        val requests = mutableListOf<Int>()
        val client = LlmClient(FakeApi(responses, requests))

        val result = client.refineJson(
            profile = profile,
            systemPrompt = "system",
            userPrompt = "user",
            imageFile = null,
            longEdgePx = 1280,
            attachImage = false,
            maxTokens = 16384
        )

        assertTrue(result is com.mistakebook.net.ApiResult.Failure)
        result as com.mistakebook.net.ApiResult.Failure
        // 中文报错 + 只发两次请求（同阶梯步减半重试一次，不继续阶梯）
        assertTrue(result.error.serverMessage.contains("已自动降档仍失败"))
        assertTrue(result.error.serverMessage.contains("16384"))
        assertEquals(listOf(16384, 8192), requests)
    }

    @Test
    fun `正常路径不触发降档`() = runBlocking {
        val requests = mutableListOf<Int>()
        val client = LlmClient(FakeApi(mutableListOf(ok()), requests))

        val result = client.refineJson(
            profile = profile,
            systemPrompt = "system",
            userPrompt = "user",
            imageFile = null,
            longEdgePx = 1280,
            attachImage = false,
            maxTokens = 16384
        )

        assertTrue(result is com.mistakebook.net.ApiResult.Success)
        assertEquals(listOf(16384), requests)
    }

    @Test
    fun `普通 400 不降档`() {
        // prompt 超限 / 参数非法等其他 400：降了也一样 400，不该触发
        assertTrue(
            !isMaxTokensLimitError(
                ApiError(ApiErrorKind.BAD_RESPONSE, detail = "HTTP 400 | body=invalid request param")
            )
        )
        assertTrue(
            isMaxTokensLimitError(
                ApiError(ApiErrorKind.BAD_RESPONSE, detail = "HTTP 400 | body=... MAX_TOKENS is too large ...")
            )
        )
        assertTrue(
            isMaxTokensLimitError(
                ApiError(
                    ApiErrorKind.BAD_RESPONSE,
                    serverMessage = "该模型最多支持 8192 个 token",
                    detail = "HTTP 400 | body={\"error\":{\"message\":\"maximum context length 8192\"}}"
                )
            )
        )
    }

    @Test
    fun `非 400 错误不降档`() {
        assertFalse(isMaxTokensLimitError(ApiError(ApiErrorKind.SERVER, detail = "HTTP 500")))
        assertFalse(isMaxTokensLimitError(ApiError(ApiErrorKind.NETWORK)))
    }
}
