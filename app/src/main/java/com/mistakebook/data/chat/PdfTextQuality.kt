package com.mistakebook.data.chat

/**
 * PDF 抽出文本的**可用性**判定（v0.0.58，纯函数，可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 用户反馈「发送 PDF 不能正确调用工具读取识别」，截图里气泡写着
 * 「已提取 631 字」，而模型看到的是 `JeMä\x...` 这种**原始字节流**。
 *
 * 根因：[com.mistakebook.data.pdf.PdfTextExtractor] 对非标准内容流的兜底是
 * 「按未压缩原始数据处理」，于是把 PDF 的二进制原文当文本返回了。
 * 旧逻辑只看「非空即成功」，于是乱码被当成成果发给模型——模型只能回一句
 * 「这是一串乱码，请把页面转成图片」，用户白折腾一轮。
 *
 * 现在先过一遍质量检查，不可用就走页面栅格化（见 [PdfPageImages]），
 * 让模型直接用识图/OCR 工具读图。
 */
object PdfTextQuality {

    /** 控制字符占比上限（\n \r \t 不算）。超过即认定不是文本。 */
    private const val MAX_CONTROL_RATIO = 0.02f

    /** 「像文字」的字符占比下限。低于它说明抽出来的是二进制噪声。 */
    private const val MIN_TEXT_RATIO = 0.6f

    /**
     * 抽出的文本能不能当正文用。
     *
     * 判据两条，任一不过即不可用：
     * 1. 控制字符（除 换行/回车/制表）占比过高——二进制数据的典型特征；
     * 2. 「像文字」的字符（中日韩、拉丁字母、数字、常用标点、空白）占比过低。
     */
    fun isUsable(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_USEFUL_CHARS) return false
        var control = 0
        var textLike = 0
        for (ch in trimmed) {
            val code = ch.code
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> textLike++
                code < 0x20 || code == 0x7F -> control++
                isTextLike(ch) -> textLike++
                else -> Unit
            }
        }
        val total = trimmed.length.toFloat()
        if (control / total > MAX_CONTROL_RATIO) return false
        return textLike / total >= MIN_TEXT_RATIO
    }

    /** 少于这个长度不认为是有效正文（避免把 `1` 这种噪声当成抽到了东西）。 */
    private const val MIN_USEFUL_CHARS = 8

    /** 常见文字字符：CJK、拉丁、数字、常用中英文标点、空白。 */
    private fun isTextLike(ch: Char): Boolean {
        val code = ch.code
        return when {
            code in 0x20..0x7E -> true // ASCII 可打印
            ch == '　' -> true // 全角空格
            code in 0x3000..0x303F -> true // CJK 标点
            code in 0xFF00..0xFFEF -> true // 全角字符
            code in 0x4E00..0x9FFF -> true // CJK 统一表意
            code in 0x3400..0x4DBF -> true // CJK 扩展 A
            code in 0x3040..0x30FF -> true // 日文假名
            code in 0xAC00..0xD7AF -> true // 韩文
            else -> false
        }
    }
}
