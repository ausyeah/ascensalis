package com.mistakebook.util

import java.io.File
import java.util.Locale

/**
 * 封面缩略图的兜底判定与文案（**纯 Kotlin，可 JVM 单测**）。
 *
 * ## 为什么需要它
 *
 * 纯文本 PDF 路线（[com.mistakebook.data.local.entities.CaptureTask.SOURCE_PDF_TEXT]）
 * 的任务，`photoPath` 存的是**从 PDF 抽出来的 .md 文件**，不是图片。
 * 题目封面直接拿它去解码必然失败，列表里就是一块空白——用户看到的是
 * 「PDF 识别出来的错题没有封面」。
 *
 * 同理还有：原图被删、文件损坏、后缀是 .pdf/.txt/.md。
 * 这些场景统一走「文字封面」：把题干开头几个字画在占位块上，
 * 列表里至少有一个能一眼认出的视觉锚点。
 */
object CoverFallback {

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"
    )

    /**
     * 这个路径**看起来像不像一张能解码的图**。
     *
     * 只看后缀与存在性，不真的去解码——这是组合期的同步判断，
     * 开销必须可以忽略（列表滚动时每一行都会问一次）。
     */
    fun isLikelyImage(path: String): Boolean {
        if (path.isBlank()) return false
        // content:// 这类 Uri 先放行：它不是文件路径（exists 一定为 false），
        // 但它是系统给的图片，后缀也可能根本没有。
        if (path.startsWith("content://", ignoreCase = true)) return true
        val file = File(path)
        if (!file.exists() || !file.isFile) return false
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * 从题干取封面文字：压平空白、去掉 Markdown 记号，最多 [max] 个字符。
     *
     * 去掉 Markdown 记号是必要的：题干开头常常是 `**` 或 `#`，
     * 直接截前几个字会得到一片星号。
     *
     * **只删 `*`、`#`、反引号、`~` 这几种标记**：数学题干里 `>` `<`
     * `(` `)` `$` `_` 全是内容（$x^2$、`a>0`、`x_1`），
     * 一并删掉的话封面就成了「已知 a0 求」——把题都改坏了。
     */
    fun label(text: String, max: Int = 8): String {
        val flat = text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[*#`~]"), "")
            .trim()
        if (flat.isEmpty()) return ""
        return if (flat.length <= max) flat else flat.substring(0, max)
    }
}
