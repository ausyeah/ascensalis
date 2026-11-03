package com.mistakebook.ui.chat

import com.mistakebook.data.chat.ChatPrompts

/**
 * 注入给模型的那段「题目上下文」拆开后的展示数据。
 *
 * ## 为什么要拆
 *
 * 题目上下文是一条 `SYSTEM` 消息，原文是**给模型读的一整块文本**：
 * 几行元信息，空一行，然后是题干/选项/我记录的答案/我记录的解析。
 * 早先界面把整块原文当普通文字铺出来——用户看到的是一堵灰色小字，
 * 既看不出「这是这道题的上下文」而不是 AI 说的话，也找不到题干在哪。
 *
 * 拆开后头部几行提升成卡片的标题与芯片，题目正文收进可折叠区，
 * 卡片用独立底色 + 细描边与对话气泡区分开。
 *
 * ## 为什么不改成从数据库另读一份元信息
 *
 * 题目上下文是**注入那一刻的快照**：用户在编辑页改了题干，已经开着的
 * 会话里模型看到的仍是注入时那一版。从库里另读一份，卡片就会和模型
 * 实际收到的东西不一致——那比解析原文更糟。原文是唯一可信来源。
 */
internal data class QuestionContext(
    val title: String = "",
    val subject: String = "",
    val difficulty: String = "",
    val errorReason: String = "",
    /** 知识点原文（顿号分隔）。只作展示，不再拆开。 */
    val knowledge: String = "",
    /** 题目正文：题干/选项/我记录的答案/我记录的解析。 */
    val body: String = ""
) {
    /**
     * 一片元信息都没解析出来。
     *
     * 为真说明原文不是既定格式（提示词改过排版，或是别的 system 消息），
     * 此时宁可整块退回正文，也不要把不认识的文本当元信息秀。
     */
    val hasMeta: Boolean
        get() = title.isNotBlank() || subject.isNotBlank() || difficulty.isNotBlank() ||
            errorReason.isNotBlank() || knowledge.isNotBlank()

    companion object {

        /**
         * 头部元信息的键。
         *
         * 与 [ChatPrompts.questionContext] 写出来的那几行一一对应；
         * 认不出的行（比如开头那句给模型的定向说明）直接忽略——
         * 不列白名单的话，任何带全角冒号的行都会被当成元信息。
         */
        private val HEAD_KEYS = listOf("标题", "学科", "难度", "记录中的错因", "知识点")

        /** 解析注入的题目上下文。格式不符时退回「全是正文」。 */
        fun parse(content: String): QuestionContext {
            if (content.isBlank()) return QuestionContext()
            val stripped = content.withoutModelOnlyLines()
            // 元信息与正文以第一个空行分界（ChatPrompts.questionContext 的排版）。
            val split = stripped.lines().indexOfFirst { it.isBlank() }
            if (split < 0) return QuestionContext(body = stripped)

            val fields = mutableMapOf<String, String>()
            stripped.lines().take(split).forEach { line ->
                val key = line.substringBefore('：', missingDelimiterValue = "")
                // substringBefore 在全角冒号缺失时返回整行，所以必须再查白名单
                if (key in HEAD_KEYS) fields[key] = line.substringAfter('：').trim()
            }
            val parsed = QuestionContext(
                title = fields["标题"].orEmpty(),
                subject = fields["学科"].orEmpty(),
                difficulty = fields["难度"].orEmpty(),
                errorReason = fields["记录中的错因"].orEmpty(),
                knowledge = fields["知识点"].orEmpty(),
                body = stripped.lines().drop(split + 1).joinToString("\n").trim()
            )
            return if (parsed.hasMeta) parsed else QuestionContext(body = stripped)
        }

        /**
         * 剔掉首尾两句**只给模型看**的话。
         *
         * 开头那句是定向说明，结尾那句是「请基于以上内容回答用户的问题。」
         * ——留在界面上，用户会以为 App 在自言自语。两句话都由
         * [ChatPrompts] 出，这里只认常量不抄字面量：提示词改了文案，
         * 两边一起变，不会出现「卡片里多了句给模型的话」。
         */
        private fun String.withoutModelOnlyLines(): String {
            var s = trim()
            if (s.endsWith(ChatPrompts.QUESTION_TAIL)) {
                s = s.removeSuffix(ChatPrompts.QUESTION_TAIL).trim()
            }
            if (s.startsWith(ChatPrompts.HEAD_INTRO)) {
                s = s.removePrefix(ChatPrompts.HEAD_INTRO).trim()
            }
            return s
        }
    }
}
