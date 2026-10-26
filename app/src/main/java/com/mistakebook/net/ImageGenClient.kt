package com.mistakebook.net

import com.mistakebook.data.prefs.ImageProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 生图 API 客户端（可插拔：渠道由用户在设置里配置）。
 *
 * ## 请求体为什么有两套
 *
 * 生图 API 没有 `/chat/completions` 那样的统一端点，请求体各家差异大。
 * 内置默认体走 OpenAI images 兼容格式；用户贴
 * [ImageProfile.requestBodyTemplate] 时走模板，代码只替换
 * `{prompt}` `{width}` `{height}` 三个占位符。
 *
 * ## 返回解析为什么这么宽容
 *
 * 用户反馈「生图很容易失败」。一部分并不是真失败，而是**响应格式没认出来**：
 * 各家放图片的位置五花八门——`data[0].b64_json`、`data[0].url`、
 * `data[0].image`、顶层 `output.images[]`，甚至直接一个 URL 字符串。
 * 只认第一种的话后面几种全被判成失败。所以先走明确路径，
 * 再在 JSON 树里做一次启发式搜索兜底。
 *
 * ## 重试
 *
 * 瞬时错误（网络 / 超时 / 5xx / 限流）重试 1 次。生图本来就慢，
 * 一次抖动就让用户重来太亏；鉴权与参数错误不重试——重试多少次都一样。
 *
 * 失败一律走 [ApiResult.Failure]，不抛异常到 UI 层（PRD 11）。
 */
class ImageGenClient(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun generate(
        profile: ImageProfile,
        prompt: String,
        width: Int,
        height: Int
    ): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        if (!profile.isConfigured()) {
            return@withContext ApiResult.Failure(
                ApiError(ApiErrorKind.NO_KEY, "生图渠道未配置完整（地址 / Key / 模型名）")
            )
        }
        val bodyText = buildRequestBody(profile, prompt, width, height)

        var last: ApiResult.Failure? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            when (val result = callOnce(profile, bodyText)) {
                is ApiResult.Success -> return@withContext result
                is ApiResult.Failure -> {
                    last = result
                    if (!shouldRetry(result.error.kind) || attempt == MAX_ATTEMPTS - 1) break
                    delay(RETRY_BACKOFF_MS)
                }
            }
        }
        last ?: ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN, "生图失败"))
    }

    private fun callOnce(profile: ImageProfile, bodyText: String): ApiResult<ByteArray> {
        val request = Request.Builder()
            .url(profile.generateUrl())
            .header("Authorization", "Bearer ${profile.apiKey}")
            .post(bodyText.toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    ApiResult.Failure(HttpFactory.httpError(resp.code, respBody))
                } else {
                    parseImage(respBody)
                }
            }
        } catch (e: SocketTimeoutException) {
            ApiResult.Failure(ApiError(ApiErrorKind.TIMEOUT, "生图超时", e.message.orEmpty()))
        } catch (e: IOException) {
            ApiResult.Failure(ApiError(ApiErrorKind.NETWORK, "网络错误", e.message.orEmpty()))
        } catch (e: Exception) {
            ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN, "生图失败", e.message.orEmpty()))
        }
    }

    /** 只重试瞬时错误：鉴权 / 参数 / 配额这类重试多少次结果都一样。 */
    private fun shouldRetry(kind: ApiErrorKind): Boolean = when (kind) {
        ApiErrorKind.NETWORK, ApiErrorKind.TIMEOUT,
        ApiErrorKind.SERVER, ApiErrorKind.RATE_LIMIT -> true

        else -> false
    }

    /** 解析生图响应：明确路径优先，再在 JSON 树里启发式兜底。 */
    private fun parseImage(respBody: String): ApiResult<ByteArray> {
        val root = try {
            Json.parseToJsonElement(respBody).jsonObject
        } catch (e: Exception) {
            return ApiResult.Failure(
                ApiError(
                    ApiErrorKind.BAD_RESPONSE,
                    "生图接口返回的不是 JSON",
                    respBody.take(DIAGNOSTIC_CHARS)
                )
            )
        }

        // 1) 明确的字段名（各家文档里出现过的写法）
        firstStringByKey(root, B64_KEYS)?.let { return decodeBase64(it) }
        firstStringByKey(root, URL_KEYS)?.let { return download(it) }

        // 2) 启发式：整棵树里找一段像图片的长 base64，或一个 http(s) 链接
        findImageLike(root)?.let { (isBase64, value) ->
            return if (isBase64) decodeBase64(value) else download(value)
        }

        return ApiResult.Failure(
            ApiError(
                ApiErrorKind.BAD_RESPONSE,
                "生图接口没有返回可用的图片数据",
                respBody.take(DIAGNOSTIC_CHARS)
            )
        )
    }

    /** 在 JSON 树里按优先级找第一个匹配 key 的非空字符串。 */
    private fun firstStringByKey(root: JsonObject, keys: Set<String>): String? {
        for (key in keys) {
            val value = findByKey(root, key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun findByKey(element: JsonElement, target: String): String? {
        return when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key.lowercase() == target) stringOf(value)?.let { return it }
                    findByKey(value, target)?.let { return it }
                }
                null
            }

            is JsonArray -> {
                for (item in element) findByKey(item, target)?.let { return it }
                null
            }

            else -> null
        }
    }

    /**
     * 启发式兜底：递归找「像图片」的值。
     *
     * @return (是否 base64, 值)：base64 判据是长度（图片 base64 至少几百字符），
     *   URL 判据是 http(s) 前缀。
     */
    private fun findImageLike(element: JsonElement): Pair<Boolean, String>? {
        return when (element) {
            is JsonObject -> {
                for ((_, value) in element) findImageLike(value)?.let { return it }
                null
            }

            is JsonArray -> {
                for (item in element) findImageLike(item)?.let { return it }
                null
            }

            else -> {
                val text = stringOf(element) ?: return null
                when {
                    text.length >= MIN_BASE64_CHARS && isBase64ish(text) -> true to text
                    text.startsWith("http://", true) || text.startsWith("https://", true) ->
                        false to text

                    else -> null
                }
            }
        }
    }

    private fun isBase64ish(text: String): Boolean =
        text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    private fun stringOf(element: JsonElement): String? =
        runCatching { element.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun decodeBase64(b64: String): ApiResult<ByteArray> {
        // 有些渠道返回的是 data URL（`data:image/png;base64,xxx`），先剥前缀
        val payload = b64.substringAfter("base64,", b64)
        return try {
            val bytes = Base64.getDecoder().decode(payload)
            if (bytes.isEmpty()) {
                ApiResult.Failure(ApiError(ApiErrorKind.BAD_RESPONSE, "生图接口返回了空图片"))
            } else {
                ApiResult.Success(bytes)
            }
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, "图片数据解码失败", e.message.orEmpty())
            )
        }
    }

    private fun download(url: String): ApiResult<ByteArray> = try {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                ApiResult.Failure(
                    ApiError(ApiErrorKind.NETWORK, "图片下载失败：HTTP ${resp.code}")
                )
            } else {
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    ApiResult.Failure(ApiError(ApiErrorKind.BAD_RESPONSE, "图片下载内容为空"))
                } else {
                    ApiResult.Success(bytes)
                }
            }
        }
    } catch (e: IOException) {
        ApiResult.Failure(ApiError(ApiErrorKind.NETWORK, "图片下载失败", e.message.orEmpty()))
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 生图超时 60s（对话场景默认 120/300s，生图不该等那么久）。 */
        fun defaultClient(): OkHttpClient =
            HttpFactory.createClient().newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()

        private const val MAX_ATTEMPTS = 2
        private const val RETRY_BACKOFF_MS = 2_000L

        /** 诊断信息最多带多少字符（不把整段响应塞给 UI）。 */
        private const val DIAGNOSTIC_CHARS = 200

        /** base64 图片的判据长度：几十字符的一般是别的字段（模型名、状态）。 */
        private const val MIN_BASE64_CHARS = 500

        private val B64_KEYS = setOf("b64_json", "b64", "base64", "image_base64", "image")
        private val URL_KEYS = setOf("url", "image_url", "download_url", "link")

        /**
         * 组装请求体 JSON（**纯函数，可 JVM 单测**）。
         *
         * - 用户贴了模板：只替换三个占位符，其余原样。
         * - 没贴模板：内置 OpenAI images 兼容默认体。
         *
         * ## 占位符的引号感知
         *
         * 模板有两种主流写法，都要能工作：
         * 1. `"prompt":"{prompt}"`——占位符在引号内（更常见）；
         * 2. `"prompt": {prompt}`——占位符裸写，占着 JSON 值的位置。
         *
         * 统一替换会必错其一：往写法 1 里插带引号的 JSON 串会得到
         * `""a dog""`（双重引号，非法 JSON）；往写法 2 里插裸串会得到
         * `a dog`（不是合法 JSON 值）。所以先按写法 1 替换（插转义裸串），
         * 再按写法 2 兜底（插带引号的完整 JSON 串）。
         * prompt 里的引号 / 换行 / 反斜杠由 [JsonPrimitive] 序列化保证转义。
         */
        internal fun buildRequestBody(
            profile: ImageProfile,
            prompt: String,
            width: Int,
            height: Int
        ): String {
            val template = profile.requestBodyTemplate.trim()
            if (template.isNotEmpty() && template.contains("{prompt}")) {
                val jsonPrompt = JsonPrimitive(prompt).toString()
                val barePrompt = jsonPrompt.removeSurrounding("\"")
                val filled = if (template.contains("\"{prompt}\"")) {
                    template.replace("{prompt}", barePrompt)
                } else {
                    template.replace("{prompt}", jsonPrompt)
                }
                return filled
                    .replace("{width}", width.toString())
                    .replace("{height}", height.toString())
            }
            // 模板存在但不含 {prompt}：当成没填，走默认体——
            // 硬替换一个没有占位符的模板只会发出一个永远不含 prompt 的请求。
            val body: JsonObject = buildJsonObject {
                put("model", JsonPrimitive(profile.model.trim()))
                put("prompt", JsonPrimitive(prompt))
                put("n", JsonPrimitive(1))
                put("size", JsonPrimitive("${width}x$height"))
                put("response_format", JsonPrimitive("b64_json"))
            }
            return body.toString()
        }
    }
}
