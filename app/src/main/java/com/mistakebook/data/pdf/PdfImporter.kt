package com.mistakebook.data.pdf

import android.content.Context
import com.mistakebook.data.AppFiles
import com.mistakebook.pipeline.RecognitionSubmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF 导入：文本 PDF 直接抽文字交给大模型，图片 PDF 逐页栅格化后走 MinerU。
 */
class PdfImporter(
    private val context: Context,
    private val files: AppFiles,
    private val submitter: RecognitionSubmitter
) {

    sealed class Result {
        data class Imported(val taskIds: List<Long>, val textMode: Boolean, val pageCount: Int) :
            Result()

        data class Failed(val message: String) : Result()
    }

    suspend fun importPdf(
        pdfFile: File,
        title: String,
        pageRange: List<Int>? = null
    ): Result = withContext(Dispatchers.IO) {
        try {
            val bytes = pdfFile.readBytes()
            val totalPages = PdfTextExtractor.pageCount(bytes)
            if (totalPages <= 0) return@withContext Result.Failed("PDF 没有可识别的页面")
            val wanted = pageRange?.filter { it in 1..totalPages }?.ifEmpty { null }
                ?: (1..totalPages).toList()

            val textPages = if (PdfTextExtractor.isTextPdf(bytes)) {
                PdfTextExtractor.extractAll(bytes).mapIndexedNotNull { index, text ->
                    val pageNumber = index + 1
                    if (pageNumber in wanted && text.isNotBlank()) text else null
                }
            } else {
                emptyList()
            }

            if (textPages.isNotEmpty()) {
                val ids = submitter.submitTextPages(textPages, title, files.importDir)
                return@withContext Result.Imported(ids, true, textPages.size)
            }

            val rendered = renderPages(pdfFile, wanted)
            if (rendered.isEmpty()) return@withContext Result.Failed("PDF 渲染失败")
            val ids = submitter.submitImages(rendered, title)
            Result.Imported(ids, false, rendered.size)
        } catch (error: Exception) {
            Result.Failed(error.message ?: "PDF 解析失败")
        }
    }

    /** 逐页栅格化成 150 DPI 的 JPEG（渲染实现在 [PdfPageRenderer]，拍题与资料库兜底共用）。 */
    private suspend fun renderPages(pdfFile: File, wanted: List<Int>): List<File> =
        PdfPageRenderer.render(pdfFile, wanted) { files.newPdfPageFile() }
}
