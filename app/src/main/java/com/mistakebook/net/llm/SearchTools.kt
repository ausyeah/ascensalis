package com.mistakebook.net.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 联网搜索的 function calling 定义（v0.0.43）。
 *
 * ## 为什么从「盲目注入」改成「工具调用」
 *
 * 旧机制（v0.0.41）：开关一开，每轮请求前都把最后一条 user 消息当 query
 * 搜一次，结果作为一条 user 消息插在提问之前。两个后果：
 * 1. **该搜的不该搜的都搜**——纯数学推导、代码解释也白搜一次，浪费额度、
 *    拖慢首字，搜回来的网页还可能把模型带偏；
 * 2. **query 是客户端猜的**——把整条提问原样当关键词，常常搜不到点子上。
 *
 * 新机制：把搜索包装成一个标准工具，**模型自己决定**要不要调、拿什么
 * 关键词调。这与 DeepSeek 官方 tool_calls 文档、Tavily 面向 agent 的
 * 用法完全同构（`tools` → `tool_calls` → 客户端执行 → `role:tool` 回传
 * → 续请求）。执行侧仍是既有的两个出口：Tavily HTTP API 与 DeepSeek
 * Anthropic 端点的服务端搜索（见 `WebSearch.kt`）。
 *
 * ## 描述怎么写
 *
 * 工具描述是模型唯一的决策依据，必须写清**什么时候该调、什么时候别调**：
 * 只写「搜索互联网」的话，模型会连 `1+1=?` 都去搜一次。
 */
object SearchTools {

    /** 工具名。回传的 tool 消息与调用匹配都靠它。 */
    const val WEB_SEARCH = "web_search"

    /** 一次回答里允许的最大工具轮数（防模型无限调搜索）。 */
    const val MAX_TOOL_ROUNDS = 3

    /**
     * web_search 工具的完整定义。
     *
     * JSON Schema 保持最小：一个必填 string 参数。参数越多模型越容易
     * 填错，而搜索真正需要的只有一个关键词。
     */
    fun webSearchTool(): LlmTool = LlmTool(
        type = "function",
        function = ToolFunction(
            name = WEB_SEARCH,
            description = "搜索互联网，获取最新信息与事实依据。" +
                "当且仅当：问题涉及时事/新闻/价格/版本/发布等时效性内容，" +
                "或涉及你不确定的客观事实、需要引用来源时，才调用本工具。" +
                "纯数学推导、代码解释、概念讲解、文案写作等不依赖外部信息的" +
                "场景不要调用。",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "搜索关键词：简短、聚焦事实本身，" +
                                        "不要带「请问」「帮我查一下」这类口语。" +
                                        "例如「2025 年高考数学 全国卷 平均分」。"
                                )
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            }
        )
    )

    /** 当前启用的工具全集。 */
    fun all(): List<LlmTool> = listOf(webSearchTool())

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从工具调用的 arguments（JSON **字符串**）里取 query（**纯函数，可单测**）。
     *
     * 模型偶尔给出坏 JSON 或漏字段——返回 null，由调用方把「参数缺失」
     * 作为工具结果回传，让模型自己修正，而不是把整轮对话打死。
     */
    fun parseSearchQuery(arguments: String): String? {
        if (arguments.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return null
        // 只收**字符串** query：模型偶尔把 query 发成数字（`{"query":123}`），
        // contentOrNull 会把它转成 "123" 当真去搜——搜不到还浪费一轮工具循环。
        // 非字符串一律视为参数缺失，回传后让模型自己改成字符串。
        val query = obj["query"] as? JsonPrimitive ?: return null
        return query.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}
