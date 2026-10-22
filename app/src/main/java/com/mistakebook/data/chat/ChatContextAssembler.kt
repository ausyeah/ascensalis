package com.mistakebook.data.chat

import com.mistakebook.domain.ChatRole

/**
 * 上下文组装：**纯逻辑，无 Android 依赖，可 JVM 单测**。
 *
 * ## 规则
 *
 * - 永远保留：system + 题目上下文 + **最新一轮**
 * - 历史预算：最近 [MAX_HISTORY_ROUNDS] 轮 / [MAX_HISTORY_CHARS] 字符（约 60K token 级别的混合文本余量）
 * - 超预算时**从最旧的完整轮次整轮丢弃**——不能只丢半轮，
 *   丢掉用户的问题却留着它的回答，模型会对着一个没有问题的回答继续胡扯
 * - 丢弃后在 UI 显示「已省略 N 条早期对话」
 *
 * ## 为什么图片不计入字符预算
 *
 * 一张按长边 1280 压过的 JPEG，base64 后约 20~40 万字符。
 * 真按字符算，**任何带图的对话都发不出去**（第一条就把预算吃光）。
 * 所以预算只管文本，图片改用**张数上限** [MAX_IMAGES_PER_REQUEST] 兜住请求体大小。
 * 这是取舍，不是疏忽。
 */
object ChatContextAssembler {

    /** 历史部分的字符预算（不含 system 与题目上下文）。按混合中英文文本保守折算，约覆盖 60K token。 */
    const val MAX_HISTORY_CHARS = 240_000

    /** 历史部分的轮数上限，避免极短消息无限增长请求。 */
    const val MAX_HISTORY_ROUNDS = 64

    /**
     * 整个请求最多带几张历史图片。
     *
     * 取 2 是权衡：够用（用户贴的图基本都在最近几条里），又不会把请求体撑到几十 MB
     * 被网关直接 413 拒掉。
     */
    const val MAX_IMAGES_PER_REQUEST = 2

    /** 合成消息的 id：负数，避免与数据库自增 id 撞上。 */
    const val ID_SYSTEM = -1L

    /** 同上，题目上下文。 */
    const val ID_QUESTION = -2L

    /** 本次新输入。 */
    const val ID_PENDING = -3L

    /** 上文摘要（上下文压缩的产物）。 */
    const val ID_SUMMARY = -4L

    /**
     * 摘要消息在数据库里的内容前缀。
     *
     * 摘要是**一条真实的 SYSTEM 消息**（injected = 0，UI 看得见），
     * 靠这个前缀把「给人看的摘要」认出来。认错的消息不会进请求体，
     * 最坏只是压缩白做一次，不会把脏数据发给模型。
     */
    const val SUMMARY_PREFIX = "【上文摘要】"

    /**
     * 把历史切成「轮」。
     *
     * 一轮 = 一条 [ChatRole.USER] + 其后连续的 [ChatRole.ASSISTANT]。
     * 开头若是孤立的助手消息（不该发生，但要能扛住脏数据），也自成一轮。
     */
    fun groupIntoRounds(messages: List<OutgoingMessage>): List<List<OutgoingMessage>> {
        val rounds = mutableListOf<MutableList<OutgoingMessage>>()
        for (message in messages) {
            if (message.role == ChatRole.USER || rounds.isEmpty()) {
                rounds += mutableListOf(message)
            } else {
                rounds.last() += message
            }
        }
        return rounds
    }

    /**
     * 组装最终上下文。
     *
     * @param systemPrompt 角色设定，始终发送。
     * @param questionContext 题目上下文（题干/选项/答案/解析…），始终发送，只注入一次。
     * @param history 全部历史消息（含 [ChatRole.SYSTEM] 的会被剔除）。
     * @param pendingText 本次要发的新内容。**无论多大都一定发送**——
     *   悄悄截断用户的问题比直接报「上下文过长」糟糕得多。
     * @param summary 上文摘要。非空时作为一条 system 消息插在题目上下文之后、
     *   历史之前——被压缩掉的旧轮次靠它续上，模型才不会「忘了前面聊过什么」。
     */
    fun assemble(
        systemPrompt: String,
        questionContext: String,
        history: List<OutgoingMessage>,
        pendingText: String = "",
        summary: String = ""
    ): AssembledContext {
        val clean = history.filter { it.role != ChatRole.SYSTEM }
        val allRounds = groupIntoRounds(clean)

        // 新内容并入最后一轮。这样它和它的回答算同一轮，不会被「整轮丢弃」吃掉。
        val pending = OutgoingMessage(
            id = ID_PENDING,
            role = ChatRole.USER,
            text = pendingText
        )
        val rounds = if (pendingText.isBlank()) allRounds else allRounds + listOf(listOf(pending))

        // 从最新一轮往回装，装不下就停。
        // 倒着走是为了「停」得干脆：一旦超预算，再往前面的轮只会更大。
        val keptReversed = mutableListOf<List<OutgoingMessage>>()
        var used = 0
        for (i in rounds.indices.reversed()) {
            if (keptReversed.size >= MAX_HISTORY_ROUNDS) break
            val cost = rounds[i].sumOf { it.budgetCost() }
            // 最新一轮无条件保留：用户刚说的话不见了是最难排查的故障。
            if (keptReversed.isNotEmpty() && used + cost > MAX_HISTORY_CHARS) break
            keptReversed += rounds[i]
            used += cost
        }
        val kept = keptReversed.reversed()

        val body = capImages(kept.flatten())
        val omitted = allRounds.size - (if (pendingText.isBlank()) kept.size else kept.size - 1)

        return AssembledContext(
            // system 与题目上下文**由这里拼在头部**，输出自包含。
            // 早先把这两个参数收下却没用，靠调用方记得自己拼——
            // 那种约定迟早在某次改动里漏掉，而且漏了不报错，只是模型突然失忆。
            messages = buildList {
                if (systemPrompt.isNotBlank()) {
                    add(OutgoingMessage(id = ID_SYSTEM, role = ChatRole.SYSTEM, text = systemPrompt))
                }
                if (questionContext.isNotBlank()) {
                    add(OutgoingMessage(id = ID_QUESTION, role = ChatRole.SYSTEM, text = questionContext))
                }
                // 摘要紧跟题目上下文：位置越靠前，模型越当「既定事实」用它。
                if (summary.isNotBlank()) {
                    add(OutgoingMessage(id = ID_SUMMARY, role = ChatRole.SYSTEM, text = summary))
                }
                addAll(body)
            },
            omittedCount = omitted.coerceAtLeast(0),
            keptRounds = kept.size,
            usedChars = used
        )
    }

    /**
     * 全局图片张数上限，**保留最近的几张**。
     *
     * 从后往前收，收满即止——用户在最近一条消息里贴的图当然比十轮之前的更重要。
     *
     * 注意这里必须逆序分配。早先写成 `messages.map` 正序遍历，
     * 结果是**最旧**的消息先把名额抢光，用户刚贴的图反被丢掉——
     * 而 KDoc 里明明写着「保留最近」。**注释写了不等于代码做了。**
     */
    private fun capImages(messages: List<OutgoingMessage>): List<OutgoingMessage> {
        var budget = MAX_IMAGES_PER_REQUEST
        val keep = IntArray(messages.size)
        for (i in messages.indices.reversed()) {
            val count = messages[i].images.size
            if (budget <= 0 || count == 0) continue
            val take = minOf(budget, count)
            keep[i] = take
            budget -= take
        }
        return messages.mapIndexed { index, message ->
            if (keep[index] == message.images.size) message
            else message.copy(images = message.images.take(keep[index]))
        }
    }
}
