package com.mistakebook.print

/**
 * PDF 导出的文本断行算法（纯 Kotlin，无 Android 依赖，JVM 可测）。
 *
 * ## 为什么独立出来（v0.0.59 折行修复）
 *
 * 旧实现把「当前行的元素列表」按值传进 appendTextToLine，而 flush 发生在
 * 外层闭包里——换行后外层换上了新列表，函数手里仍攥着已被提交的旧列表，
 * 于是第一个换行点之后的文字全部追加进第一行（画出页边被裁），新行永远为空。
 * 根因是**行状态被复制了一份**。这里把「当前行」收敛成一个稳定的可变对象
 * （[PdfComposeSink]），flush 后 sink 内部自换新行，算法只认这个对象，
 * 旧引用问题从结构上不可能再发生。
 *
 * 断行规则与旧算法一致：中文逐字、西文按词累积；宽度超限时在「行内已有
 * 内容」的前提下换行，单个超宽元素独占一行（无法再切）。
 */
internal interface PdfComposeSink {
    /** 当前行的已用宽度。 */
    val currentLineWidth: Float

    /** 当前行是否已有内容（决定超宽时是否换行）。 */
    val hasContent: Boolean

    /** 追加一个文本片段及其宽度到当前行。 */
    fun append(text: String, width: Float, fontSize: Float, color: Int)

    /** 换行：把当前行提交给调用方，之后的 append 落到新行。 */
    fun flushLine()
}

internal object PdfTextWrap {

    /**
     * 把一段文本按自然中英文断行加入当前行。
     *
     * [text] 里的 '\n' 不在这里处理：调用方负责先按硬换行切好段
     * （见 PdfExporter.layoutRichTokens 的 TextToken 分支）。
     */
    fun appendText(
        sink: PdfComposeSink,
        text: String,
        fontSize: Float,
        color: Int,
        maxWidth: Float,
        measure: (String) -> Float
    ) {
        val wordBuffer = StringBuilder()

        fun flushWordBuffer() {
            if (wordBuffer.isEmpty()) return
            val word = wordBuffer.toString()
            val wordW = measure(word)
            if (sink.currentLineWidth + wordW > maxWidth && sink.hasContent) {
                sink.flushLine()
            }
            sink.append(word, wordW, fontSize, color)
            wordBuffer.clear()
        }

        text.forEach { ch ->
            if (ch.code >= 0x2E80 || ch.isWhitespace()) {
                // 中文字符或空白标点作为独立断行边界
                flushWordBuffer()
                val charStr = ch.toString()
                val charW = measure(charStr)
                if (sink.currentLineWidth + charW > maxWidth && sink.hasContent) {
                    sink.flushLine()
                }
                sink.append(charStr, charW, fontSize, color)
            } else {
                // 西文字符/数字累积在单词中，避免单词断开
                wordBuffer.append(ch)
            }
        }
        flushWordBuffer()
    }
}
