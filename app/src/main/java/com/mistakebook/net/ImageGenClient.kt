package com.mistakebook.net

import com.mistakebook.data.prefs.ImageProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
 * 内置默认体走 OpenAI images 兼容格式（SenseNova 视觉模型同样兼容：
 * `model` + `prompt` + `n` + `size` + `response_format`）；
 * 用户贴 [ImageProfile.requestBodyTemplate] 时走模板，代码只替换
 * `{prompt}` `{width}` `{height}` 三个占位符——其余字段
 * （负向提示词、seed、steps……）完全由用户按 API 文档掌握。
 *
 * ## 返回解析
 *
 * 按宽容顺序尝试：`data[0].b64_json` → `data[0].url`（再 GET 下载）→
 * `data[0]` 本身是字符串（有的中转站直接给 URL 串）。
 * 三种都是市面常见形态，都不认领才报 BAD_RESPONSE。
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
        val request = Request.Builder()
            .url(profile.generateUrl())
            .header("Authorization", "Bearer ${profile.apiKey}")
            .post(bodyText.toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Failure(
                        HttpFactory.httpError(resp.code, respBody)
                    )
                }
                parseImage(respBody)
            }
        } catch (e: SocketTimeoutException) {
            ApiResult.Failure(ApiError(ApiErrorKind.TIMEOUT, detail = e.message.orEmpty()))
        } catch (e: IOException) {
            ApiResult.Failure(ApiError(ApiErrorKind.NETWORK, detail = e.message.orEmpty()))
        } catch (e: Exception) {
            ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN, detail = e.message.orEmpty()))
        }
    }

    /** 解析生图响应：b64 / url / 裸字符串三种形态依次尝试。 */
    private fun parseImage(respBody: String): ApiResult<ByteArray> {
        val root = try {
            Json.parseToJsonElement(respBody).jsonObject
        } catch (e: Exception) {
            return ApiResult.Failure(
                ApiError(ApiErrorKind.BAD_RESPONSE, detail = "响应不是 JSON：${e.message}")
            )
        }
        val first = try {
            root["data"]?.jsonArray?.firstOrNull()?.jsonObject
        } catch (e: Exception) {
            null
        }
        // 1) data[0].b64_json
        first?.get("b64_json")?.jsonPrimitive?.contentOrNullSafe()?.let { b64 ->
            return decodeBase64(b64)
        }
        // 2) data[0].url（GET 下载）
        first?.get("url")?.jsonPrimitive?.contentOrNullSafe()?.let { url ->
            return download(url)
        }
        // 3) data[0] 直接是 URL 字符串
        (try {
            root["data"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNullSafe()
        } catch (e: Exception) {
            null
        })?.let { url ->
            if (url.startsWith("http")) return download(url)
        }
        // 4) 顶层 url（个别渠道不包 data）
        root["url"]?.jsonPrimitive?.contentOrNullSafe()?.let { url ->
            if (url.startsWith("http")) return download(url)
        }
        return ApiResult.Failure(
            ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "生图接口未返回图片数据")
        )
    }

    private fun decodeBase64(b64: String): ApiResult<ByteArray> = try {
        val bytes = Base64.getDecoder().decode(b64)
        if (bytes.isEmpty()) {
            ApiResult.Failure(ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "生图接口返回了空图片"))
        } else {
            ApiResult.Success(bytes)
        }
    } catch (e: IllegalArgumentException) {
        ApiResult.Failure(ApiError(ApiErrorKind.BAD_RESPONSE, detail = "base64 解码失败：${e.message}"))
    }

    private fun download(url: String): ApiResult<ByteArray> = try {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                ApiResult.Failure(
                    ApiError(ApiErrorKind.NETWORK, serverMessage = "图片下载失败：HTTP ${resp.code}")
                )
            } else {
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    ApiResult.Failure(ApiError(ApiErrorKind.BAD_RESPONSE, serverMessage = "图片下载内容为空"))
                } else {
                    ApiResult.Success(bytes)
                }
            }
        }
    } catch (e: IOException) {
        ApiResult.Failure(ApiError(ApiErrorKind.NETWORK, detail = e.message.orEmpty()))
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 生图超时按任务书 60s（默认 client 是 120/300s，对话场景的配置）。
         * 其余能力（代理规则、日志 redact）复用 [HttpFactory]。
         */
        fun defaultClient(): OkHttpClient =
            HttpFactory.createClient().newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()

        /**
         * 组装请求体 JSON（**纯函数，可 JVM 单测**）。
         *
         * - 用户贴了模板：只替换三个占位符，其余原样。
         * - 没贴模板：内置 OpenAI images 兼容默认体。
         *
         * ## 占位符的引号感知
         *
         * 模板有两种主流写法，都要能工作：
         * 1. `"prompt":"{prompt}"`——占位符在引号内（更常见，
         *    用户按「最终 JSON 长什么样」贴模板）；
         * 2. `"prompt": {prompt}`——占位符裸写，占着 JSON 值的位置。
         *
         * 统一替换会必错其一：往写法 1 里插带引号的 JSON 串会得到
         * `""a dog""`（双重引号，非法 JSON）；往写法 2 里插裸串会得到
         * `a dog`（不是合法 JSON 值）。所以先按写法 1 替换（插转义裸串），
         * 再按写法 2 兜底（插带引号的完整 JSON 串）。
         * prompt 里的引号 / 换行 / 反斜杠由 [JsonPrimitive] 序列化保证转义，
         * 两种写法下都不会撑破模板的 JSON 结构。
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
                // 带引号写法（"{prompt}"）：只替换占位符本体，模板里的引号保留，
                // 插转义裸串 → "prompt":"a dog"。
                // 裸写写法（{prompt}）：插带引号的完整 JSON 串 → "prompt": "a dog"。
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

/** kotlinx.json 的 contentOrNull 是 internal 的，这里包一层空值安全读取。 */
private fun kotlinx.serialization.json.JsonElement.contentOrNullSafe(): String? =
    try {
        jsonPrimitive.content.takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    }
