package com.mistakebook.net.llm

import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.net.ApiError
import com.mistakebook.net.ApiErrorKind
import com.mistakebook.net.ApiResult

/**
 * 这个失败是不是「**这把 key 本身不行**」——是才值得换下一把 key。
 *
 * ## 为什么只认这两种
 *
 * 401/403 是 key 被吊销或填错，429/503 是这把 key 的额度用尽或被限流：
 * 换一把 key 就是换一份额度，**同一个请求原样再发就能成**。
 *
 * 其余的换 key 原样再犯，只是把其余 key 的额度一起烧掉：
 * - 服务端 5xx / 连不上 / 超时：和中转站整体有关，与 key 无关；
 * - HTTP 400（字段名不认、max_tokens 超限、prompt 超长）：请求体的问题，
 *   每把 key 收到的都是同一个请求体；
 * - 用户取消：换 key 重发等于用户点了停止还在烧 token。
 *
 * 判据刻意不匹配中文错误体（「额度不足」「当前分组上游负载已饱和」）：
 * 各中转站措辞随手改，靠字符串猜一次猜错就把用户导向错误结论。
 * 真出现那种形态，服务商返回的状态码也基本落在上面两类里。
 *
 * 独立成纯函数才能单测——它是这个特性里唯一有分支策略的地方。
 */
internal fun isKeyFailure(error: ApiError): Boolean = when (error.kind) {
    ApiErrorKind.AUTH, ApiErrorKind.RATE_LIMIT -> true
    else -> false
}

/**
 * 按 [LlmProfile.keyCandidates] 的候选链逐个试，直到成功、或错误与 key 无关。
 *
 * ## 为什么换 key 不额外退避
 *
 * [com.mistakebook.pipeline.LlmClient] 里那个 2 秒退避是给**同一把 key**
 * 的瞬时错误用的（等服务端喘口气）。换 key 是换一份额度，没有「等」的理由；
 * 限流窗口按 key 分开算，等也等不到这把 key 恢复。
 *
 * ## 为什么第一把就失败时不猜「下一把也许行」
 *
 * 只有 [isKeyFailure] 成立才继续。请求体有问题时把链上每一把都发一遍，
 * 代价是 N 倍 token 和 N 倍等待，结论还和第一把一模一样。
 */
internal suspend fun <T> withKeyFailover(
    profile: LlmProfile,
    attempt: suspend (key: String) -> ApiResult<T>
): ApiResult<T> {
    val keys = profile.keyCandidates(profile.model).map { it.key }
    if (keys.isEmpty()) return ApiResult.Failure(ApiError(ApiErrorKind.NO_KEY))
    for ((index, key) in keys.withIndex()) {
        val result = attempt(key)
        if (result is ApiResult.Success) return result
        if (result is ApiResult.Failure) {
            // 最后一把：没得换了，把它的失败原样交出去
            // 不是 key 的锅：原样返回，别把链上剩下的 key 白烧一遍
            if (index == keys.lastIndex || !isKeyFailure(result.error)) return result
        }
    }
    // 链非空却走到这里，说明 attempt 吐出了 Success/Failure 之外的形态；
    // ApiResult 是封闭类，编译期就穷尽了，这只是给编译器一个返回值。
    return ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN))
}
