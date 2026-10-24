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
            is IOException -> ApiErrorKind.NETWORK
            else -> ApiErrorKind.UNKNOWN
        }
        return ApiError(kind = kind, detail = t.message.orEmpty())
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
