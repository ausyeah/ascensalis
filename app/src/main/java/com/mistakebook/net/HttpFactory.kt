package com.mistakebook.net

import com.mistakebook.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 统一的 OkHttpClient 与错误映射。
 *
 * Key 永不进日志：HttpLoggingInterceptor 必须 redactHeader("Authorization")。
 */
object HttpFactory {

    const val WRITE_TIMEOUT_SECONDS = 120L
    const val READ_TIMEOUT_SECONDS = 120L
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val CALL_TIMEOUT_SECONDS = 300L

    /**
     * 令牌（API Key）里**任何空白字符都非法**。
     *
     * 粘贴是最常见的翻车点：从聊天记录/网页复制 key 时带回一个换行或
     * 空格，OkHttp 拼 Authorization 头直接抛 `Unexpected char 0x0a`，
     * 用户看到的报错跟「key 填错」毫无关系。key 从不含空白，整串剥掉
     * 只会救回手滑、绝不会误伤。
     */
    fun sanitizedToken(value: String): String = value.filterNot { it.isWhitespace() }

    /** Authorization 头的**统一拼装点**：全 App 的 Bearer 都经这里清洗。 */
    fun bearer(token: String): String = "Bearer ${sanitizedToken(token)}"

    fun createClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 代理规则走 NetworkRouter：设置页改模式后下一个请求即生效，
            // 不需要（也无法）重建这里建好的客户端。
            .proxySelector(NetworkRouter.proxySelector)
            .addInterceptor(loggingInterceptor())
            .build()

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

    /** HTTP 状态码 + 响应体 -> 用户可读错误。 */
    fun httpError(code: Int, body: String): ApiError {
        val msg = extractServerMessage(body)
        val kind = when (code) {
            401, 403 -> ApiErrorKind.AUTH
            408, 504 -> ApiErrorKind.TIMEOUT
            429, 503 -> ApiErrorKind.RATE_LIMIT
            in 500..599 -> ApiErrorKind.SERVER
            in 200..299 -> ApiErrorKind.BAD_RESPONSE
            else -> ApiErrorKind.UNKNOWN
        }
        return ApiError(kind = kind, serverMessage = msg, detail = "HTTP $code")
    }

    /** 异常 -> 用户可读错误。 */
    fun throwableError(t: Throwable): ApiError {
        val kind = when (t) {
            is java.util.concurrent.CancellationException -> ApiErrorKind.CANCELLED
            is SocketTimeoutException -> ApiErrorKind.TIMEOUT
            is UnknownHostException, is ConnectException -> ApiErrorKind.NETWORK
            // 反序列化失败是「响应结构不符预期」，不是「未知错误」：典型场景是
            // 代理/VPN 拦截后返回一张 HTML 错误页（HTTP 200 + 非 JSON），用户
            // 只看到「UNKNOWN」根本猜不到是被出口改写了响应。
            is kotlinx.serialization.SerializationException -> ApiErrorKind.BAD_RESPONSE
            is IOException -> ApiErrorKind.NETWORK
            else -> ApiErrorKind.UNKNOWN
        }
        // detail 带上异常类名：message 常常是空的（如 ConnectException），
        // 只剩类名也能说明问题；两者都空时由 UI 退回人话标签。
        val text = t.message?.takeIf { it.isNotBlank() }
            ?: t.javaClass.simpleName
        return ApiError(kind = kind, detail = text)
    }

    /**
     * 从响应体提取服务端 msg，兼容 {"msg":".."} 与 {"error":{"message":".."}} 两种形态。
     */
    fun extractServerMessage(body: String): String {
        if (body.isBlank()) return ""
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return ""
        if (element !is JsonObject) return ""
        listOf("msg", "message", "error_msg", "detail").forEach { key ->
            val value = (element[key] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (value.isNotBlank() && value != "null") return value
        }
        val nested = element["error"] as? JsonObject
        return (nested?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
