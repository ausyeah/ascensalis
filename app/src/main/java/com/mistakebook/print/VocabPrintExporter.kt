package com.mistakebook.print

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.mistakebook.wordbook.data.Word
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VocabTemplate {
    VOCAB_PRACTICE,
    VOCAB_CHEAT_SHEET
}

// 画布层没有 Context：空数据提示与页脚模板由调用方从 strings.xml 解析后注入。
class VocabPrintExporter(
    private val emptyHint: String,
    private val pageFooterTemplate: String
) {

    companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842

        fun calculatePages(itemCount: Int, itemsPerPage: Int): Int {
            if (itemCount == 0) return 1
            return (itemCount + itemsPerPage - 1) / itemsPerPage
        }

        fun measureRowsPerPage(pageHeight: Int, marginTop: Float, marginBottom: Float, rowHeight: Float): Int {
            val available = pageHeight - marginTop - marginBottom
            return (available / rowHeight).toInt()
        }
    }

    fun export(
        words: List<Word>,
        outputFile: File,
        template: VocabTemplate,
        title: String = "考研英语核心错词默写本",
        subtitle: String = ""
    ) {
        val document = PdfDocument()
        
        if (words.isEmpty()) {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = document.startPage(pageInfo)
            val paint = Paint().apply { 
                isAntiAlias = true
                color = Color.BLACK
                textSize = 20f
            }
            page.canvas.drawText(emptyHint, 50f, 50f, paint)
            document.finishPage(page)
            FileOutputStream(outputFile).use { document.writeTo(it) }
            document.close()
            return
        }

        when (template) {
            VocabTemplate.VOCAB_PRACTICE -> drawPractice(document, words, title, subtitle)
            VocabTemplate.VOCAB_CHEAT_SHEET -> drawCheatSheet(document, words, title, subtitle)
        }
        
        FileOutputStream(outputFile).use { document.writeTo(it) }
        document.close()
    }

    private fun drawPractice(document: PdfDocument, words: List<Word>, title: String, subtitle: String) {
        val marginTop = 80f
        val marginBottom = 50f
        val rowHeight = 40f
        
        val itemsPerPage = measureRowsPerPage(PAGE_HEIGHT, marginTop, marginBottom, rowHeight)
        val totalPages = calculatePages(words.size, itemsPerPage)

        val paint = Paint().apply { isAntiAlias = true }
        val linePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        val dashPaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
        }

        for (pageIndex in 0 until totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            drawHeaderAndFooter(canvas, title, subtitle, pageIndex + 1, totalPages, paint)

            val startIndex = pageIndex * itemsPerPage
            val endIndex = minOf(startIndex + itemsPerPage, words.size)
            
            val foldX = PAGE_WIDTH / 2f
            canvas.drawLine(foldX, marginTop, foldX, PAGE_HEIGHT - marginBottom, dashPaint)

            for (i in startIndex until endIndex) {
                val word = words[i]
                val y = marginTop + (i - startIndex) * rowHeight + 30f

                paint.color = Color.BLACK
                paint.textSize = 14f
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(word.word, 40f, y, paint)

                canvas.drawLine(40f + 120f, y, foldX - 20f, y, linePaint)

                paint.typeface = Typeface.DEFAULT
                paint.textSize = 12f
                val meaningText = if (word.pos.isNotEmpty()) "${word.pos} ${word.meaning}" else word.meaning
                var displayMeaning = meaningText.replace("\n", " ")
                if (displayMeaning.length > 25) {
                    displayMeaning = displayMeaning.substring(0, 24) + "..."
                }
                
                canvas.drawText(displayMeaning, foldX + 20f, y, paint)
            }

            document.finishPage(page)
        }
    }

    private fun drawCheatSheet(document: PdfDocument, words: List<Word>, title: String, subtitle: String) {
        val marginTop = 80f
        val marginBottom = 50f
        val marginLeft = 20f
        val marginRight = 20f
        val colCount = 4
        val colWidth = (PAGE_WIDTH - marginLeft - marginRight) / colCount
        val rowHeight = 60f
        
        val rowsPerPage = measureRowsPerPage(PAGE_HEIGHT, marginTop, marginBottom, rowHeight)
        val itemsPerPage = rowsPerPage * colCount
        val totalPages = calculatePages(words.size, itemsPerPage)

        val paint = Paint().apply { isAntiAlias = true }
        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }

        for (pageIndex in 0 until totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            drawHeaderAndFooter(canvas, title, subtitle, pageIndex + 1, totalPages, paint)

            val startIndex = pageIndex * itemsPerPage
            val endIndex = minOf(startIndex + itemsPerPage, words.size)

            for (i in startIndex until endIndex) {
                val localIndex = i - startIndex
                val col = localIndex % colCount
                val row = localIndex / colCount

                val x = marginLeft + col * colWidth
                val y = marginTop + row * rowHeight

                canvas.drawRect(x, y, x + colWidth, y + rowHeight, borderPaint)

                val word = words[i]
                paint.color = Color.BLACK
                paint.textSize = 12f
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(word.word, x + 5f, y + 20f, paint)

                paint.textSize = 10f
                paint.typeface = Typeface.DEFAULT
                paint.color = Color.DKGRAY
                val posStr = if (word.pos.isNotEmpty()) "[${word.pos}]" else ""
                canvas.drawText(posStr, x + 5f, y + 35f, paint)

                var meaningText = word.meaning.replace("\n", " ")
                if (meaningText.length > 12) {
                    meaningText = meaningText.substring(0, 11) + "..."
                }
                canvas.drawText(meaningText, x + 5f, y + 50f, paint)
            }

            document.finishPage(page)
        }
    }

    private fun drawHeaderAndFooter(
        canvas: Canvas,
        title: String,
        subtitle: String,
        currentPage: Int,
        totalPages: Int,
        paint: Paint
    ) {
        paint.color = Color.BLACK
        paint.textSize = 18f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(title, 40f, 40f, paint)

        paint.textSize = 12f
        paint.typeface = Typeface.DEFAULT
        paint.color = Color.GRAY
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        canvas.drawText("$subtitle  $dateStr", 40f, 60f, paint)

        val footerText = pageFooterTemplate.format(currentPage, totalPages)
        paint.textSize = 12f
        val textWidth = paint.measureText(footerText)
        canvas.drawText(footerText, (PAGE_WIDTH - textWidth) / 2f, PAGE_HEIGHT - 20f, paint)
    }
}
