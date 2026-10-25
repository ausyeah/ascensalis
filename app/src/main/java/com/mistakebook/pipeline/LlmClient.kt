package com.mistakebook.pipeline

import android.graphics.Bitmap
import com.mistakebook.data.ImageNormalizer
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult
import com.mistakebook.net.HttpFactory
import com.mistakebook.net.llm.ChatContentPart
import com.mistakebook.net.llm.ChatMessage
import com.mistakebook.net.llm.ChatRequest
import com.mistakebook.net.llm.ImageUrl
import com.mistakebook.net.llm.LlmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 大模型纠错调用（OpenAI 兼容）。
 *
 * - 降级阶梯（每级一次机会，互不挤占）：带 response_format → 去掉该字段 →
 *   去掉该字段且把「只输出 JSON」写进 prompt；
 * - 可选把原图以 base64 data URL 一并送入（多模态模型），长边压到 [longEdgePx]；
 * - **区分「被 max_tokens 截断」和「返回垃圾」**：前者 JSON 必然残缺，
 *   原先两者都落到 BAD_RESPONSE，导致上层只能笼统报「整理失败」；
 * - 瞬时错误（网络/5xx）额外重试 1 次（2s 退避）；鉴权、限流、取消与截断不重试。
 */
class LlmClient(private val api: LlmApi) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * @return 成功时 [LlmReply.text] 是模型原文；被截断时返回
     *   [ApiErrorKind.BAD_RESPONSE] 且 serverMessage 指明「输出超长被截断」，
     *   由调用方决定是分批还是让用户减少题目。
     */
    suspend fun refineJson(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
        imageFile: File?,
        longEdgePx: Int,
        attachImage: Boolean,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!profile.isConfigured()) return@withContext fail(ApiErrorKind.NO_KEY)

        val imagePart = if (attachImage && imageFile != null && imageFile.exists()) {
            encodeImage(imageFile, longEdgePx)
        } else {
            null
        }

        // 降级阶梯：每一级都有自己的一次机会。
        // 之前的写法把这些降级塞进 repeat(2) 的重试里，阶梯第一步就把次数用光，
        // 后面两级根本没机会执行，最终只会返回笼统的「大模型调用失败」。
        val ladder = listOf(
            LadderStep(jsonMode = true, reminder = false),
            LadderStep(jsonMode = false, reminder = false),
            LadderStep(jsonMode = false, reminder = true)
        )
        var lastFailure: ApiResult.Failure? = null
        var retryStep: LadderStep? = null

        for ((index, step) in ladder.withIndex()) {
            val system = if (step.reminder) systemPrompt + JSON_ONLY_REMINDER else systemPrompt
            val initial = sendOnce(profile, system, userPrompt, imagePart, step.jsonMode, maxTokens)

            // T8：部分服务商的输出上限只有 8k，请求带的 16384 会每级阶梯都
            // 400 才失败，报错还是服务端英文原文。命中「HTTP 400 且错误体
            // 提到 max_tokens / maximum context length」时，以 maxTokens/2
            // （下限 2048）在**同一阶梯步**重试一次——不消耗阶梯次数、
            // 不额外退避（这不是瞬时错误，退避救不了）。
            var downgradeExhausted = false
            val result: ApiResult<String> = if (
                initial is ApiResult.Failure &&
                initial.error.serverMessage != TRUNCATED_MESSAGE &&
                isMaxTokensLimitError(initial.error) &&
                maxTokens > MIN_MAX_TOKENS
            ) {
                val halved = (maxTokens / 2).coerceAtLeast(MIN_MAX_TOKENS)
                when (val retry = sendOnce(profile, system, userPrompt, imagePart, step.jsonMode, halved)) {
                    is ApiResult.Success -> retry
                    is ApiResult.Failure -> {
                        downgradeExhausted = true
                        ApiResult.Failure(
                            initial.error.copy(serverMessage = maxTokensDowngradeMessage(maxTokens))
                        )
                    }
                }
            } else {
                initial
            }

            when (result) {
                is ApiResult.Success -> return@withContext result
                is ApiResult.Failure -> {
                    if (result.error.serverMessage == TRUNCATED_MESSAGE) {
                        return@withContext result
                    }
                    // 降档也救不回来：上限是模型属性，换 json_mode 救不了，
                    // 继续阶梯只会原样再 400——直接把中文结论交给用户
                    if (downgradeExhausted) return@withContext result
                    lastFailure = result
                    if (shouldTryNextLlmVariant(result.error) && index < ladder.lastIndex) continue
                    if (!shouldRetryLlmCall(result.error.kind)) return@withContext result
                    retryStep = step
                    break
                }
            }
        }

        val step = retryStep
            ?: return@withContext lastFailure ?: fail(ApiErrorKind.UNKNOWN, "大模型调用失败")
        delay(RETRY_BACKOFF_MS)
        val retrySystem = if (step.reminder) systemPrompt + JSON_ONLY_REMINDER else systemPrompt
        sendOnce(profile, retrySystem, userPrompt, imagePart, step.jsonMode, maxTokens)

    }

    private data class LadderStep(val jsonMode: Boolean, val reminder: Boolean)

    private suspend fun sendOnce(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
        imagePart: ChatContentPart?,
        useJsonMode: Boolean,
        maxTokens: Int
    ): ApiResult<String> {
        val messages = buildList {
            add(ChatMessage(role = "system", content = JsonPrimitive(systemPrompt)))
            val userContent = if (imagePart == null) {
                JsonPrimitive(userPrompt)
            } else {
                json.encodeToString(
                    ListSerializer(ChatContentPart.serializer()),
                    listOf(
                        ChatContentPart(type = ChatContentPart.TYPE_TEXT, text = userPrompt),
                        imagePart
                    )
                ).let { Json.parseToJsonElement(it) }
            }
            add(ChatMessage(role = "user", content = userContent))
        }
        val request = ChatRequest(
            model = profile.model,
            messages = messages,
            max_tokens = maxTokens,
            responseFormat = if (useJsonMode) com.mistakebook.net.llm.ResponseFormat("json_object") else null
        )
        val response = runCatching {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = "Bearer ${profile.resolveKey(profile.model)}",
                request = request
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string().orEmpty()
            val error = HttpFactory.httpError(response.code(), body)
            return ApiResult.Failure(
                if (response.code() == 400) {
                    // body 片段带进 detail：extractServerMessage 只认几种 JSON 形状，
                    // 有的服务商的「max_tokens is too large」落在别的字段里，
                    // 不带原文的话降档判定（isMaxTokensLimitError）就瞎了
                    error.copy(
                        kind = ApiErrorKind.BAD_RESPONSE,
                        serverMessage = error.serverMessage,
                        detail = "${error.detail} | body=${body.take(400)}"
                    )
                } else {
                    error
                }
            )
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        val choice = body.choices.firstOrNull()
            ?: return fail(ApiErrorKind.BAD_RESPONSE, "大模型未返回任何结果")
        val text = choice.message?.content.orEmpty()
        if (text.isBlank()) return fail(ApiErrorKind.BAD_RESPONSE, "大模型未返回内容")
        // finish_reason 解析了却从来没用过：被 max_tokens 截断的 JSON 必然残缺，
        // 表现出来和「模型胡乱输出」一模一样，只能靠它区分。
        if (choice.finishReason == "length") {
            return ApiResult.Failure(
                ApiError(
                    kind = ApiErrorKind.BAD_RESPONSE,
                    serverMessage = TRUNCATED_MESSAGE,
                    detail = "finish_reason=length, max_tokens=$maxTokens"
                )
            )
        }
        return ApiResult.Success(text)
    }

    /** 长边压到 [longEdgePx]，JPEG q80，转 base64 data URL。 */
    private fun encodeImage(file: File, longEdgePx: Int): ChatContentPart? {
        val bitmap = ImageNormalizer.decodeBounded(file, longEdgePx) ?: return null
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val base64 = android.util.Base64.encodeToString(
                stream.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            ChatContentPart(
                type = ChatContentPart.TYPE_IMAGE,
                imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64")
            )
        } finally {
            bitmap.recycle()
        }
    }


    /** 设置页「测试连接」：GET {base}/models，HTTP 200 即有效。 */
    /**
     * 拉取某个配置下的可用模型列表。
     *
     * [keyOverride] 用来**指定用哪一把 key 去探**：一个配置下有多把 key
     * 时，每把 key 看到的模型可能完全不同，要逐个探测再合并。
     * 不给时按 [LlmProfile.resolveKey] 的规则挑一把。
     *
     * 这里**不要求模型名非空**（与聊天请求不同）：拉列表的目的往往正是
     * 拿到模型名填进配置里，要求先有模型名才能拉列表是个死循环。
     */
    suspend fun listModels(profile: LlmProfile, keyOverride: String? = null): ApiResult<List<String>> {
        val key = keyOverride?.takeIf { it.isNotBlank() } ?: profile.resolveKey(profile.model)
        if (profile.baseUrl.isBlank() || key.isBlank()) return fail(ApiErrorKind.NO_KEY)
        val response = runCatching {
            api.listModels(
                url = profile.modelsUrl(),
                authorization = "Bearer $key"
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        val ids = body.data.map { it.id }.filter { it.isNotBlank() }.distinct().sorted()
        if (ids.isEmpty()) return fail(ApiErrorKind.BAD_RESPONSE, "未返回任何模型")
        return ApiResult.Success(ids)
    }

    /**
     * 真正验证「模型可用」：发一次极小的 chat/completions（max_tokens=1），
     * 能拿到返回内容才算连通，避免只测了域名但模型名写错。
     */
    suspend fun probeModel(profile: LlmProfile): ApiResult<String> {
        if (!profile.isConfigured()) return fail(ApiErrorKind.NO_KEY)
        val request = ChatRequest(
            model = profile.model,
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("ping"))),
            max_tokens = 1,
            responseFormat = null,
            stream = false
        )
        val response = runCatching {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = "Bearer ${profile.resolveKey(profile.model)}",
                request = request
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        val content = body.choices.firstOrNull()?.message?.content.orEmpty()
        if (body.choices.isEmpty()) return fail(ApiErrorKind.BAD_RESPONSE, "模型未返回内容")
        return ApiResult.Success(content.ifBlank { "ok" })
    }

    /** 旧版测试连接（保留给 MinerU 侧使用）。 */
    suspend fun testConnection(profile: LlmProfile): ApiResult<Boolean> {
        if (!profile.isConfigured()) return fail(ApiErrorKind.NO_KEY)
        val response = runCatching {
            api.listModels(
                url = profile.modelsUrl(),
                authorization = "Bearer ${profile.resolveKey(profile.model)}"
            )
        }.getOrElse { return ApiResult.Failure(HttpFactory.throwableError(it)) }
        return if (response.isSuccessful) {
            ApiResult.Success(true)
        } else {
            fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
    }

    private fun fail(kind: ApiErrorKind, message: String = ""): ApiResult.Failure =
        ApiResult.Failure(ApiError(kind = kind, serverMessage = message))

    private fun fail(error: ApiError): ApiResult.Failure = ApiResult.Failure(error)

    companion object {
        const val RETRY_BACKOFF_MS = 2_000L
        const val JSON_ONLY_REMINDER = "\n\n再次强调：只输出 JSON 本身，不要任何其他文字。"

        /**
         * 多题 + 详细解析的输出预算。
         * 4096 会把多题结果拦腰截断，JSON 残缺必然解析失败。
         */
        const val DEFAULT_MAX_TOKENS = 16384

        private const val TRUNCATED_MARKER = "__truncated__"

        /** 面向用户的中文提示：输出被 max_tokens 截断。 */
        const val TRUNCATED_MESSAGE =
            "题目太多，AI 输出超长被截断。请一次少导入几道题，或在编辑页分次整理。"
    }
}

internal fun shouldTryNextLlmVariant(error: ApiError): Boolean =
    error.kind == ApiErrorKind.BAD_RESPONSE && error.detail == "HTTP 400"

internal fun shouldRetryLlmCall(kind: ApiErrorKind): Boolean = when (kind) {
    ApiErrorKind.AUTH,
    ApiErrorKind.RATE_LIMIT,
    ApiErrorKind.CANCELLED -> false
    else -> true
}

/** 降档下限：再低很多模型连一段解析都装不下，降了也没意义。 */
internal const val MIN_MAX_TOKENS = 2048

/**
 * 是否「输出上限超限」型 400（T8）。
 *
 * 必须是 HTTP 400（detail 以 "HTTP 400" 开头，body 片段已由 sendOnce 带入），
 * 且错误文本提到 max_tokens 或 maximum context length（忽略大小写）——
 * 两种常见服务端原文都覆盖。其他 400（如 prompt 超限、参数非法）不降档：
 * 那些降了也一样 400。
 */
internal fun isMaxTokensLimitError(error: ApiError): Boolean {
    if (!error.detail.startsWith("HTTP 400")) return false
    val haystack = "${error.serverMessage}\n${error.detail}".lowercase()
    return haystack.contains("max_tokens") || haystack.contains("maximum context length")
}

/** 降档后仍失败的中文报错（T8）：N 是原始请求的 max_tokens。 */
internal fun maxTokensDowngradeMessage(originalMaxTokens: Int): String =
    "当前模型的输出上限较低（约 $originalMaxTokens tokens），已自动降档仍失败，" +
        "请换用输出上限更高的模型或减少一次整理的题目数"
