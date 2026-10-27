package com.mistakebook.net.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    /**
     * 默认给到 16384 而不是 4096。
     * 多题整理 + 详细解析（每题都要分步推导）的输出轻松超过 4000 tokens，
     * 一旦被截断，JSON 就是残缺的，解析必然失败——表现为「AI 整理老是失败，
     * 尤其多题的时候」。4096 是这里最主要的事故源。
     * 调用方可在真正需要时覆盖。
     */
    val max_tokens: Int = 16384,
    @SerialName("response_format") val responseFormat: ResponseFormat? = ResponseFormat("json_object"),

    /**
     * 思考强度，三个候选字段**同一时刻只有一个非空**（见 [withReasoning]）。
     *
     * 为什么是三个而不是一个：各服务端字段名不统一——
     * OpenAI 系用 `reasoning_effort`，部分中转站认 `thinking`，还有认 `reasoning` 的。
     * 只发一个字段名，遇到不认的服务端就是 HTTP 400、整次请求失败。
     * 发送方按候选链逐个试，哪个 400 就换下一个（见 ReasoningFallback）。
     */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("thinking") val thinking: String? = null,
    @SerialName("reasoning") val reasoning: String? = null,
    val stream: Boolean = false,

    /**
     * 流式用量回传开关。
     *
     * **为什么需要它**：OpenAI 兼容规范里，流式响应**默认不回 usage**——
     * 不带这个字段，finish_reason 之后再无任何帧，token 用量统计永远是 0
     * （用户反馈「用量概览次数有了但具体量都是 0」的第一层原因）。
     *
     * 由 [ChatCompletionStream.buildRequest] 在流式时统一注入，
     * 调用方不需要知道这个字段的存在；非流式响应体本来就带 usage，不发。
     */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,

    /**
     * 工具（function calling）定义；null 表示不带，行为与改造前完全一致。
     *
     * v0.0.43 起联网搜索改走这条链路：模型自己决定要不要搜、搜什么，
     * 而不是请求前盲目检索注入。只有 [com.mistakebook.data.prefs.SettingsSnapshot.chatSearchEnabled]
     * 开启时才由调用方填入（见 `ChatGenerationService` 的 tool loop）。
     */
    val tools: List<LlmTool>? = null
) {
    /**
     * 用指定的字段名携带思考强度；[field] 为 null 表示三个字段全不发。
     */
    fun withReasoning(field: String?, value: String?): ChatRequest = copy(
        reasoningEffort = if (field == "reasoning_effort") value else null,
        thinking = if (field == "thinking") value else null,
        reasoning = if (field == "reasoning") value else null
    )
}

/**
 * 消息内容既可以是纯字符串，也可以是多模态片段数组（text + image_url）。
 * content 用 JsonElement 承载两种形态，由调用方决定。
 *
 * v0.0.43：tool loop 需要三种新角色形态，都在下面——
 * - assistant 带 [toolCalls]：模型请求调用工具；
 * - role=tool 带 [toolCallId]：客户端执行结果回传；
 * - [reasoningContent]：DeepSeek 思考模式**必须**在后续请求里完整回传，
 *   否则 API 直接 400（官方文档明确要求）。
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement,

    /** assistant 消息：模型发起的工具调用请求。 */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,

    /** tool 消息：回指被执行的调用 id。 */
    @SerialName("tool_call_id") val toolCallId: String? = null,

    /** tool 消息：被调用的函数名（部分服务端要求）。 */
    val name: String? = null,

    /** DeepSeek 思考模式回传字段；其他服务端不认识，故按渠道条件发送。 */
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

/** 一个工具（OpenAI function calling 格式）。 */
@Serializable
data class LlmTool(
    val type: String = "function",
    val function: ToolFunction
)

/** 工具的 schema：名字、用途说明、参数 JSON Schema。 */
@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    /** JSON Schema（object 类型）。用 JsonElement 承载，省一整棵 schema 树。 */
    val parameters: JsonElement
)

/** assistant 消息里的一次工具调用请求。 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,

    /** 参数 **JSON 字符串**（模型给的就是字符串，不是对象）。 */
    val arguments: String
)

@Serializable
data class ResponseFormat(
    val type: String
)

/**
 * 流式用量开关。
 *
 * `include_usage = true` 时，服务端才会在 `finish_reason` 帧之后、
 * `[DONE]` 之前补一个携带 `usage` 的帧。默认 false（OpenAI 规范行为），
 * 不带就是「流式响应永远没有用量」。
 */
@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class ChatContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image_url"
    }
}

@Serializable
data class ImageUrl(
    val url: String
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChoiceMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChoiceMessage(
    val role: String = "",
    val content: String = "",

    /**
     * 非流式路径下的思考过程。
     *
     * 流式与非流式的字段名**不同**（流式在 `delta` 里，非流式在 `message` 里），
     * 所以两边都要单独声明。只改一边的结果是——
     * 模型支持流式时看起来正常，端点不支持需要降级到非流式时思考流失。
     */
    @SerialName("reasoning_content") val reasoningContent: String = "",

    /**
     * 非流式路径下的工具调用请求（v0.0.43）。
     *
     * 流式在 `delta.tool_calls` 里分片给，非流式在 `message.tool_calls` 里
     * 一次给全——两边都要声明，否则降级到非流式时工具调用整段丢失。
     */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,

    /**
     * 命中提示词缓存的输入 token（DeepSeek `prompt_cache_hit_tokens`）。
     * 缓存命中部分的输入单价只有普通的十分之一量级，单独统计才看得清
     * 「钱花在哪」。多数中转站不给这个字段，为 0。
     */
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0
)

@Serializable
data class ModelsResponse(
    val data: List<ModelItem> = emptyList()
)

@Serializable
data class ModelItem(
    val id: String = ""
)
