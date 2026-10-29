package com.mistakebook.net.llm

import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.net.ApiError

/**
 * 思考强度的**字段名 / 取值降级链**。
 *
 * ## 背景：字段名不统一
 *
 * 控制推理模型思考深度的参数，各服务端写法不一样：
 *
 * | 字段 | 谁在用 |
 * |---|---|
 * | `reasoning_effort` | OpenAI o 系 / GPT-5 系（官方规范） |
 * | `thinking` | 部分中转站、Claude 风格网关 |
 * | `reasoning` | 另一批中转站 |
 *
 * 只发一个字段名，遇到不认的服务端就是 **HTTP 400**（ unrecognized field ），
 * 整次请求失败——用户看到的是「AI 用不了」，真实原因只是字段名对不上。
 *
 * ## 取值也要降级
 *
 * `max` 只有部分中转代理认；官方口径只到 `high`。所以 MAX 档先发 `max`，
 * 被 400 打回后再发 `high`。
 *
 * ## 独立成纯函数
 *
 * 它是这个特性里唯一有分支策略的地方，抽出来才能单测——
 * 和 [StreamFallback] 同样的理由。
 */
object ReasoningFallback {

    /** 按优先级排列的字段名候选。官方规范打头。 */
    val FIELD_CANDIDATES = listOf("reasoning_effort", "thinking", "reasoning")

    /**
     * 生成本次请求的尝试链：`(字段名, 取值)`，**null 表示不带任何思考字段**。
     *
     * - OFF：直接 [null]，一个字段都不发。
     * - MAX：三个字段名 × `max`，再三个字段名 × `high`，最后兜底 null。
     * - 其他档：三个字段名 × 档位值，最后兜底 null。
     *
     * 400 是**立即返回**的错误，不吃 token 也不产生输出，
     * 所以多试几次的代价只是多一次往返；只有在服务端不认时才逐级降，
     * 正常服务端第一次就成功。
     */
    fun attempts(level: ReasoningEffort): List<Pair<String, String>?> {
        if (level == ReasoningEffort.OFF) return listOf(null)
        val values = if (level == ReasoningEffort.MAX) listOf("max", "high")
        else listOf(level.key ?: return listOf(null))
        val out = mutableListOf<Pair<String, String>?>()
        for (value in values) {
            for (field in FIELD_CANDIDATES) {
                out.add(field to value)
            }
        }
        out.add(null)
        return out
    }

    /**
     * 这个失败是不是「服务端不认思考字段」——是才值得换下一个候选。
     *
     * ## 判据为什么不能只看 HTTP 400（v0.0.62 收紧）
     *
     * 旧实现「400 即换字段」把**一切** 400 都当成字段名问题：
     * max_tokens 超限、prompt 超长、模型名写错……每一种都会把整条候选链
     * （最多 7 个）重发一遍全量 prompt，烧 7 倍 token 后失败，还可能再走
     * 一次非流式降级。收紧为两层：
     *
     * 1. **负向排除**：错误体明确是「输出上限 / 上下文超长」的，换字段毫无意义；
     * 2. **正向要求**：错误体确实提到思考字段名，或出现 unrecognized /
     *    unknown field 措辞——服务端不认字段时几乎都会回显字段名
     *    （OpenAI 系："Unrecognized request argument supplied: reasoning_effort"）。
     *    两层都不满足的 400，一次失败就报错，比盲发 7 次更省钱也更可读。
     */
    fun isUnknownFieldError(error: ApiError): Boolean {
        if (!error.detail.startsWith("HTTP 400")) return false
        val haystack = "${error.serverMessage}\n${error.detail}".lowercase()
        if (isMaxTokensLimitError(error) || haystack.contains("context length") ||
            haystack.contains("prompt is too long") || haystack.contains("too many tokens")
        ) {
            return false
        }
        val mentionsField = FIELD_CANDIDATES.any { haystack.contains(it) }
        val mentionsUnknownField = haystack.contains("unrecognized") ||
            haystack.contains("unknown field")
        return mentionsField || mentionsUnknownField
    }
}

/**
 * 是否「输出上限超限」型 400（与识别路径 pipeline.LlmClient 同判据，两处共用）。
 *
 * 必须是 HTTP 400（detail 以 "HTTP 400" 开头，body 片段已由请求层拼入），
 * 且错误文本提到 max_tokens 或 maximum context length（忽略大小写）——
 * 两种常见服务端原文都覆盖。
 */
internal fun isMaxTokensLimitError(error: ApiError): Boolean {
    if (!error.detail.startsWith("HTTP 400")) return false
    val haystack = "${error.serverMessage}\n${error.detail}".lowercase()
    return haystack.contains("max_tokens") || haystack.contains("maximum context length")
}
