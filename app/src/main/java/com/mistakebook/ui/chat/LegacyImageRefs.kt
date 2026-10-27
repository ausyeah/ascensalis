package com.mistakebook.ui.chat

/**
 * 清理消息正文里的**本地绝对路径图片引用**。
 *
 * ## 为什么需要
 *
 * v0.0.46 的生图工具最初把结果写成 `![](/data/user/0/.../p_xxx.png)` 追加
 * 到回答末尾。这条路径有两个问题：
 * 1. 指向 `filesDir/images/gen` 的缓存文件——缓存清理后就是死链；
 * 2. 有用户反馈「生图后这个对话点进去就闪退」，而会话里唯一的新东西
 *    就是这段路径。把本地绝对路径交给 Coil 解码这条链路不可靠。
 *
 * 现在生图结果改存**会话附件**（`gen_*.png` 复制进会话目录，按 messageId
 * 挂到消息上，走已验证的气泡图片渲染）。但**库里已经有旧消息**——
 * 不清掉的话，那几条会话依然打不开。
 *
 * 这里只剥「本地绝对路径」的图片引用，远程 URL 图片
 * （`![](https://...)`）照常保留：那是正常内容，不该误伤。
 *
 * 纯函数，可 JVM 单测。
 */
object LegacyImageRefs {

    /** 本地绝对路径的图片语法：`![](/...)`（Unix）或 `![](file://...)`。 */
    private val LOCAL_IMAGE = Regex(
        """!\[[^\]]*]\(\s*(?:file://)?/[^)\s]*\s*\)"""
    )

    /** 清理后留下的说明：让用户知道这里原本有一张图，而不是内容凭空少一块。 */
    private const val REPLACEMENT = "\n[此处原有一张示意图，已清理]\n"

    fun strip(text: String): String {
        if (text.isBlank() || !text.contains("![")) return text
        val cleaned = LOCAL_IMAGE.replace(text, REPLACEMENT)
        // 清完可能出现连续三个以上换行，压回两个（Markdown 段落间距）
        return cleaned.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
