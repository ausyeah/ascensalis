package com.mistakebook.net

/** 网络层统一返回类型（PRD 11：禁止把原始异常抛到 UI 层）。 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

enum class ApiErrorKind {
    /** 未填写 Key，应引导去设置 */
    NO_KEY,

    /** 鉴权失败：401 或业务 code != 0 */
    AUTH,

    /** 429 / 503：服务端繁忙或额度受限 */
    RATE_LIMIT,

    /** 超时 */
    TIMEOUT,

    /** 无网络 / DNS / 连接失败 */
    NETWORK,

    /** 500 及以上，原样展示服务端 msg */
    SERVER,

    /** 用户取消 */
    CANCELLED,

    /** 响应结构不符预期 */
    BAD_RESPONSE,

    UNKNOWN
}

data class ApiError(
    val kind: ApiErrorKind,
    /** 服务端返回的原始 msg（脱敏后），仅用于 SERVER / BAD_RESPONSE 的补充说明 */
    val serverMessage: String = "",
    /** 调试用细节，不展示给用户 */
    val detail: String = ""
) {
    fun hasServerMessage(): Boolean = serverMessage.isNotBlank()
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> runCatching { ApiResult.Success(transform(data)) }
        .getOrElse { ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN, detail = it.message.orEmpty())) }
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.dataOrNull(): T? = (this as? ApiResult.Success)?.data

fun ApiResult<*>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error

/**
 * 失败原因的**人话文本**，给所有要展示错误的地方用。
 *
 * 优先服务端 msg（业务错误最有信息量），其次异常 detail（连接被拒、超时、
 * 反序列化失败这类只有异常里有），最后才退回枚举名。
 *
 * 只读 serverMessage 是「UNKNOWN」的根源：异常类失败的 serverMessage 为空，
 * 真实原因全在 detail 里，被丢掉后用户只剩一个看不懂的枚举名。
 */
val ApiError.displayMessage: String
    get() = serverMessage.takeIf { it.isNotBlank() }
        ?: detail.takeIf { it.isNotBlank() }
        ?: kind.name
