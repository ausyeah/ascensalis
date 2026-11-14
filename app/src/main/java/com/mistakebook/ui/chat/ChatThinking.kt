package com.mistakebook.ui.chat

/**
 * 思考过程与正文的分隔。
 *
 * ## 为什么用标记而不是两个数据库字段
 *
 * 1. **不用写迁移**。给 `chat_messages` 加字段要升 Room 版本号、写迁移 SQL，
 *    而迁移是本项目最容易翻车的一步（v0.0.x 那次「只加字段不改版本号」
 *    直接导致老用户一开库就 `IllegalStateException` 闪退）。
 * 2. **`<think>…</think>` 是通用约定**。DeepSeek / Qwen 都用它标记思考内容，
 *    导出的对话拿到别的前端里也能被正确识别。
 *
 * ## 边界情况
 *
 * - 模型可能**只思考、没回答**（例如拒答前的推理）——这时正文为空，
 *   仍然要显示思考，不能显示成空气泡。
 * - 标记**必须成对**。只出现 `\n<think>` 而没有 `\n</think>` 时，
 *   整段都当思考——宁可多显示，也不把思考混进正文里（正文是要复制的）。
 */
object ChatThinking {

    /**
     * 标记本身**不带前导换行**。
     *
     * 早先写成 `"\n<think>"` / `"\n</think>"`，于是模型输出的 `</think>`
     * 前面只要没有换行就匹配不上——表现是**整段正文被当成思考**，
     * 用户看到一个巨大的思考块、答案不见了，而且不报错。
     * 换行只影响显示，由界面在拆分后自己补。
     */
    const val OPEN = "<think>"
    const val CLOSE = "</think>"

    /**
     * 开标记（带可选属性）。v0.0.153（学 deepseek-ai/chat-demo）：思考用时
     * 存进开标记的 `data-ms` 属性——`<think data-ms="12345">`。零迁移拿到
     * 「已思考（用时 N 秒）」这个界面要素；无属性的老消息继续用普通 `<think>`。
     */
    private val OPEN_TAG = Regex("""<think(\s[^>]*)?>""")

    private val DURATION_ATTR = Regex("""data-ms="(\d+)"""")

    /** 内容里是否已经带了标记。 */
    fun isWrapped(text: String): Boolean = OPEN_TAG.containsMatchIn(text)

    /**
     * 把一条消息拆成「思考 / 正文」。
     *
     * ## 为什么不能只找第一对标记
     *
     * 截图里的真实输出（用户报告）：
     *
     * ```
     * <think> user is asking for my suggestions ... framework-level </think>
     * <think> report that acknowledges it lacks ... 错题 </think>
     * <think> 分类表，阅读错题日志）
     * ...
     * Good aspects:
     * - Clear structure
     * ```
     *
     * 三个特征叠加，导致原实现全错：
     *
     * 1. **模型在 `content` 流里自带字面量标记**（而不是放在 `reasoning_content`），
     *    所以标记会**原样出现在界面上**。用户看到的是一串 `<think>` 噪声。
     * 2. **标记有多对**。只拆第一对的话，后面的标记留在正文里继续显示。
     * 3. **存在未闭合的标记**（`<think> 分类表，阅读错题日志）`）。
     *    它之后的正常正文会被当成思考吞掉——
     *    而用户真正想看的答案，恰恰在那后面。
     *
     * 所以改成**逐段扫描**：标记内的归思考，标记外的归正文，
     * 标记本身一律剥离。这样不管有几对、有没有闭合，都不会漏出标签。
     *
     * @param thinking 空串表示这条消息没有思考过程。
     * @param answer 去掉标记后的正文。
     */
    fun split(raw: String, streaming: Boolean = false): Pair<String, String> {
        if (raw.isEmpty()) return "" to ""

        val thinking = StringBuilder()
        val answer = StringBuilder()
        var cursor = 0
        // 当前是否处于标记内。未闭合的标记会一直是 true——
        // 那时剩余内容全算思考，与「宁可多显示」的原意一致。
        var inThinking = false
        // 开标记 data-ms 属性里的思考用时（毫秒）。只有第一个带属性的
        // 开标记生效——多对标记只记第一份（时长已经覆盖全程）。
        var durationMs: Long? = null

        while (cursor < raw.length) {
            val openMatch = OPEN_TAG.find(raw, cursor)
            val open = openMatch?.range?.first ?: -1
            val close = raw.indexOf(CLOSE, cursor)

            when {
                // 下一个是开始标记
                open >= 0 && (close < 0 || open < close) -> {
                    if (!inThinking) {
                        answer.append(raw, cursor, open)
                        inThinking = true
                        if (durationMs == null) {
                            durationMs = openMatch?.let { match ->
                                DURATION_ATTR.find(match.value)?.groupValues?.get(1)?.toLongOrNull()
                            }
                        }
                    }
                    cursor = openMatch!!.range.last + 1
                }

                // 下一个是结束标记
                close >= 0 -> {
                    if (inThinking) {
                        thinking.append(raw, cursor, close)
                        cursor = close + CLOSE.length
                        inThinking = false
                    } else {
                        // **孤立的结束标记**：不在任何 `<think>` 里。
                        //
                        // 模型偶尔会漏写开始标记，这时 `</think>` 是正文的**一部分**，
                        // 不是控制符。原样保留才对——剥掉它会让用户看到的内容少一块。
                        //
                        // 注意 [cursor, close) 之间那段**也要 append**，
                        // 否则标记之前的正文会被吞掉（`只有</think>没有开始`
                        // 会变成 `没有开始`）。
                        answer.append(raw, cursor, close + CLOSE.length)
                        cursor = close + CLOSE.length
                    }
                }

                // 后面再没有标记了
                else -> {
                    // 尾部还开着 `<think>`——是**流式到一半**还是**模型漏写结束标记**，
                    // 光看文本分不出来，但两者的正确处理完全相反：
                    // - 流式中：后面确实还是推理，归思考（用户要看到「正在想什么」）
                    // - 已结束：模型忘了闭合，后面多半是**答案**。
                    //   归思考的话，用户展开一个巨大的思考块却找不到答案——
                    //   那正是截图里的现象。
                    if (inThinking && !streaming) {
                        answer.append(raw, cursor, raw.length)
                    } else if (inThinking) {
                        thinking.append(raw, cursor, raw.length)
                    } else {
                        answer.append(raw, cursor, raw.length)
                    }
                    cursor = raw.length
                }
            }
        }
        return thinking.toString().trim() to answer.toString().trim()
    }

    /** 标记之间与标记之后各留一个换行，纯粹为了显示时各占一行。 */
    fun tags(text: String): String = tags(text, durationMs = null)

    /**
     * 带用时的包裹：时长写进开标记的 `data-ms` 属性（见 [durationOf]）。
     */
    fun tags(text: String, durationMs: Long?): String {
        if (isWrapped(text)) return text
        return if (durationMs != null && durationMs > 0) {
            "\n<think data-ms=\"$durationMs\">$text$CLOSE"
        } else {
            "\n$OPEN$text$CLOSE"
        }
    }

    /**
     * 思考用时（毫秒）：从第一个开标记的 `data-ms` 属性读。
     * 老消息（无属性）返回 null——界面退回字数展示。
     */
    fun durationOf(raw: String): Long? =
        OPEN_TAG.find(raw)?.let { match ->
            DURATION_ATTR.find(match.value)?.groupValues?.get(1)?.toLongOrNull()
        }

    /** 供界面显示的思考过程，纯文本。 */
    fun thinkingOf(raw: String): String = split(raw).first

    /** 供界面显示的正文，已剥掉标记。 */
    fun answerOf(raw: String): String = split(raw).second
}
