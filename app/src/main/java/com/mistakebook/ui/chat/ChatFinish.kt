package com.mistakebook.ui.chat

/**
 * 助手消息的**结束原因**持久化（v0.0.154，学 deepseek-ai/chat-demo 的
 * `canContinueReply` / stopReason）。
 *
 * ## 为什么用标记而不是数据库字段
 *
 * 与 [ChatThinking] 同一取舍：给 `chat_messages` 加列要升 Room 版本号，
 * 而迁移是本项目历史上最容易翻车的一步。自闭合标记追加在 content 尾部，
 * 显示 / 复制路径由 [ChatThinking.split] 统一剥离。
 *
 * ## 目前只持久化 `length`
 *
 * `finish_reason = length`（输出预算耗尽）意味着**内容确定不完整**，
 * 且「继续生成」能把剩下的部分接出来——这是需要入口的场景。
 * `stop`（正常结束）与 `tool_calls`（中间态）没有界面含义，不落标记；
 * `content_filter` 的处理是在正文尾部追加一句说明（见 ChatGenerationService
 * 的 Done 分支），不走本标记。
 */
object ChatFinish {

    /** 追加在 content 尾部的截断标记。 */
    const val LENGTH_MARKER = "\n<finish-reason length/>"

    private val MARKER_REGEX = Regex("""\s*<finish-reason[^>]*/>""")

    /** 这条消息是否因输出预算耗尽而截断。 */
    fun isLengthTruncated(raw: String): Boolean = raw.contains(LENGTH_MARKER)

    /** 追加截断标记（已带标记不重复追加）。 */
    fun appendLengthMarker(content: String): String =
        if (isLengthTruncated(content)) content else content + LENGTH_MARKER

    /** 从任意文本里剥掉结束原因标记（显示 / 复制 / 导出路径统一走这里）。 */
    fun strip(raw: String): String = raw.replace(MARKER_REGEX, "")
}
