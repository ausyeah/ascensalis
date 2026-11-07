package com.mistakebook.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * PDF → 页图的共享栅格化（v0.0.131）。
 *
 * 原来是 [PdfImporter] 的私有实现；资料库索引的扫描件兜底（v0.0.131）
 * 同样要把 PDF 变成页图喂给识别渠道，同一件事两处用就提出来。
 *
 * 长边封顶 [MAX_RASTER_EDGE]、放大上限 [MAX_SCALE]（约等于 150 DPI）：
 * 拍题题面的常用精度，文字清晰又不至于把图撑得过大；OOM 时降到
 * RGB_565 再试一次——低端机全卷渲染最容易在这里炸。
 *
 * 输出文件由 [newPageFile] 提供：拍题链路走 CaptureFiles 的页图命名，
 * 资料库兜底走 cache 临时目录，互不污染。
 */
object PdfPageRenderer {

    /**
     * 逐页栅格化成 JPEG。
     *
     * @param wanted 页码列表（1 起）；null = 全部页；越界页跳过。
     * @param newPageFile 每页的输出文件工厂。
     * @return 渲染成功的页图；整体失败时返回空列表（调用方降级）。
     */
    suspend fun render(
        pdfFile: File,
        wanted: List<Int>? = null,
        newPageFile: () -> File
    ): List<File> = withContext(Dispatchers.IO) {
        val output = mutableListOf<File>()
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            descriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            renderer = PdfRenderer(descriptor)
            (wanted ?: (1..renderer.pageCount).toList()).forEach { pageNumber ->
                val index = pageNumber - 1
                if (index < 0 || index >= renderer.pageCount) return@forEach
                val rendered = renderPageSafely(renderer, index, newPageFile) ?: return@forEach
                output += rendered
            }
        } catch (error: Exception) {
            output.clear()
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
        output
    }

    // 单页渲染带内存保护：长边封顶 + OOM 时降级到 RGB_565 再试
    private fun renderPageSafely(renderer: PdfRenderer, index: Int, newPageFile: () -> File): File? {
        val first = try {
            renderOnePage(renderer, index, Bitmap.Config.ARGB_8888, newPageFile)
        } catch (oom: OutOfMemoryError) {
            null
        }
        if (first != null) return first
        return try {
            renderOnePage(renderer, index, Bitmap.Config.RGB_565, newPageFile)
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    private fun renderOnePage(
        renderer: PdfRenderer,
        index: Int,
        config: Bitmap.Config,
        newPageFile: () -> File
    ): File? {
        renderer.openPage(index).use { page ->
            val longEdge = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = minOf(MAX_RASTER_EDGE.toFloat() / longEdge, MAX_SCALE)
            val width = (page.width * scale).toInt().coerceIn(100, MAX_RASTER_EDGE * 2)
            val height = (page.height * scale).toInt().coerceIn(100, MAX_RASTER_EDGE * 2)
            val bitmap = Bitmap.createBitmap(width, height, config)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val target = newPageFile()
            FileOutputStream(target).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            }
            bitmap.recycle()
            return target
        }
    }

    private const val MAX_RASTER_EDGE = 1600
    private const val MAX_SCALE = 150f / 72f
}
