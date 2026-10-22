package com.mistakebook.net.llm

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.HttpFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式对话的一次输出。
 *
 * **全部走事件，不抛异常到 UI**（项目规则 6）。
 */
sealed interface ChatStreamEvent {
    /** 增量文本。 */
    data class Delta(val text: String) : ChatStreamEvent

    /**
     * 增量**思考过程**。与 [Delta] 分开传，上层才能分别渲染。
     *
     * 早先的实现**直接丢掉了它**——推理模型把思考放在 `reasoning_content`，
     * 与 `content` 完全分开。结果是用户看到一个只有蓝点、没有内容的空气泡，
     * 而模型确实已经思考了。
     */
    data class Thinking(val text: String) : ChatStreamEvent

    /**
     * 结束。
     *
     * @param finishReason `stop` 正常；**`length` 表示被 max_tokens 截断**。
     *   两者在上层必须区别对待——截断要提示用户「换个大点的模型或分次问」，
     *   而不能当成正常结束。
     */
    data class Done(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val finishReason: String? = null,
        /** 命中提示词缓存的 token 数（DeepSeek `prompt_cache_hit_tokens`），服务端没给就是 0。 */
        val cachedTokens: Int = 0
    ) : ChatStreamEvent

    data class Failed(val error: ApiError) : ChatStreamEvent
}

/**
 * 降级判定。**独立成纯函数**才能单测——它是这个文件里唯一有分支策略的地方。
 */
object StreamFallback {

    /**
     * 能不能退回非流式。
     *
     * 三条硬规则：
     *
     * 1. **已经吐过内容就绝不降级**。降级会重新请求，用户会看到同一段话出现两遍。
     * 2. **鉴权 / 限流 / 服务端错误不降级**。这些重发一次还是同样的结果，
     *    只是白烧一次配额，还把真实错误信息冲淡了。
     * 3. **用户主动取消不降级**。取消后偷偷重发，用户点了停止却还在烧 token。
     */
    fun shouldFallback(error: ApiError, totalEmitted: Int): Boolean {
        if (totalEmitted > 0) return false
        return when (error.kind) {
            ApiErrorKind.AUTH,
            ApiErrorKind.RATE_LIMIT,
            ApiErrorKind.SERVER,
            ApiErrorKind.NO_KEY,
            ApiErrorKind.CANCELLED -> false

            // 服务端忽略 stream 参数、返回体不是 SSE、连不上：都值得试一次非流式
            ApiErrorKind.BAD_RESPONSE,
            ApiErrorKind.NETWORK,
            ApiErrorKind.TIMEOUT,
            ApiErrorKind.UNKNOWN -> true
        }
    }
}

/**
 * SSE 流式对话客户端。
 *
 * ## 为什么不用 Retrofit
 *
 * Retrofit 的 `Response<T>` 会把 body 一次性读完，流式拿不到增量。
 * 流式这条路必须直接用 OkHttp；非流式降级仍走 Retrofit（[LlmApi]），
 * 那里已经有成熟的错误处理。
 *
 * ## 降级阶梯不在重试里
 *
 * 早期版本把这些降级塞进 `repeat(n)` 的重试，n 不够就静默失效
 * （见 docs/DECISIONS.md 的 v0.1.13 事故）。这里是显式的 if，
 * 一次机会一次机会，走不走得清楚。
 */
class ChatCompletionStream(
    private val client: OkHttpClient,
    private val api: LlmApi
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 流式专用客户端：**取消整体调用超时**。
     *
     * [HttpFactory] 给普通请求设了 300s call timeout，那是给「一次性拿完」设计的。
     * 流式下用户和模型一问一答可能超过 5 分钟，届时 socket 还连着却被强行掐断，
     * 表现为「答到一半断了」。安全性由 120s 读超时兜底（读不到任何字节才算卡死），
     * 正常情况下模型不会沉默 120 秒。
     */
    private val streamingClient: OkHttpClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 发起一次对话。
     *
     * 内部顺序：先流式，[StreamFallback] 判定可以降级时才发非流式。
     * **两条路对上层完全一样**——都是 [ChatStreamEvent] 序列，UI 察觉不到区别。
     *
     * @param effort 思考强度。按 [ReasoningFallback.attempts] 生成的候选链逐个试：
     *   服务端不认当前字段名（HTTP 400）就换下一个，正常服务端第一次即成功。
     *   已经吐出过内容时绝不换字段重发——那会让用户看到同一段话出现两遍。
     */
    fun complete(
        profile: LlmProfile,
        request: ChatRequest,
        effort: ReasoningEffort = ReasoningEffort.OFF
    ): Flow<ChatStreamEvent> = flow {
        var deltas = 0
        var thinkings = 0
        var failure: ApiError? = null
        var ended = false

        val attempts = ReasoningFallback.attempts(effort)
        try {
            for ((index, attempt) in attempts.withIndex()) {
                var attemptFailure: ApiError? = null
                rawStream(
                    profile,
                    request.withReasoning(attempt?.first, attempt?.second)
                ).collect { event ->
                    when (event) {
                        is RawEvent.Delta -> {
                            if (event.text.isNotEmpty()) {
                                deltas++
                                emit(ChatStreamEvent.Delta(event.text))
                            }
                        }

                        is RawEvent.Thinking -> {
                            if (event.text.isNotEmpty()) {
                                thinkings++
                                emit(ChatStreamEvent.Thinking(event.text))
                            }
                        }

                        is RawEvent.Ended -> {
                            ended = true
                            emit(
                                ChatStreamEvent.Done(
                                    event.promptTokens,
                                    event.completionTokens,
                                    event.finishReason,
                                    event.cachedTokens
                                )
                            )
                        }

                        is RawEvent.Failed -> attemptFailure = event.error
                    }
                }
                if (ended) return@flow
                // 已经吐过内容：绝不换字段重发（用户会看到重复输出或长停顿）
                if (deltas + thinkings > 0) {
                    failure = attemptFailure
                    break
                }
                failure = attemptFailure ?: failure
                // 只有「服务端不认这个字段名」才值得换下一个候选；
                // 鉴权 / 限流 / 服务端错误换字段再发还是同样结果，直接走原降级逻辑
                if (attemptFailure == null || !ReasoningFallback.isUnknownFieldError(attemptFailure)) break
                if (index == attempts.lastIndex) break
            }
        } catch (cancelled: CancellationException) {
            // 用户按了停止或页面销毁。**绝不能当成错误降级重发**。
            throw cancelled
        } catch (t: Throwable) {
            failure = HttpFactory.throwableError(t)
        }

        if (ended) return@flow

        // 一帧都没解出来：可能是服务端忽略了 stream 参数、返回体不是 SSE、
        // 或者连不上。只要思考或正文吐出过任何一帧，就绝不能降级重发（否则会看到重复输出或长停顿）。
        val totalEmitted = deltas + thinkings
        val reason = failure
            ?: if (totalEmitted == 0) ApiError(ApiErrorKind.BAD_RESPONSE, "模型未返回流式内容")
            else return@flow

        if (!StreamFallback.shouldFallback(reason, totalEmitted)) {
            if (failure != null) emit(ChatStreamEvent.Failed(reason))
            return@flow
        }

        emitAll(nonStreaming(profile, request, totalEmitted, attempts.first()))
    }

    // ------------------------------------------------------------ 内部事件

    private sealed interface RawEvent {
        data class Delta(val text: String) : RawEvent
        data class Thinking(val text: String) : RawEvent
        data class Ended(
            val promptTokens: Int,
            val completionTokens: Int,
            val finishReason: String?,
            val cachedTokens: Int = 0
        ) : RawEvent
        data class Failed(val error: ApiError) : RawEvent
    }

    // ------------------------------------------------------------ 流式

    private fun rawStream(profile: LlmProfile, request: ChatRequest): Flow<RawEvent> = callbackFlow {
        val call = streamingClient.newCall(buildRequest(profile, request, streaming = true))
        var finished = false

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!finished) {
                    finished = true
                    trySend(RawEvent.Failed(HttpFactory.throwableError(e)))
                    close()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { received ->
                    try {
                        if (!finished) readBody(received) { trySend(it) }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        if (!finished) {
                            finished = true
                            trySend(RawEvent.Failed(HttpFactory.throwableError(t)))
                        }
                    } finally {
                        if (!finished) {
                            finished = true
                            close()
                        }
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    private fun readBody(response: Response, emit: (RawEvent) -> Unit) {
        val body = response.body
        if (!response.isSuccessful) {
            val text = runCatching { body?.string().orEmpty() }.getOrDefault("")
            emit(RawEvent.Failed(HttpFactory.httpError(response.code, text)))
            return
        }
        if (body == null) {
            emit(RawEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "响应体为空")))
            return
        }

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            // 服务端直接返回了 JSON —— 它**忽略了 stream 参数**。
            // 直接就地解析，不要重新发一次请求。
            readPlainJson(body.string(), emit)
            return
        }

        val parser = SseLineParser(json)
        val result = drainSse(
            readLine = { body.source().readUtf8Line() },
            parser = parser,
            onThinking = { emit(RawEvent.Thinking(it)) },
            onDelta = { emit(RawEvent.Delta(it)) }
        )
        // 无论是怎么结束的（[DONE] / EOF / finish_reason），都发一次 Ended。
        // 上层靠它把消息状态从「生成中」翻成完成。
        emit(
            RawEvent.Ended(
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                finishReason = result.finishReason,
                cachedTokens = result.cachedTokens
            )
        )
    }

    /** 服务端忽略 stream、直接给 JSON 时走这里。 */
    private fun readPlainJson(text: String, emit: (RawEvent) -> Unit) {
        val parsed = runCatching { json.decodeFromString(ChatResponse.serializer(), text) }.getOrNull()
        if (parsed == null) {
            emit(RawEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "响应不是有效的 SSE 或 JSON")))
            return
        }
        val choice = parsed.choices.firstOrNull()
        if (choice == null) {
            emit(RawEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "模型未返回任何结果")))
            return
        }
        // 降级到非流式后思考也要输出：否则用户会看到「模型不回答」
        choice.message?.reasoningContent?.takeIf { it.isNotBlank() }
            ?.let { emit(RawEvent.Thinking(it)) }
        choice.message?.content?.takeIf { it.isNotEmpty() }?.let { emit(RawEvent.Delta(it)) }
        emit(
            RawEvent.Ended(
                promptTokens = parsed.usage?.promptTokens ?: 0,
                completionTokens = parsed.usage?.completionTokens ?: 0,
                finishReason = choice.finishReason,
                cachedTokens = parsed.usage?.promptCacheHitTokens ?: 0
            )
        )
    }

    // ------------------------------------------------------------ 非流式降级

    private suspend fun nonStreaming(
        profile: LlmProfile,
        request: ChatRequest,
        deltasAlreadyEmitted: Int,
        reasoning: Pair<String, String>? = null
    ): Flow<ChatStreamEvent> = flow {
        if (deltasAlreadyEmitted > 0) return@flow

        // 非流式降级同样带上思考字段：第一次尝试用的那个。
        val body = request.copy(stream = false)
            .withReasoning(reasoning?.first, reasoning?.second)
        val response = try {
            api.chatCompletions(
                url = profile.chatCompletionsUrl(),
                authorization = "Bearer ${profile.apiKey}",
                request = body
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emit(ChatStreamEvent.Failed(HttpFactory.throwableError(t)))
            return@flow
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
            emit(ChatStreamEvent.Failed(HttpFactory.httpError(response.code(), body)))
            return@flow
        }

        val parsed = response.body()
        if (parsed == null || parsed.choices.isEmpty()) {
            emit(ChatStreamEvent.Failed(ApiError(ApiErrorKind.BAD_RESPONSE, "模型未返回任何结果")))
            return@flow
        }
        val choice = parsed.choices.first()
        choice.message?.reasoningContent?.takeIf { it.isNotBlank() }
            ?.let { emit(ChatStreamEvent.Thinking(it)) }
        val text = choice.message?.content.orEmpty()
        if (text.isNotEmpty()) emit(ChatStreamEvent.Delta(text))
        emit(
            ChatStreamEvent.Done(
                promptTokens = parsed.usage?.promptTokens ?: 0,
                completionTokens = parsed.usage?.completionTokens ?: 0,
                finishReason = choice.finishReason,
                cachedTokens = parsed.usage?.promptCacheHitTokens ?: 0
            )
        )
    }

    // ------------------------------------------------------------ 请求构造

    private fun buildRequest(profile: LlmProfile, request: ChatRequest, streaming: Boolean): Request {
        // 对话**绝不能开 json_object**：会把回复强行掰成 JSON，
        // 用户看到的是一堆花括号而不是讲解。
        val body = request.copy(
            temperature = TEMPERATURE,
            max_tokens = request.max_tokens.takeIf { it > 0 } ?: MAX_TOKENS,
            responseFormat = null,
            stream = streaming
        )
        val payload = json.encodeToString(ChatRequest.serializer(), body)
        return Request.Builder()
            .url(profile.chatCompletionsUrl())
            .header("Authorization", "Bearer ${profile.apiKey}")
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .header("Cache-Control", "no-cache")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    companion object {
        /** 对话场景的温度。规格锁定 0.6。 */
        const val TEMPERATURE = 0.6

        /**
         * 对话的输出预算。
         *
         * 8192 而不是纠错任务的 16384：后者是「多题 + 详细解析」的量级，
         * 对话是「一道题讲清楚」，用不满。
         *
         * **别再往下调。** 4096 时用户反馈「经常限制模型输出长度，
         * 长度太长直接啥都不显示了」——一道稍复杂的推导就撞上限，
         * 正文在句子中间断掉。多数服务端模型的上限是 8192 或更高，
         * 这里取一个两边都安全的值。
         *
         * 真撞上限时会有 [FINISH_REASON_LENGTH]，界面挂常驻的「已截断」标记，
         * 而不是静默给一段残缺回答。
         */
        /** 单次回答上限；长题目需要完整推导，仍由 finish_reason=length 标记真实截断。 */
        const val MAX_TOKENS = 16_384

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 这一帧是否意味着**生成已经结束**。
 *
 * ## 为什么需要它
 *
 * 用户报告：「输出结束了还是终止图标」——内容、表格全都渲染完了，
 * 右下角却还是「停止」方块，点一下才恢复。
 *
 * 界面上那个图标由 `ChatUiState.isStreaming` 决定，而它只在
 * `collectStream` 的 `finally` 里被置回 `false`。也就是说：
 * **`collect` 不返回，`finally` 就永远不跑，图标就永远是停止。**
 *
 * 原来的收尾只有两条路：`data: [DONE]` 哨兵，或者读到 EOF。
 * 而实测下来有些服务端**两条都不给**——发完最后一个 delta
 * （`finish_reason` 已经带上了）就把 HTTP 连接挂着既不发 `[DONE]` 也不关。
 * 于是 `source.readUtf8Line()` 永久阻塞在等下一行，界面就这么卡住了。
 *
 * 按 OpenAI 规范，`finish_reason` 非空就表示这一轮生成结束，
 * 后面**只可能**再跟一个 usage 帧（`stream_options.include_usage`）。
 * 早先为了留那一帧而继续读，代价就是「可能永远读不到」——
 * 而丢掉的 token 用量只写进数据库、**界面上根本不显示**。
 * 拿一个用户看得见的死等，去换一个他看不见的数字，不划算。
 *
 * @param finishReason 该帧携带的 `finish_reason`
 */
fun isTerminalFinish(finishReason: String?): Boolean = when (finishReason) {
    null -> false
    // 服务端偶尔发空串占位，等于没有
    "", "null" -> false
    else -> true
}

/** 一条 SSE 流读完之后的累计结果。 */
data class SseStreamResult(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val finishReason: String? = null,

    /** 命中提示词缓存的 token 数累计（DeepSeek `prompt_cache_hit_tokens`）。 */
    val cachedTokens: Int = 0,

    /** 因什么而结束的：`done` 哨兵 / `eof` 连接关闭 / `finish_reason` 已到齐。 */
    val endedBy: String = ""
)

/**
 * 把 SSE 流读到结束。
 *
 * ## 三条收尾路径
 *
 * | 触发 | 场景 |
 * |---|---|
 * | `done` | 服务端发了 `data: [DONE]` |
 * | `eof` | 服务端关了连接（不少中转站不发哨兵） |
 * | `finish_reason` | **本次修的就是这条** |
 *
 * 早先只有前两条。实测有些服务端发完最后一帧（`finish_reason` 已带）就
 * 把连接**挂着**：既不发 `[DONE]`，也不关。于是下一行 `readUtf8Line()`
 * 永久阻塞，`collect` 不返回，`ChatViewModel.collectStream` 的 `finally`
 * 永远不跑——`isStreaming` 一直是 true，右下角就一直显示「停止」。
 * 用户看到的是「内容早出完了，图标还是停止」。
 *
 * ## 为什么把 [readLine] 做成参数
 *
 * 「挂住」在真实代码里是 `readUtf8Line()` 阻塞，单测里没法复现。
 * 抽成注入的 lambda 之后，测试可以用一个**再调用就抛异常**的源来代表
 * 「服务端不吭声了」——真的挂住时，读到这里就会炸，
 * 而不是安静地返回、把 bug 放过去。
 *
 * @param readLine 读下一行；返回 `null` 表示 EOF。**不返回**即代表连接挂住。
 */
fun drainSse(
    readLine: () -> String?,
    parser: SseLineParser = SseLineParser(),
    onThinking: (String) -> Unit = {},
    onDelta: (String) -> Unit = {}
): SseStreamResult {
    var finishReason: String? = null
    var promptTokens = 0
    var completionTokens = 0
    var cachedTokens = 0

    while (true) {
        val line = readLine() ?: return SseStreamResult(
            promptTokens, completionTokens, finishReason, cachedTokens, endedBy = "eof"
        )
        val event = parser.accept(line) ?: continue
        when (event) {
            is SseEvent.Chunk -> {
                event.thinking.takeIf { it.isNotEmpty() }?.let(onThinking)
                event.text.takeIf { it.isNotEmpty() }?.let(onDelta)
                // finish_reason / usage 只在特定帧出现，取**最后一个非空值**
                event.finishReason?.let { finishReason = it }
                if (event.promptTokens > 0) promptTokens = event.promptTokens
                if (event.completionTokens > 0) completionTokens = event.completionTokens
                if (event.cachedTokens > 0) cachedTokens = event.cachedTokens

                // 到了就不再多读一行——多读那一下可能就是永远。
                if (isTerminalFinish(event.finishReason)) {
                    return SseStreamResult(
                        promptTokens, completionTokens, finishReason, cachedTokens,
                        endedBy = "finish_reason"
                    )
                }
            }

            SseEvent.Done -> return SseStreamResult(
                promptTokens, completionTokens, finishReason, cachedTokens, endedBy = "done"
            )

            // 坏帧不中断：可能只是某一行被代理改坏了。累计数量交给上层判断。
            is SseEvent.Malformed -> Unit

            SseEvent.Ignore -> Unit
        }
    }
}
