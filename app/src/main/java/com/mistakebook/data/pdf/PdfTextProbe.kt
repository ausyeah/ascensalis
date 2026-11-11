package com.mistakebook.data.pdf

import java.io.File

// 选完 PDF 后的快速探测：用系统 PDF 引擎取真实页数，再决定是否能安全走文本链路。
object PdfTextProbe {

    data class Info(val pageCount: Int, val textMode: Boolean)

    // 超过这个大小就不整体读进内存了，直接提示分段导入
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    fun probe(file: File): Info {
        if (file.length() > MAX_FILE_BYTES) {
            error("PDF 超过 64MB，请分段导入")
        }
        val pageCount = PdfPageRenderer.pageCount(file)
        if (pageCount <= 0) return Info(pageCount, false)

        val bytes = file.readBytes()
        // 轻量抽取器只认识普通对象布局。页数不匹配时退回系统渲染 + OCR，
        // 避免压缩对象流被误判成 0 页或抽取出不完整页面集合。
        val textMode = PdfTextExtractor.pageCount(bytes) == pageCount &&
            PdfTextExtractor.isTextPdf(bytes)
        return Info(pageCount, textMode)
    }
}
