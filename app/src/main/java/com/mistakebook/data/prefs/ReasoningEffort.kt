package com.mistakebook.data.prefs

/**
 * 对话的思考强度（reasoning effort）。
 *
 * ## 为什么要有这一档设置
 *
 * 推理模型的思考深度由请求里的 `reasoning_effort`（或等价字段）控制。
 * 不传就是服务端默认——而各家默认差别很大：有的默认 medium，
 * 有的干脆不思考。整理错题、总结题目这类「想清楚比答得快重要」的
 * 场景，用户要能自己把思考拉满。
 *
 * ## 字段名不统一（实测）
 *
 * OpenAI o 系 / GPT-5 系用 `reasoning_effort`（low / medium / high）；
 * 部分中转站认 `thinking`；还有认 `reasoning` 的。
 * 只发一个字段名，遇到不认的服务端就是 **HTTP 400**，整次请求失败。
 * 所以发送时要按候选链逐个试，见 [com.mistakebook.net.llm.ReasoningFallback]。
 */
enum class ReasoningEffort(val key: String?) {
    /** 关闭：不带任何思考字段，服务端按自己的默认来。 */
    OFF(null),

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    /**
     * 最高档。发送 `max`——比 `high` 更激进，部分中转站的代理模型认这个值。
     * 不认时由 [com.mistakebook.net.llm.ReasoningFallback] 降级到 `high`。
     */
    MAX("max");

    companion object {
        fun fromKey(key: String?): ReasoningEffort = when (key?.lowercase()) {
            "off" -> OFF // SettingsStore.setReasoningEffort 把 OFF 写成字符串 "off"，这里必须认
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            "max" -> MAX
            else -> HIGH // 默认高：整理/总结场景「想清楚」比「答得快」重要
        }
    }
}
