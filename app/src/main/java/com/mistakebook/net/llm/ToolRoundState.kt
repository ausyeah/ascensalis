package com.mistakebook.net.llm

/**
 * 工具循环（tool loop）里**一条助手消息**的跨轮累积状态。
 *
 * ## 为什么需要它
 *
 * v0.0.43 把联网搜索改成 function calling：模型可能「先思考 → 调 web_search →
 * 再思考 → 继续调 → 最后作答」，一次用户提问对应**多次 API 请求**，但用户
 * 眼里这仍然是**一条连续的助手消息**。早先的实现在这里栽了跟头：
 *
 * 1. 每轮结束把累积器清零，下一轮从头累积——数据库落的是 `updateAssistantContent`
 *    （整体替换），于是**第一轮的开场白与思考被永久抹掉**，最终答案只剩最后一轮；
 * 2. 工具执行期间往 [com.mistakebook.ui.chat.ChatGenerationBus] 发
 *    `content = ""` 的快照，而界面是**整体替换语义**
 *    （`streamingContent = snap.content`）——用户看到正在流的回答瞬间清空、
 *    只剩一行「正在联网搜索…」，即反馈里的「调用搜索之后就断输出」。
 *
 * ## 两套缓冲，职责分开
 *
 * - **跨轮累积**（[answer] / [thinking]）：UI 与数据库视角。一条消息就是一段
 *   连续回答，各轮正文/思考依次拼接，搜索期间也不能清空——气泡要一直有内容。
 * - **本轮缓冲**（[roundAnswer] / [roundThinking]）：API 视角。DeepSeek 思考
 *   模式的硬性要求：携带 tools 的后续请求必须完整回传 `reasoning_content`，
 *    且官方样例里**每条 assistant 消息只带自己那一轮的 reasoning_content**。
 *    所以 echo 回传用本轮缓冲，[beginNextRound] 时重置，跨轮累积不动。
 *
 * 纯 Kotlin、无 Android 依赖，JVM 单测直接覆盖。
 */
class ToolRoundState {

    private val answerAll = StringBuilder()
    private val thinkingAll = StringBuilder()

    private val answerRound = StringBuilder()
    private val thinkingRound = StringBuilder()

    /** 收到正文增量。两套缓冲同时追加。 */
    fun onDelta(text: String) {
        if (text.isEmpty()) return
        answerAll.append(text)
        answerRound.append(text)
    }

    /** 收到思考增量。两套缓冲同时追加。 */
    fun onThinking(text: String) {
        if (text.isEmpty()) return
        thinkingAll.append(text)
        thinkingRound.append(text)
    }

    /**
     * 一轮工具调用收尾：echo 已按本轮快照构建完毕，重置**本轮**缓冲。
     *
     * 跨轮累积**绝不能**在这里清——清了就是「输出中断」bug 本身。
     */
    fun beginNextRound() {
        answerRound.setLength(0)
        thinkingRound.setLength(0)
    }

    /** 跨轮累积的正文（最终答案的一部分）。 */
    val answer: String get() = answerAll.toString()

    /** 跨轮累积的思考（最终答案的一部分）。 */
    val thinking: String get() = thinkingAll.toString()

    /** 本轮正文：assistant echo 回传给 API 的 content。 */
    val roundAnswer: String get() = answerRound.toString()

    /** 本轮思考：assistant echo 回传给 API 的 reasoning_content。 */
    val roundThinking: String get() = thinkingRound.toString()
}

/**
 * 工具循环的策略判定（**纯函数，可 JVM 单测**）。
 */
object ToolLoopPolicy {

    /**
     * 这个渠道是否需要回传 `reasoning_content`。
     *
     * DeepSeek 思考模式的硬性要求：携带 tools 的请求，后续所有请求必须完整
     * 回传 `reasoning_content`，否则 API 直接 400（官方文档明确要求）。
     * 判定覆盖两种形态：
     * - 官方端点：baseUrl 含 deepseek（api.deepseek.com）；
     * - 中转站 serving DeepSeek 系模型：模型名含 deepseek（baseUrl 不含）。
     *
     * 其他渠道（Qwen / GPT 系等）不认识也不要求这个字段，不回传——
     * 回传一个它们没见过的字段，严格校验的服务端会 400。
     */
    fun needsReasoningEcho(baseUrl: String, model: String): Boolean =
        baseUrl.contains("deepseek", ignoreCase = true) ||
            model.contains("deepseek", ignoreCase = true)
}
