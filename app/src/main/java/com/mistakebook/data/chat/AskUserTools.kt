package com.mistakebook.data.chat

import com.mistakebook.net.llm.LlmTool
import com.mistakebook.net.llm.ToolFunction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * 「反问用户」工具（v0.0.128，issue #46）。
 *
 * 用户给的参考是电脑端 ask user question：模型拿不准时抛出一张选项卡片，
 * 用户点选后结果回到对话里继续跑。要点四条都实现：
 * - **单选 / 多选**：`multi_select` 决定 UI 用 RadioButton 还是 Checkbox；
 * - **推荐项**：`recommended` 标记默认高亮、给「推荐」角标；
 * - **默认选项**：`defaults` 预选中，用户不点也能直接确认；
 * - **超时跳过**：到时自动返回「用户未回答」，模型自己接着走，
 *   不会把整轮生成卡死（最坏情况是用户离开）。
 *
 * ## 桥怎么搭
 *
 * 生成跑在 [com.mistakebook.ui.chat.ChatGenerationService] 的 IO 协程里，
 * 界面在另一个进程内对象里。服务侧 `await` 挂起等结果，UI 侧把选择写回：
 * 用进程单例 [ChatAskBridge]（与 ChatGenerationBus 同一路数）。
 * 服务被取消（用户按停止）时协程取消，挂起随之解除，不会漏一个等待者。
 */
object AskUserTools {

    const val ASK_USER = "ask_user"

    /** 默认等待上限：用户没在看就 90 秒后跳过。 */
    const val DEFAULT_TIMEOUT_MS = 90_000L

    /** 上限，避免模型传一个荒唐的超时把对话挂半小时。 */
    private const val MAX_TIMEOUT_MS = 300_000L

    fun tool(): LlmTool = LlmTool(
        function = ToolFunction(
            name = ASK_USER,
            description = "需要用户做选择或补充关键信息时，向用户提一个问题并给出选项。" +
                "例如不确定他想练哪种题型、要按中考还是高考标准批改、要生成长文还是提纲。" +
                "能用常识合理假设的就不要问——只在答案会实质改变你的输出时才调用。" +
                "调用后你会收到用户的选择；超时未回答会返回「用户未回答」，请按推荐项继续。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "question",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "要问用户的一句话，尽量简短（不超过 40 字）")
                            }
                        )
                        put(
                            "options",
                            buildJsonObject {
                                put("type", "array")
                                put("description", "2~6 个候选项")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "label",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put("description", "选项文案，不超过 16 字")
                                                    }
                                                )
                                                put(
                                                    "description",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put("description", "可选的补充说明，一行")
                                                    }
                                                )
                                            }
                                        )
                                        put(
                                            "required",
                                            buildJsonArray { add(JsonPrimitive("label")) }
                                        )
                                    }
                                )
                            }
                        )
                        put(
                            "multi_select",
                            buildJsonObject {
                                put("type", "boolean")
                                put("description", "true 表示可多选，默认单选")
                            }
                        )
                        put(
                            "recommended",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "推荐选项的下标（从 0 开始），可省略")
                            }
                        )
                        put(
                            "defaults",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject { put("type", "integer") }
                                )
                                put("description", "默认预选的下标列表，可省略")
                            }
                        )
                        put(
                            "timeout_seconds",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "等待秒数，默认 90，最多 300")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("question")); add(JsonPrimitive("options")) })
            }
        )
    )

    /** 一个候选项。 */
    data class Option(val index: Int, val label: String, val description: String?)

    /** 解析后的一次提问。 */
    data class Ask(
        val question: String,
        val options: List<Option>,
        val multiSelect: Boolean,
        val recommendedIndex: Int?,
        val defaultIndexes: Set<Int>,
        val timeoutMs: Long
    )

    /**
     * 解析工具参数；不合法（没有问题或少于两个选项）时返回 null，
     * 调用方给模型一句可纠正的提示。
     */
    fun parse(arguments: String): Ask? = runCatching {
        val root = Json.parseToJsonElement(arguments).jsonObject
        val question = root["question"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return@runCatching null
        val optionsArray = root["options"] as? JsonArray ?: return@runCatching null
        val options = optionsArray.mapIndexedNotNull { index, element ->
            val label = element.jsonObject["label"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val desc = element.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
            Option(index, label, desc)
        }
        if (options.size < 2) return@runCatching null
        val multi = root["multi_select"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        val recommended = root["recommended"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?.takeIf { it in options.indices }
        val defaults = (root["defaults"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
            ?.filter { it in options.indices }
            ?.toSet()
            ?: emptySet()
        val timeoutSec = root["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val timeoutMs = ((timeoutSec ?: (DEFAULT_TIMEOUT_MS / 1000)) * 1000L)
            .coerceIn(5_000L, MAX_TIMEOUT_MS)
        Ask(
            question = question,
            options = options,
            multiSelect = multi,
            recommendedIndex = recommended,
            defaultIndexes = if (defaults.isEmpty() && recommended != null) setOf(recommended) else defaults,
            timeoutMs = timeoutMs
        )
    }.getOrNull()

    /**
     * 服务侧执行：挂起等待用户选择，超时返回跳过话术。
     *
     * 取消（用户按停止）时挂起随协程一起结束——[ChatAskBridge] 的清理
     * 在 finally 里做，界面上的卡片会被撤掉。
     */
    suspend fun execute(arguments: String, askId: String, sessionId: Long): String {
        val ask = parse(arguments)
            ?: return "提问未执行：question 必填且 options 至少两项，请修正参数后重试。"
        val answer = try {
            withTimeoutOrNull(ask.timeoutMs) {
                ChatAskBridge.awaitAnswer(askId, sessionId, ask)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ChatAskBridge.clearIfMatches(askId)
            throw cancelled
        } finally {
            ChatAskBridge.clearIfMatches(askId)
        }
        return if (answer == null) {
            val fallback = ask.options.firstOrNull { it.index == ask.recommendedIndex }
                ?: ask.options.first()
            "用户没有在 ${ask.timeoutMs / 1000} 秒内回答，已按「${fallback.label}」继续。" +
                "不要再次提问同一件事。"
        } else {
            val labels = answer.indexes
                .mapNotNull { index -> ask.options.firstOrNull { it.index == index }?.label }
            if (answer.skipped || labels.isEmpty()) {
                "用户选择了跳过这个问题。请按最合理的方式自行决定，不要再问同一件事。"
            } else {
                "用户选择了：${labels.joinToString("、")}。据此继续。"
            }
        }
    }
}

/**
 * 服务 ↔ 界面的提问桥（进程单例，v0.0.128）。
 *
 * 与 [com.mistakebook.ui.chat.ChatGenerationBus] 的区别：总线是单向广播
 * （服务 → 界面），这里需要**双向**——所以额外持一个挂起点。
 */
object ChatAskBridge {

    /** 界面上的待答卡片；null = 没有待答问题。 */
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var awaiting: kotlinx.coroutines.CancellableContinuation<Answer>? = null

    /** 一次待答的提问（界面据此渲染卡片）。 */
    data class Pending(
        val askId: String,
        val sessionId: Long,
        val question: String,
        val options: List<AskUserTools.Option>,
        val multiSelect: Boolean,
        val recommendedIndex: Int?,
        val defaultIndexes: Set<Int>,
        val timeoutMs: Long
    )

    /** 用户的回答。[skipped] = 主动点「跳过」。 */
    data class Answer(val indexes: Set<Int>, val skipped: Boolean)

    /** 服务侧：挂起直到 UI 回答或被取消。 */
    suspend fun awaitAnswer(
        askId: String,
        sessionId: Long,
        ask: AskUserTools.Ask
    ): Answer = suspendCancellableCoroutine { continuation ->
        awaiting = continuation
        _pending.value = Pending(
            askId = askId,
            sessionId = sessionId,
            question = ask.question,
            options = ask.options,
            multiSelect = ask.multiSelect,
            recommendedIndex = ask.recommendedIndex,
            defaultIndexes = ask.defaultIndexes,
            timeoutMs = ask.timeoutMs
        )
        continuation.invokeOnCancellation {
            awaiting = null
            _pending.value = null
        }
    }

    /** UI 侧：提交选择（[skipped] = 直接跳过）。 */
    fun submit(askId: String, indexes: Set<Int>, skipped: Boolean = false) {
        val current = _pending.value ?: return
        if (current.askId != askId) return
        _pending.value = null
        val continuation = awaiting
        awaiting = null
        if (continuation?.isActive == true) {
            continuation.resume(Answer(indexes, skipped))
        }
    }

    /** 服务侧清理（超时 / 取消后撤掉卡片）。 */
    fun clearIfMatches(askId: String) {
        if (_pending.value?.askId == askId) _pending.value = null
    }

    /** 会话切换时撤掉不属于当前会话的卡片（界面侧调用）。 */
    fun clearForOtherSession(sessionId: Long) {
        if (_pending.value?.sessionId != null && _pending.value?.sessionId != sessionId) {
            _pending.value = null
        }
    }
}