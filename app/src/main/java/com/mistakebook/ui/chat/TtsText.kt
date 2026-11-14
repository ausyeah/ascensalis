package com.mistakebook.ui.chat

/**
 * 朗读文本的清洗（v0.0.154，学 deepseek-ai/chat-demo 的 ReadAloudButton）。
 *
 * 助手回答是 Markdown + LaTeX：`#`/`*`/反引号读出来是噪声，`$公式$`
 * 读出来是乱码。朗读前把「给眼睛的记号」剥掉、把「给耳朵的内容」留下：
 *
 * - 围栏代码块整段移除（读代码没有意义）；
 * - 行内代码只留内容；
 * - 公式（$...$ / $$...$$）整段移除——TTS 读 LaTeX 是乱码；
 * - 图片移除，链接只留文字；
 * - Markdown 记号（# > * - | 表格线）剥掉；
 * - 连续空白压成单空格。
 *
 * 纯函数、零 Android 依赖，JVM 单测直接覆盖。
 */
object TtsText {

    fun plain(markdown: String): String {
        var text = markdown

        // 围栏代码块整段移除（```…```，语言标记可带）
        text = text.replace(Regex("```[a-zA-Z]*\\n[\\s\\S]*?```"), " ")
        text = text.replace(Regex("```[\\s\\S]*?```"), " ")

        // 公式整段移除（先块级后行内；未闭合的块级也移除——朗读场景宁可少读）
        text = text.replace(Regex("\\$\\$[\\s\\S]*?\\$\\$"), " ")
        text = text.replace(Regex("\\$[^$\\n]+\\$"), " ")

        // 图片移除，链接只留文字
        text = text.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        text = text.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")

        // 行内代码只留内容
        text = text.replace(Regex("`([^`]*)`"), "$1")

        // Markdown 记号：标题 / 引用 / 强调 / 列表 / 表格线 / 分隔线
        text = text.replace(Regex("(?m)^#{1,6}\\s*"), "")
        text = text.replace(Regex("(?m)^>\\s?"), "")
        text = text.replace(Regex("\\*\\*|__|~~"), "")
        text = text.replace(Regex("(?m)^\\s*([-*+]\\s+|\\|)"), "")
        text = text.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), " ")
        text = text.replace("|", " ")

        // 连续空白压成单空格
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }
}
