package com.mistakebook.data.chat

/**
 * 循环输出检测（v0.0.135，用户：「防止循环输出的小心思，循环输出就会
 * 触发中断」）。
 *
 * ## 为什么需要它
 *
 * 小模型（或被坏采样参数带偏的模型）有时会卡在同一个片段上反复输出：
 * 「根据题意可得，根据题意可得，根据题意可得……」。这段废话会一直烧
 * token 直到撞上 max_tokens，用户只能干看着屏幕滚。
 *
 * ## 检测办法：找周期
 *
 * 取文本尾部 `period * REPEATS` 个字符，切成等长四段——四段完全相同
 * 即判定为打转。两轮相同在正常文本里很常见（「好的」「那么」连用），
 * 会把正常回答误杀；**四轮相同 + 片段至少 [MIN_PERIOD] 字**，误报率
 * 才足够低。只看尾部：打转一定发生在刚输出的地方，历史中间偶然重复
 * 不构成循环。
 *
 * ## 增量检查
 *
 * 每个 token 都扫全文是浪费：[ChatLoopDetector] 每累积 [CHECK_STEP]
 * 个字符才查一次，且只查尾部（窗口最大 120×4=480 字符），单次成本恒定。
 *
 * 纯 Kotlin、无 Android 依赖，JVM 单测直接覆盖（ChatLoopGuardTest）。
 */
object ChatLoopGuard {

    /** 周期片段的最短长度：太短的片段（标点、单字）容易误报。 */
    const val MIN_PERIOD: Int = 6

    /** 周期片段的最长长度：再长就不像「短片段打转」，是正常复述。 */
    const val MAX_PERIOD: Int = 120

    /** 连续重复多少轮判定为循环。 */
    const val REPEATS: Int = 4

    /**
     * 文本尾部是否处于循环输出。
     *
     * @param text 已累积的输出文本（回答或思考，任一皆可）
     * @return true = 检测到循环，调用方应中断生成
     */
    fun isLooping(text: String): Boolean {
        val length = text.length
        val maxPeriod = minOf(MAX_PERIOD, (length / REPEATS) - 1)
        if (maxPeriod < MIN_PERIOD) return false

        for (period in MIN_PERIOD..maxPeriod) {
            val window = period * REPEATS
            val tail = text.substring(length - window)
            val unit = tail.substring(0, period)
            var matches = true
            for (i in 1 until REPEATS) {
                if (tail.substring(i * period, (i + 1) * period) != unit) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}

/**
 * 流式生成里的循环检测状态：每累积 [ChatLoopGuard.CHECK_STEP] 个字符
 * 查一次尾部，命中即报。回答与思考各用一实例。
 */
class ChatLoopDetector {

    private var lastCheckedAt = 0

    /**
     * 喂入当前累积文本。返回 true = 已确认循环（同一实例只会返回一次，
     * 之后的调用一律 false——调用方拿到 true 就该中断，不需要重复信号）。
     */
    fun observe(text: String): Boolean {
        if (tripped) return false
        if (text.length - lastCheckedAt < CHECK_STEP) return false
        lastCheckedAt = text.length
        if (ChatLoopGuard.isLooping(text)) {
            tripped = true
            return true
        }
        return false
    }

    private var tripped = false

    companion object {
        /** 每累积这么多字符检查一次（远大于最长窗口，成本可忽略）。 */
        const val CHECK_STEP: Int = 64
    }
}
