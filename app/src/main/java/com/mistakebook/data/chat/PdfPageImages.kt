package com.mistakebook.data.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

/**
 * 把 PDF 页面栅格化成图片（v0.0.58，零依赖，用系统 `PdfRenderer`）。
 *
 * ## 为什么需要它
 *
 * 扫描件 / 特殊字体编码的 PDF 抽不出文字（见 [PdfTextQuality]）。
 * 与其把乱码发给模型换一句「请把页面转成图片」，不如**客户端自己转**：
 * 页面图走既有的图片附件链路，模型用识图 / OCR 工具去读，一次到位。
 *
 * ## 为什么限量
 *
 * - 页数上限 [MAX_PAGES]：每张图都要 base64 进请求，太多会把上下文撑爆；
 * - 长边上限 [DEFAULT_LONG_EDGE_PX]：识别不需要原图精度，
 *   1500px 已经够 OCR，再大只是白占 token。
 *
 * 渲染失败（加密 PDF、损坏文件）不抛异常，返回空列表——调用方按
 * 「提取失败」提示用户，总比崩溃好。
 */
object PdfPageImages {

    /** 最多栅格化几页。 */
    const val MAX_PAGES = 4

    /** 默认长边（像素）。 */
    const val DEFAULT_LONG_EDGE_PX = 1500

    /**
     * @param pdf 源 PDF 文件。
     * @param targetFactory 产出图片文件（调用方决定落在哪个会话目录）。
     * @param maxPages 最多渲染几页。
     * @param longEdgePx 图片长边像素。
     * @return 渲染成功的图片路径列表（顺序即页序）；失败返回空列表。
     */
    fun render(
        pdf: File,
        targetFactory: (index: Int) -> File,
        maxPages: Int = MAX_PAGES,
        longEdgePx: Int = DEFAULT_LONG_EDGE_PX
    ): List<String> = runCatching {
        if (!pdf.isFile || pdf.length() <= 0L) return emptyList()
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val out = mutableListOf<String>()
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(descriptor)
            val pages = minOf(renderer.pageCount, maxPages.coerceAtLeast(1))
            for (index in 0 until pages) {
                val page = renderer.openPage(index)
                try {
                    val target = targetFactory(index)
                    val bitmap = pageBitmap(page.width, page.height, longEdgePx)
                    try {
                        // 先铺白底：PDF 页面常带透明通道，直接渲染会得到全黑图。
                        android.graphics.Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        target.outputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    if (target.length() > 0L) out += target.absolutePath
                } finally {
                    runCatching { page.close() }
                }
            }
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor.close() }
        }
        out
    }.getOrDefault(emptyList())

    /** 按长边上限算目标尺寸（保持页面比例）。 */
    private fun pageBitmap(pageW: Int, pageH: Int, longEdgePx: Int): Bitmap {
        val w = pageW.coerceAtLeast(1)
        val h = pageH.coerceAtLeast(1)
        val scale = longEdgePx.toFloat() / maxOf(w, h).toFloat()
        val targetW = (w * scale).roundToInt().coerceIn(1, 4096)
        val targetH = (h * scale).roundToInt().coerceIn(1, 4096)
        return Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    }
}
