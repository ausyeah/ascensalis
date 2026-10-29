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
 * 会话联网搜索。
 *
 * ## 两个实现，一条注入路径
 *
 * - **DeepSeek**：官方原生 Search API（`/v1/tools/search`），Bearer 鉴权，
 *   结构化 results——与 Tavily 同级的纯搜索服务，**不绑定对话模型**：
 *   对话用别家大模型时，单独配一把 DeepSeek Key 即可调用它的搜索；
 * - **Tavily**：独立搜索服务，POST /search 返回 results 数组，
 *   需要单独的 Key（免费档每月 1000 次）。
 *
 * 两条路都汇成 [WebSearchResult] 列表，经 [WebSearchFormatter.format]
 * 变成工具结果回传给模型——模型怎么用这些结果是它自己的事，
 * 我们只负责把新鲜的网页信息递到它面前。
 *
 * ## 搜索是尽力而为
 *
 * 任何失败（无 Key / 网络 / 解析）由调用方吞掉并降级说明，
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
    /** DeepSeek 官方原生 Search API（独立 Key，可复用 DeepSeek 模型 Key）。 */
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

    /**
     * Tavily 额度快照（v0.0.57）。
     *
     * ## 为什么要查额度
     *
     * 用户反映「Tavily 搜着搜着就不搜了」——免费额度用尽后 `/search` 返回
     * 432/433 一类错误，但客户端只当成「这次没搜到」，模型继续若无其事地
     * 编答案。有了额度查询，设置页可以直接显示「本月已用 X / Y」，
     * 用尽时明确告诉用户，而不是让模型默默降级。
     *
     * ## 字段语义（以官方 GET /usage 为准）
     *
     * - [keyUsage] / [keyLimit]：**这把 Key** 的已用 / 上限；
     *   [keyLimit] 为 null 表示不限量（付费计划常为 null）。
     * - [planUsage] / [planLimit]：**整个账号**本计费周期已用 / 配额；
     *   同样可能为 null（= 不限量）。
     * - [paygoUsage] / [paygoLimit]：按量付费（超出套餐后的部分）。
     *
     * 之所以两组都留：一个账号可能配多把 Key（项目级），
     * 单看账号额度会掩盖「某个 Key 单独被限」的情况。
     */
    data class Usage(
        val keyUsage: Long? = null,
        val keyLimit: Long? = null,
        val planUsage: Long? = null,
        val planLimit: Long? = null,
        val paygoUsage: Long? = null,
        val paygoLimit: Long? = null,
        /** 当前套餐名（`researcher` / `paygo` 等），没有就为空串。 */
        val plan: String = ""
    ) {
        /** 是否够用：账号额度还有剩余（limit 为 null 视为不限量）。 */
        val hasQuota: Boolean
            get() {
                val limit = planLimit ?: return true
                return (planUsage ?: 0L) < limit
            }
    }

/**
 * 额度查询结果（v0.0.58）。
 *
 * 为什么不用 Usage?：null 分不清「Key 错」「没网」「服务端炸了」，
 * 而这三种的下一步动作完全不同。带上 message，界面才能说人话。
 */
sealed interface UsageResult {
    data class Ok(val usage: TavilySearch.Usage) : UsageResult
    data class Failed(val message: String) : UsageResult
}

    /**
     * 纯解析函数（可单测）：`GET /usage` 响应体 → [Usage]。
     *
     * 数字字段官方给的是整数，但历史上出现过浮点（按量计费折算），
     * 所以这里用 [numOrNull] 同时兼容两种。任何字段缺失都退回 null，
     * 不抛异常——额度查询失败不应该影响搜索本身。
     */
    fun parseUsage(body: String): Usage {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Usage()
        val key = root["key"] as? JsonObject
        val account = root["account"] as? JsonObject
        return Usage(
            keyUsage = key.numOrNull("usage"),
            keyLimit = key.numOrNull("limit"),
            planUsage = account.numOrNull("plan_usage"),
            planLimit = account.numOrNull("plan_limit"),
            paygoUsage = account.numOrNull("paygo_usage"),
            paygoLimit = account.numOrNull("paygo_limit"),
            plan = (account?.get("current_plan") as? JsonPrimitive)?.contentOrNull.orEmpty()
        )
    }

    /**
     * 查询额度：`GET https://api.tavily.com/usage`，Bearer 鉴权。
     *
     * 返回 [UsageResult.Failed] 表示这次没查成（网络错误 / Key 无效 / 响应异常），
     * **message 里带上真实原因**（HTTP 状态或异常类型）——早先只回 null，
     * 界面只能显示「查询失败」，用户完全不知道该改 Key 还是开代理。
     */
    suspend fun fetchUsage(
        client: OkHttpClient,
        apiKey: String
    ): UsageResult {
        val request = Request.Builder()
            .url("https://api.tavily.com/usage")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@use UsageResult.Failed(usageErrorMessage(r.code, text))
                }
                UsageResult.Ok(parseUsage(text))
            }
        }.getOrElse { t ->
            UsageResult.Failed(
                "查询失败：${t.javaClass.simpleName}（${t.message ?: "网络不可达"}）" +
                    "。若在国内网络，请先在设置里配置代理或开启 VPN"
            )
        }
    }

    /** HTTP 非 2xx → 给人看的原因。 */
    private fun usageErrorMessage(code: Int, body: String): String = when (code) {
        401, 403 -> "查询失败：Key 无效或已失效（HTTP $code）"
        429 -> "查询失败：查询太频繁，稍后再试（HTTP $code）"
        in 500..599 -> "查询失败：Tavily 服务端异常（HTTP $code）"
        else -> {
            val snippet = body.take(120).ifBlank { "" }
            "查询失败：HTTP $code${if (snippet.isEmpty()) "" else " $snippet"}"
        }
    }

    /** JSON 数字字段，兼容整数 / 浮点 / 缺少。 */
    private fun JsonObject?.numOrNull(name: String): Long? {
        val p = this?.get(name) as? JsonPrimitive ?: return null
        val s = p.contentOrNull ?: return null
        return s.toDoubleOrNull()?.toLong()
    }
}

/**
 * DeepSeek 官方搜索服务（2026-10-17 开放的原生 Search API）。
 *
 * `POST https://api.deepseek.com/v1/tools/search`，Bearer 鉴权，
 * 返回结构化 results 数组——与 [TavilySearch] 同级的**纯搜索服务**，
 * 与对话用哪个模型完全解耦：
 *
 * - 对话模型是 DeepSeek 官方：可复用同一把 Key；
 * - 对话模型是别家（GPT / Qwen / Kimi…）：在设置里单独配一把
 *   DeepSeek Key，照样能调它的搜索服务——搜索能力不绑定对话模型。
 *
 * 计费按成功请求（$0.75/千次，失败与空结果不计费），账号级 QPS 5。
 *
 * ## 为什么换掉旧的 Anthropic 兼容实现
 *
 * 旧版走 `/anthropic/v1/messages` + 服务端工具 `web_search_20250305`，
 * 本质是**让 DeepSeek 模型替我们搜**：慢、烧 token、还依赖模型端点。
 * 原生 Search API 是一次纯 HTTP 检索，官方明确定位为
 * "give agents and RAG applications access to live web content"。
 */
object DeepSeekSearch {

    private val json = Json { ignoreUnknownKeys = true }

    private const val ENDPOINT = "https://api.deepseek.com/v1/tools/search"

    /** 默认取回条数。官方上限 50；5 条够模型作答又不撑爆上下文。 */
    private const val RESULT_COUNT = 5

    /**
     * 纯解析函数（可单测）：Search API 响应体 → 结果列表。
     *
     * 摘录字段三选一，按「官方推荐模型阅读的粒度」优先：
     * `summary`（600~800 字符，官方明确说 intended for a model to read）
     * > `content`（全文，need_content=true 才有） > `snippet`（短摘）。
     */
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
                snippet = listOf("summary", "content", "snippet")
                    .mapNotNull { key ->
                        (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }
                    .firstOrNull().orEmpty()
            )
        }
    }

    suspend fun search(
        client: OkHttpClient,
        apiKey: String,
        query: String,
        /** 用户原始问题：官方用于改进 query 理解，可空。 */
        oriQuestion: String? = null
    ): List<WebSearchResult> {
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query.take(128)))
                put("count", kotlinx.serialization.json.JsonPrimitive(RESULT_COUNT))
                put("need_content", kotlinx.serialization.json.JsonPrimitive(true))
                if (!oriQuestion.isNullOrBlank()) {
                    put("ori_question", kotlinx.serialization.json.JsonPrimitive(oriQuestion.take(128)))
                }
            }
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
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
 * 搜索 Key 挑选（**纯函数，可单测**）。
 *
 * 显式配的 Key 永远优先；没配才退回复用对话模型的 Key。
 * 两个都空返回 null——调用方据此提示「去设置页配 Key」，
 * 而不是拿空串去打一次注定 401 的请求。
 */
fun pickSearchKey(explicitKey: String?, fallbackKey: String?): String? =
    explicitKey?.takeIf { it.isNotBlank() } ?: fallbackKey?.takeIf { it.isNotBlank() }

/**
 * 建搜索专用的 OkHttpClient：比对话更长的超时（服务端搜索要真爬网页）。
 *
 * v0.0.58：补上 `NetworkRouter.proxySelector`。此前这个 client 是裸建的，
 * 用户在网络代理里配的规则对它完全不生效——国内网络下 Tavily 的
 * `/usage` 与 `/search` 都注定连不上，而界面只会显示「查询失败」。
 */
fun searchHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .proxySelector(com.mistakebook.net.NetworkRouter.proxySelector)
    .build()
