package com.mistakebook.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 会话联网搜索（v0.0.41）。
 *
 * ## 两个实现，一条注入路径
 *
 * - **DeepSeek**（默认）：官方搜索走 Anthropic 兼容端点
 *   `/anthropic/v1/messages`，在请求里声明服务端工具
 *   `web_search_20250305`，服务端完成搜索后把结构化来源放进
 *   `web_search_tool_result` 内容块——复用用户已配的 DeepSeek Key，
 *   无需新密钥；
 * - **Tavily**：独立搜索服务，POST /search 返回 results 数组，
 *   需要单独的 Key（免费档每月 1000 次）。
 *
 * 两条路都汇成 [WebSearchResult] 列表，经 [WebSearchFormatter.format]
 * 变成注入上下文的文本块——模型怎么用这些结果是它自己的事，
 * 我们只负责把新鲜的网页信息递到它面前。
 *
 * ## 搜索是尽力而为
 *
 * 任何失败（无 Key / 网络 / 解析）由调用方吞掉并跳过注入，
 * 绝不因为搜索失败把整轮对话打死——没有联网结果的普通回答
 * 也远好过报错。
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/** 搜索出口的配置形态（存 DataStore 的是 name）。 */
enum class ChatSearchProvider {
    /** DeepSeek 官方服务端搜索（复用 DeepSeek 渠道的 Key）。 */
    DEEPSEEK,

    /** Tavily 独立搜索服务（独立 Key）。 */
    TAVILY;

    companion object {
        fun fromKey(key: String?): ChatSearchProvider =
            if (key.equals("tavily", ignoreCase = true)) TAVILY else DEEPSEEK
    }
}

object WebSearchFormatter {

    /**
     * 把搜索结果格式化成注入上下文的文本块。
     *
     * 无结果返回空串（调用方不注入任何东西）。声明时效与「仅供参考」：
     * 模型不该把搜索摘要当成权威事实，也不该假装这些知识它本来就会。
     */
    fun format(query: String, results: List<WebSearchResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【联网搜索结果】以下是针对「").append(query.take(120))
            .append("」的实时网页检索摘要（").append(results.size)
            .append("条，获取时间 ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date()))
            .append("）。回答时可引用这些信息并注明来源；若与你的知识冲突，以搜索结果为时效性依据；与题目无关的信息忽略。\n")
        results.forEachIndexed { index, r ->
            sb.append("\n[").append(index + 1).append("] ").append(r.title.ifBlank { "(无标题)" })
            if (r.url.isNotBlank()) sb.append("\n").append(r.url)
            if (r.snippet.isNotBlank()) sb.append("\n").append(r.snippet.take(400))
        }
        return sb.toString()
    }
}

/** Tavily：POST /search，results 数组里是 {title,url,content}。 */
object TavilySearch {

    private val json = Json { ignoreUnknownKeys = true }

    /** 纯解析函数（可单测）：Tavily 响应体 → 结果列表。 */
    fun parse(body: String): List<WebSearchResult> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val arr = (root["results"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            WebSearchResult(
                title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                url = url,
                snippet = (o["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            )
        }
    }

    suspend fun search(
        client: OkHttpClient,
        apiKey: String,
        query: String
    ): List<WebSearchResult> {
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("api_key", kotlinx.serialization.json.JsonPrimitive(apiKey))
                put("query", kotlinx.serialization.json.JsonPrimitive(query.take(400)))
                put("max_results", kotlinx.serialization.json.JsonPrimitive(5))
                put("search_depth", kotlinx.serialization.json.JsonPrimitive("basic"))
            }
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) return emptyList()
            return parse(text)
        }
    }
}

/**
 * DeepSeek 官方服务端搜索：Anthropic 兼容 `/anthropic/v1/messages`
 * 携带 `web_search_20250305` 服务端工具。
 *
 * 来源从 `web_search_tool_result` 内容块取（结构化，绝不从模型
 * 自由文本里抓 URL）；摘录从 text 块的 citations 里按 url 关联
 * （cited_text）——官方教程口径。
 */
object DeepSeekSearch {

    private val json = Json { ignoreUnknownKeys = true }

    /** 纯解析函数（可单测）：/messages 响应体 → 结果列表。 */
    fun parse(body: String): List<WebSearchResult> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val content = (root["content"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        val byUrl = linkedMapOf<String, WebSearchResult>()
        val snippets = linkedMapOf<String, String>()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                "web_search_tool_result" -> {
                    // 块内可能是 {"web_search_tool_result": [条目...]} 或直接是数组，两形态都容
                    val entries = (block["web_search_tool_result"] as? kotlinx.serialization.json.JsonArray)
                        ?: continue
                    for (entry in entries) {
                        val o = entry.jsonObject
                        val url = (o["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (url.isBlank() || url in byUrl) continue
                        byUrl[url] = WebSearchResult(
                            title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            url = url,
                            snippet = ""
                        )
                    }
                }

                "text" -> {
                    val citations = (block["citations"] as? kotlinx.serialization.json.JsonArray) ?: continue
                    for (c in citations) {
                        val o = c.jsonObject
                        val url = (o["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val cited = (o["cited_text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (url.isNotBlank() && cited.isNotBlank() && url !in snippets) {
                            snippets[url] = cited
                        }
                    }
                }
            }
        }
        return byUrl.values.map { it.copy(snippet = snippets[it.url].orEmpty()) }
    }

    suspend fun search(
        client: OkHttpClient,
        apiKey: String,
        query: String
    ): List<WebSearchResult> {
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive("deepseek-flash"))
                put("max_tokens", kotlinx.serialization.json.JsonPrimitive(1024))
                put(
                    "messages",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                                put(
                                    "content",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                                put(
                                                    "text",
                                                    kotlinx.serialization.json.JsonPrimitive(
                                                        "Perform a web search for: " + query.take(400)
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
                put(
                    "tools",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("web_search_20250305"))
                                put("name", kotlinx.serialization.json.JsonPrimitive("web_search"))
                                put("max_uses", kotlinx.serialization.json.JsonPrimitive(3))
                            }
                        )
                    }
                )
            }
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepseek.com/anthropic/v1/messages")
            .header("x-api-key", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("anthropic-version", "2023-06-01")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) return emptyList()
            return parse(text)
        }
    }
}

/** 建搜索专用的 OkHttpClient：比对话更长的超时（服务端搜索要真爬网页）。 */
fun searchHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()
