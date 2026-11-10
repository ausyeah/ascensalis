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
import com.mistakebook.net.llm.isMaxTokensLimitError
import com.mistakebook.net.llm.withKeyFailover
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
 * - 瞬时错误（网络/5xx）额外重试 1 次（2s 退避）；鉴权、限流、取消与截断不重试；
 * - **多 key 失败转移**：鉴权失败（401/403）或额度受限（429/503）时换下一把
 *   key 重发同一个请求（[withKeyFailover]），不再让一把废 key 卡死整条流水线。
 */
class LlmClient(
    private val api: LlmApi,
    /**
     * 用量统计（v0.0.58）：识别流水线的 LLM 调用以前**完全不进统计**，
     * 用户看到的「调用次数/tokens 从来不动」有一部分就是这个原因——
     * 错题识别走的是本类，而只有对话链路记了账。
     * 传 null 表示不统计（单测 / 旧构造点）。
     */
    private val usageStats: com.mistakebook.data.prefs.UsageStatsStore? = null
) {

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

        // 换 key 在阶梯**外面**：一把新 key 值得把整条阶梯重新走一遍，
        // 而阶梯里某一步失败（json_mode 不被认）换 key 原样再犯——
        // 那种错误由 shouldTryNextLlmVariant 判，不该顺手烧掉别的 key。
        withKeyFailover(profile) { key ->
            runLadder(profile, key, systemPrompt, userPrompt, imagePart, maxTokens)
        }
    }

    /**
     * 降级阶梯主体：每一级都有自己的一次机会。
     *
     * 之前的写法把这些降级塞进 repeat(2) 的重试里，阶梯第一步就把次数用光，
     * 后面两级根本没机会执行，最终只会返回笼统的「大模型调用失败」。
     *
     * @param key 这一轮用哪把 key（由 [withKeyFailover] 按候选链逐个给）。
     */
    private suspend fun runLadder(
        profile: LlmProfile,
        key: String,
        systemPrompt: String,
        userPrompt: String,
        imagePart: ChatContentPart?,
        maxTokens: Int
    ): ApiResult<String> {
        val ladder = listOf(
            LadderStep(jsonMode = true, reminder = false),
            LadderStep(jsonMode = false, reminder = false),
            LadderStep(jsonMode = false, reminder = true)
        )
        var lastFailure: ApiResult.Failure? = null
        var retryStep: LadderStep? = null

        for ((index, step) in ladder.withIndex()) {
            val system = if (step.reminder) systemPrompt + JSON_ONLY_REMINDER else systemPrompt
            val initial = sendOnce(profile, key, system, userPrompt, imagePart, step.jsonMode, maxTokens)

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
                when (val retry = sendOnce(profile, key, system, userPrompt, imagePart, step.jsonMode, halved)) {
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
                is ApiResult.Success -> return result
                is ApiResult.Failure -> {
                    if (result.error.serverMessage == TRUNCATED_MESSAGE) {
                        return result
                    }
                    // 降档也救不回来：上限是模型属性，换 json_mode 救不了，
                    // 继续阶梯只会原样再 400——直接把中文结论交给用户
                    if (downgradeExhausted) return result
                    lastFailure = result
                    if (shouldTryNextLlmVariant(result.error) && index < ladder.lastIndex) continue
                    if (!shouldRetryLlmCall(result.error.kind)) return result
                    retryStep = step
                    break
                }
            }
        }

        val step = retryStep
            ?: return lastFailure ?: fail(ApiErrorKind.UNKNOWN, "大模型调用失败")
        delay(RETRY_BACKOFF_MS)
        val retrySystem = if (step.reminder) systemPrompt + JSON_ONLY_REMINDER else systemPrompt
        return sendOnce(profile, key, retrySystem, userPrompt, imagePart, step.jsonMode, maxTokens)
    }

    private data class LadderStep(val jsonMode: Boolean, val reminder: Boolean)

    /**
     * 发一次请求。
     *
     * @param key 这一轮用哪把 key。**必须由调用方显式给出**（[withKeyFailover]
     *   按候选链逐个给）：这里若自行 resolveKey，换 key 就失效了——
     *   每次重发都拿回同一把废 key，候选链成了摆设。
     */
    private suspend fun sendOnce(
        profile: LlmProfile,
        key: String,
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
        // 取消必须**原样上抛**（v0.0.68，与 MineruClient.rethrowCancellation 对齐）：
        // runCatching 会把 CancellationException 转成普通 Failure 返回，
        // 协程继续跑到结束——用户点「取消」却落一条「识别失败，请重试」，
        // 重试又烧一次 LLM 额度。为一个取消按钮供应事故更多额度。
        val response = try {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = HttpFactory.bearer(key),
                request = request
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return ApiResult.Failure(HttpFactory.throwableError(t))
        }
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
        // 记账（v0.0.58）：识别流水线的用量以前没人记，统计页因此「从来不动」。
        // 只在拿到正文时记一次——阶梯重试会把用量翻倍，取最后一次成功即可。
        recordUsage(body.usage)
        return ApiResult.Success(text)
    }

    /** 把一次成功调用的 token 用量写进统计。服务端不给 usage 就跳过（不编数）。 */
    private fun recordUsage(usage: com.mistakebook.net.llm.Usage?) {
        val stats = usageStats ?: return
        val u = usage ?: return
        if (u.promptTokens <= 0 && u.completionTokens <= 0) return
        runCatching {
            stats.recordLlm(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                cachedTokens = u.promptCacheHitTokens
            )
        }
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
     * 轻量纯文本调用：一次 system + user，返回模型原文。
     *
     * 与 [refineJson] 的区别：**不走 JSON 模式、不走降级阶梯、不重试**——
     * 阶梯是为「结构化输出必须成功」设计的（错题整理），而这类调用
     * （生成生图 prompt、起标题这类）失败一次让用户重点即可，
     * 强行阶梯只会把 5 秒的失败拖成 15 秒。
     *
     * 但**换 key 照做**：一把废 key 不该让这类顺手调用也陪葬，
     * 而换 key 的代价只是多一次往返。
     */
    suspend fun plainText(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = DEFAULT_PLAIN_MAX_TOKENS
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!profile.isConfigured()) return@withContext fail(ApiErrorKind.NO_KEY)
        withKeyFailover(profile) { key ->
            sendOnce(profile, key, systemPrompt, userPrompt, imagePart = null, useJsonMode = false, maxTokens = maxTokens)
        }
    }

    /**
     * 带一张图的纯文本调用：**识图模型读图**。
     *
     * 与 [refineJson] 的区别同 [plainText]：不走 JSON 模式、不重试、不阶梯。
     * 识图是要模型「看图说话」，返回一段描述；JSON 模式只会把它套进
     * 一个壳里，还得再剥一层。
     *
     * @param imageFile 本地图片；为空或不存在时按纯文本调用处理（不报错）。
     */
    suspend fun plainTextWithImage(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
        imageFile: File?,
        longEdgePx: Int = 1280,
        maxTokens: Int = DEFAULT_PLAIN_MAX_TOKENS
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!profile.isConfigured()) return@withContext fail(ApiErrorKind.NO_KEY)
        val imagePart = if (imageFile != null && imageFile.exists()) {
            encodeImage(imageFile, longEdgePx)
        } else {
            null
        }
        withKeyFailover(profile) { key ->
            sendOnce(profile, key, systemPrompt, userPrompt, imagePart, useJsonMode = false, maxTokens = maxTokens)
        }
    }

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
                authorization = HttpFactory.bearer(key)
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
            // 探活**不能**只给 1 个 token（v0.0.144 实测踩坑）：
            // reasoning 型模型（deepseek-v4 / glm-5 / kimi-k2 / minimax-m3…）
            // 会把这点预算全花在思考上，正文一个字都吐不出来——接口明明通着，
            // 却被报成「无响应」，用户会以为自己的 Key 坏了（同一个 Key 手动
            // 发请求完全正常）。给足预算，思考完自然会写出答案。
            max_tokens = PROBE_MAX_TOKENS,
            responseFormat = null,
            stream = false
        )
        // 取消必须**原样上抛**（v0.0.68，与 MineruClient.rethrowCancellation 对齐）：
        // runCatching 会把 CancellationException 转成普通 Failure 返回，
        // 协程继续跑到结束——用户点「取消」却落一条「识别失败，请重试」，
        // 重试又烧一次 LLM 额度。为一个取消按钮供应事故更多额度。
        //
        // 这里**不换 key**：探活的目的是回答「首选那把 key 到底通不通」，
        // 换一把成功了反而掩盖「你日常在用的那把已经废了」这个事实。
        val response = try {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = HttpFactory.bearer(profile.resolveKey(profile.model)),
                request = request
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return ApiResult.Failure(HttpFactory.throwableError(t))
        }
        if (!response.isSuccessful) {
            return fail(HttpFactory.httpError(response.code(), response.errorBody()?.string().orEmpty()))
        }
        val body = response.body() ?: return fail(ApiErrorKind.BAD_RESPONSE)
        if (body.choices.isEmpty()) return fail(ApiErrorKind.BAD_RESPONSE, "模型未返回内容")
        val message = body.choices.first().message
        val verdict = probeVerdict(
            content = message?.content.orEmpty(),
            reasoning = message?.reasoningContent.orEmpty()
        ) ?: return fail(ApiErrorKind.BAD_RESPONSE, "模型未返回内容")
        return ApiResult.Success(verdict)
    }

    /** 旧版测试连接（保留给 MinerU 侧使用）。 */
    suspend fun testConnection(profile: LlmProfile): ApiResult<Boolean> {
        if (!profile.isConfigured()) return fail(ApiErrorKind.NO_KEY)
        val response = runCatching {
            api.listModels(
                url = profile.modelsUrl(),
                authorization = HttpFactory.bearer(profile.resolveKey(profile.model))
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
         *
         * v0.0.56 从 16384 提到 32768：与对话侧同一个理由（见
         * [com.mistakebook.net.llm.ChatCompletionStream.MAX_TOKENS]）——
         * 用户实测 16K 在中文下约 26000 字就被截断。多题 + 每道题带完整解析
         * 与 SVG 附图时，JSON 很容易超过这个量。撞服务端硬上限时有降档重试兜底。
         */
        const val DEFAULT_MAX_TOKENS = 65536

    /**
     * 探活（测试大模型）给的 token 预算。
     *
     * 不能是 1：reasoning 型模型先把预算花在思考上，1 个 token 只够想一个
     * 字，正文必然为空——接口是通的，却会被报成「无响应」。512 足够模型
     * 思考完并写出答案（实测 deepseek-v4.1-flash 对「ping」用 7 个
     * reasoning token）。
     */
    const val PROBE_MAX_TOKENS = 512

        /** [plainText] 的默认上限：一段场景 prompt / 一个标题，512 足够。 */
        const val DEFAULT_PLAIN_MAX_TOKENS = 512

        private const val TRUNCATED_MARKER = "__truncated__"

        /** 面向用户的中文提示：输出被 max_tokens 截断。 */
        const val TRUNCATED_MESSAGE =
            "题目太多，AI 输出超长被截断。请一次少导入几道题，或在编辑页分次整理。"
    }
}

/**
 * 是否该换下一个请求变体重试（json_mode 降级阶梯）。
 *
 * 判等必须是 startsWith（v0.0.61 修复）：sendOnce 在 400 时会把 body 片段
 * 拼进 detail（`"HTTP 400 | body=…"`，见 sendOnce 的注释——不带原文的话
 * isMaxTokensLimitError 就瞎了），旧的 `== "HTTP 400"` 精确判等永远为 false，
 * 阶梯第 2/3 步（去 json_mode / 去含字段）对拒绝 json_object 的服务商
 * 永远走不到，白烧一次同阶梯重试后失败。
 * 旧测试只喂手工构造的 `detail = "HTTP 400"`，绿测盖不住这条死路。
 */
internal fun shouldTryNextLlmVariant(error: ApiError): Boolean =
    error.kind == ApiErrorKind.BAD_RESPONSE && error.detail.startsWith("HTTP 400")

internal fun shouldRetryLlmCall(kind: ApiErrorKind): Boolean = when (kind) {
    ApiErrorKind.AUTH,
    ApiErrorKind.RATE_LIMIT,
    ApiErrorKind.CANCELLED -> false
    else -> true
}

/** 降档下限：再低很多模型连一段解析都装不下，降了也没意义。 */
internal const val MIN_MAX_TOKENS = 2048

// isMaxTokensLimitError 已上移到 net.llm（v0.0.62）：对话路径也要用同一判据做
// 输出上限自适应降档，两处共享一份正负判据，改口径只改一处。

/** 降档后仍失败的中文报错（T8）：N 是原始请求的 max_tokens。 */
internal fun maxTokensDowngradeMessage(originalMaxTokens: Int): String =
    "当前模型的输出上限较低（约 $originalMaxTokens tokens），已自动降档仍失败，" +
        "请换用输出上限更高的模型或减少一次整理的题目数"

/**
 * 探活结果判定（纯函数，单测见 LlmProbeVerdictTest）。
 *
 * 返回**可展示的结论**，返回 null 表示「这次探活什么都没拿到」。
 *
 * 关键一条：**只有思考过程、没有正文，也算接口通了**。
 * reasoning 模型被压到很小的输出预算时就是这个形态——HTTP 200、连接正常、
 * 模型也在干活，只是这轮没来得及写正文。把它报成「无响应」是最误导的
 * 结论：用户会去换 Key、换模型、换网络，而问题根本不在那里。
 */
internal fun probeVerdict(content: String, reasoning: String): String? = when {
    content.isNotBlank() -> content
    reasoning.isNotBlank() -> "（模型这轮只返回了思考过程，接口是通的）"
    else -> null
}
