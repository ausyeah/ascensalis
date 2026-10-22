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
     * 判据收紧到 **HTTP 400**：鉴权(401/403)、限流(429)、服务端(5xx)
     * 换字段名再发一次还是同样结果，白烧一次配额；
     * 而 400 且一帧内容都没吐出时，换字段名正是对症的解法。
     */
    fun isUnknownFieldError(error: ApiError): Boolean =
        error.detail.startsWith("HTTP 400")
}
