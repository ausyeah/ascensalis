package com.mistakebook.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.mistakebook.math.MathLayout
import com.mistakebook.math.MathRenderer
import com.mistakebook.math.RenderedMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原生 A4 PDF 导出引擎（PRD 第 8 节标准实现）。
 *
 * ## 架构与排版保证
 *
 * 1. **全原生标准**：严格使用 Android 原生 [PdfDocument] + [Canvas]，
 *    绝不引入任何第三方 PDF 库，符合安全与打包体积红线。
 * 2. **版面尺寸**：标准 A4 portrait（595 × 842 pt），左右页边距 42 pt，
 *    可用宽度 511 pt；可用内容高度 712 pt。
 * 3. **公式排版**：
 *    - 异步批量调用 [MathRenderer.renderAll] 获取透明位图与基准字号；
 *    - 利用 [MathLayout.fit] 等比缩放，公式字号与正文文字完全匹配；
 *    - 针对行内公式与独立块级公式分别排版，行高动态迁就最高公式（[MathLayout.lineHeightFor]），
 *      彻底杜绝行间文字与公式重叠；
 *    - 渲染失败或极端语法异常时，优雅回退为源码文本排版，绝不崩溃、绝不丢题。
 * 4. **纯空白答题/重做区**：
 *    - **严禁绘制任何横线、网格线或横条**（完全遵循用户指示）；
 *    - 留白间距与高度严格受用户选项 [ExportCard.blankHeightPt] 控制；
 *    - 保留纯净的手写答题空间。
 * 5. **两阶段分页算法**：
 *    - 阶段一：测量所有卡片并切分成单页绘制指令列表，精准获取总页数 `M`，
 *      且每行/每块均有独立边界，文字绝不被截断；
 *    - 阶段二：逐页生成 [PdfDocument.Page]，在页眉写入文档信息、页脚写入
 *      「第 N / M 页」，并完整绘制该页元素。
 */
// 画布层没有 Context：重做区角标与页脚模板由调用方从 strings.xml 解析后注入。
class PdfExporter(
    private val mathRenderer: MathRenderer,
    private val redoTagText: String,
    private val pageFooterTemplate: String
) {

    companion object {
        // A4 尺寸常量（单位：pt，1 pt = 1/72 inch）
        const val PAGE_WIDTH_PT = 595f
        const val PAGE_HEIGHT_PT = 842f
        const val MARGIN_H_PT = 42f
        const val MARGIN_V_PT = 42f
        const val USABLE_WIDTH_PT = 511f // 595 - 42 * 2

        const val HEADER_HEIGHT_PT = 26f
        const val FOOTER_HEIGHT_PT = 20f

        const val CONTENT_TOP_PT = 68f // MARGIN_V_PT + HEADER_HEIGHT_PT
        const val CONTENT_BOTTOM_PT = 780f // PAGE_HEIGHT_PT - MARGIN_V_PT - FOOTER_HEIGHT_PT
        const val USABLE_CONTENT_HEIGHT_PT = 712f

        // 字号常量
        const val FONT_TITLE_PT = 13f
        const val FONT_STEM_PT = 14f
        const val FONT_OPTION_PT = 12.5f
        const val FONT_ANSWER_PT = 12f
        const val FONT_ANALYSIS_PT = 12f
        const val FONT_CAPTION_PT = 8.5f
        const val FONT_HEADER_PAGE_PT = 10f
        const val FONT_FOOTER_PAGE_PT = 9.5f
        const val FONT_TAG_PT = 11f

        // 颜色常量
        val COLOR_TEXT_PRIMARY = Color.rgb(0x21, 0x21, 0x21)
        val COLOR_TEXT_SECONDARY = Color.rgb(0x55, 0x55, 0x55)
        val COLOR_TEXT_MUTED = Color.rgb(0x88, 0x88, 0x88)
        val COLOR_DIVIDER = Color.rgb(0xE0, 0xE0, 0xE0)
        val COLOR_ANSWER_TAG = Color.rgb(0x1B, 0x5E, 0x20) // 墨绿
        val COLOR_ANALYSIS_TAG = Color.rgb(0x0D, 0x47, 0xA1) // 深蓝
    }

    /**
     * 将导出文档渲染并写出为 PDF 文件。
     */
    suspend fun export(doc: ExportDoc, target: File): ExportResult = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()

        // 1. 批量预渲染全部公式
        val mathMap = preRenderAllMath(doc)

        // 2. 第一阶段：排版测量并分页切分
        val pages = layoutDocument(doc, mathMap, skipped)

        val totalPages = pages.size.coerceAtLeast(1)

        // 3. 第二阶段：渲染写入 PdfDocument
        val pdfDocument = PdfDocument()
        val textPaint = TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val linePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = COLOR_DIVIDER
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(doc.generatedAt))

        try {
            pages.forEachIndexed { pageIndex, pageLayout ->
                val pageNumber = pageIndex + 1
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH_PT.toInt(),
                    PAGE_HEIGHT_PT.toInt(),
                    pageNumber
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // 绘制页眉
                drawHeader(canvas, textPaint, linePaint, doc.title, dateStr)

                // 绘制页脚（第 N / M 页）
                drawFooter(canvas, textPaint, pageNumber, totalPages)

                // 绘制本页所有元素
                pageLayout.drawItems.forEach { item ->
                    item.draw(canvas, textPaint, linePaint)
                }

                pdfDocument.finishPage(page)
            }

            // 写出到目标文件
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
        } finally {
            pdfDocument.close()
        }

        ExportResult(
            file = target,
            pageCount = totalPages,
            skipped = skipped
        )
    }

    // =========================================================================
    // 阶段一：公式预渲染
    // =========================================================================

    private suspend fun preRenderAllMath(doc: ExportDoc): Map<String, RenderedMath?> {
        val mathItems = mutableListOf<Triple<String, String, Boolean>>()

        fun collectTokens(tokens: List<RichToken>) {
            tokens.forEach { token ->
                if (token is RichToken.MathToken) {
                    val key = (if (token.display) "d:" else "i:") + token.latex
                    mathItems.add(Triple(key, token.latex, token.display))
                }
            }
        }

        doc.cards.forEach { card ->
            collectTokens(card.stem)
            card.options.forEach { option ->
                collectTokens(tokenizeWithMath(option.text))
            }
            collectTokens(card.answer)
            collectTokens(card.analysis)
        }

        if (mathItems.isEmpty()) return emptyMap()

        return runCatching {
            mathRenderer.renderAll(
                mathItems.distinctBy { it.first },
                colorCss = MathRenderer.FG_CSS_LIGHT
            )
        }.getOrDefault(emptyMap())
    }

    // =========================================================================
    // 阶段二：文档排版测量与分页切分
    // =========================================================================

    private sealed interface PageDrawItem {
        fun draw(canvas: Canvas, textPaint: TextPaint, linePaint: Paint)
    }

    private data class TextLineDrawItem(
        val x: Float,
        val y: Float,
        val text: String,
        val fontSize: Float,
        val color: Int,
        val isBold: Boolean = false
    ) : PageDrawItem {
        override fun draw(canvas: Canvas, textPaint: TextPaint, linePaint: Paint) {
            textPaint.textSize = fontSize
            textPaint.color = color
            textPaint.isFakeBoldText = isBold
            canvas.drawText(text, x, y, textPaint)
        }
    }

    private data class BitmapDrawItem(
        val rect: RectF,
        val bitmap: Bitmap
    ) : PageDrawItem {
        override fun draw(canvas: Canvas, textPaint: TextPaint, linePaint: Paint) {
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, rect, null)
            }
        }
    }

    private data class DividerLineDrawItem(
        val startX: Float,
        val startY: Float,
        val stopX: Float,
        val stopY: Float,
        val strokeWidthPt: Float = 0.5f,
        val color: Int = COLOR_DIVIDER
    ) : PageDrawItem {
        override fun draw(canvas: Canvas, textPaint: TextPaint, linePaint: Paint) {
            linePaint.strokeWidth = strokeWidthPt
            linePaint.color = color
            canvas.drawLine(startX, startY, stopX, stopY, linePaint)
        }
    }

    private class PageLayout(
        val drawItems: MutableList<PageDrawItem> = mutableListOf()
    )

    private class MeasuredLine(
        val height: Float,
        val items: List<LineElement>
    )

    private sealed interface LineElement {
        val width: Float
        val height: Float
    }

    private data class TextElement(
        val text: String,
        override val width: Float,
        override val height: Float,
        val fontSize: Float,
        val color: Int,
        val isBold: Boolean = false
    ) : LineElement

    private data class MathElement(
        val bitmap: Bitmap,
        override val width: Float,
        override val height: Float
    ) : LineElement

    /**
     * 一行的累积状态（元素与宽度同源）。[PdfTextWrap] 只认这个对象：
     * flush 后内部换成新列表，后续追加落进新行——旧引用误用从结构上不可能再发生。
     */
    private class ComposingLine(
        private val onFlush: (List<LineElement>) -> Unit
    ) : PdfComposeSink {
        var elements = mutableListOf<LineElement>()
        override var currentLineWidth = 0f
        override val hasContent: Boolean get() = elements.isNotEmpty()
        override fun append(text: String, width: Float, fontSize: Float, color: Int) {
            elements.add(TextElement(text, width, fontSize, fontSize, color))
            currentLineWidth += width
        }
        override fun flushLine() {
            if (elements.isEmpty()) return
            onFlush(elements)
            elements = mutableListOf()
            currentLineWidth = 0f
        }
    }

    private fun layoutDocument(
        doc: ExportDoc,
        mathMap: Map<String, RenderedMath?>,
        skipped: MutableList<String>
    ): List<PageLayout> {
        val pages = mutableListOf<PageLayout>()
        var currentPage = PageLayout()
        pages.add(currentPage)
        var currentY = CONTENT_TOP_PT

        val textPaint = TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        fun ensureSpace(neededHeight: Float): Float {
            if (currentY + neededHeight > CONTENT_BOTTOM_PT && currentY > CONTENT_TOP_PT) {
                currentPage = PageLayout()
                pages.add(currentPage)
                currentY = CONTENT_TOP_PT
            }
            return currentY
        }

        doc.cards.forEachIndexed { cardIndex, card ->
            try {
                // 卡片预留检查：标题 + 题干首行，避免孤题号遗留页底
                val titleText = card.header + (card.subjectName?.let { " $it" } ?: "")
                textPaint.textSize = FONT_TITLE_PT
                val titleHeight = FONT_TITLE_PT * 1.5f

                // 排版题干
                val stemLines = layoutRichTokens(
                    tokens = card.stem,
                    fontSize = FONT_STEM_PT,
                    textColor = COLOR_TEXT_PRIMARY,
                    maxWidth = USABLE_WIDTH_PT,
                    paint = textPaint,
                    mathMap = mathMap
                )

                val headMinHeight = titleHeight + (stemLines.firstOrNull()?.height ?: 20f)
                ensureSpace(headMinHeight)

                // 1. 绘制标题行
                val titleY = currentY + FONT_TITLE_PT
                currentPage.drawItems.add(
                    TextLineDrawItem(
                        x = MARGIN_H_PT,
                        y = titleY,
                        text = titleText,
                        fontSize = FONT_TITLE_PT,
                        color = COLOR_TEXT_PRIMARY,
                        isBold = true
                    )
                )
                currentY += titleHeight + 4f

                // 2. 绘制题干行
                stemLines.forEach { line ->
                    currentY = ensureSpace(line.height)
                    emitLineDrawItems(line, currentY, MARGIN_H_PT, currentPage)
                    currentY += line.height
                }
                currentY += 4f

                // 3. 绘制原图 / 附图（若开启）
                if (doc.options.includeImage && card.imagePath.isNotBlank()) {
                    val imageFile = File(card.imagePath)
                    if (imageFile.exists() && imageFile.canRead()) {
                        val decoded = decodeSampledBitmap(imageFile, USABLE_WIDTH_PT * 0.45f, 220f)
                        if (decoded != null) {
                            val imgW = decoded.first.width.toFloat()
                            val imgH = decoded.first.height.toFloat()
                            val captionHeight = FONT_CAPTION_PT * 1.4f
                            val totalImgBlockH = imgH + 4f + captionHeight + 6f

                            currentY = ensureSpace(totalImgBlockH)

                            val imgX = MARGIN_H_PT + (USABLE_WIDTH_PT - imgW) / 2f
                            currentPage.drawItems.add(
                                BitmapDrawItem(
                                    rect = RectF(imgX, currentY, imgX + imgW, currentY + imgH),
                                    bitmap = decoded.first
                                )
                            )

                            val captionText = if (card.imageIsFigure) "题目附图" else "原题照片"
                            textPaint.textSize = FONT_CAPTION_PT
                            val capW = textPaint.measureText(captionText)
                            val capX = MARGIN_H_PT + (USABLE_WIDTH_PT - capW) / 2f
                            val capY = currentY + imgH + 4f + FONT_CAPTION_PT
                            currentPage.drawItems.add(
                                TextLineDrawItem(
                                    x = capX,
                                    y = capY,
                                    text = captionText,
                                    fontSize = FONT_CAPTION_PT,
                                    color = COLOR_TEXT_MUTED
                                )
                            )

                            currentY += totalImgBlockH
                        }
                    }
                }

                // 4. 绘制选项
                if (card.options.isNotEmpty()) {
                    card.options.forEachIndexed { optIndex, option ->
                        val optionTokens = mutableListOf<RichToken>()
                        val label = option.label.ifBlank { com.mistakebook.domain.labelFor(optIndex) }
                        optionTokens.add(RichToken.TextToken("$label. "))
                        optionTokens.addAll(tokenizeWithMath(option.text))

                        val optLines = layoutRichTokens(
                            tokens = optionTokens,
                            fontSize = FONT_OPTION_PT,
                            textColor = COLOR_TEXT_PRIMARY,
                            maxWidth = USABLE_WIDTH_PT,
                            paint = textPaint,
                            mathMap = mathMap
                        )

                        optLines.forEach { line ->
                            currentY = ensureSpace(line.height)
                            emitLineDrawItems(line, currentY, MARGIN_H_PT, currentPage)
                            currentY += line.height
                        }
                    }
                    currentY += 4f
                }

                // 5. 绘制答案与解析（若开启）
                if (card.hasAnswer) {
                    val answerTokens = mutableListOf<RichToken>()
                    answerTokens.add(RichToken.TextToken("【答案】 "))
                    answerTokens.addAll(card.answer)

                    val ansLines = layoutRichTokens(
                        tokens = answerTokens,
                        fontSize = FONT_ANSWER_PT,
                        textColor = COLOR_ANSWER_TAG,
                        maxWidth = USABLE_WIDTH_PT,
                        paint = textPaint,
                        mathMap = mathMap
                    )
                    ansLines.forEach { line ->
                        currentY = ensureSpace(line.height)
                        emitLineDrawItems(line, currentY, MARGIN_H_PT, currentPage)
                        currentY += line.height
                    }
                }

                if (card.hasAnalysis) {
                    val analysisTokens = mutableListOf<RichToken>()
                    analysisTokens.add(RichToken.TextToken("【解析】 "))
                    analysisTokens.addAll(card.analysis)

                    val anaLines = layoutRichTokens(
                        tokens = analysisTokens,
                        fontSize = FONT_ANALYSIS_PT,
                        textColor = COLOR_TEXT_SECONDARY,
                        maxWidth = USABLE_WIDTH_PT,
                        paint = textPaint,
                        mathMap = mathMap
                    )
                    anaLines.forEach { line ->
                        currentY = ensureSpace(line.height)
                        emitLineDrawItems(line, currentY, MARGIN_H_PT, currentPage)
                        currentY += line.height
                    }
                }

                // 6. 绘制重做留白区（若开启）
                // 核心注意：用户明确要求「不要横线，只要空白，间距由重做区高度调节」
                if (doc.options.blankRedoMode) {
                    val blankH = card.blankHeightPt.toFloat().coerceAtLeast(40f)
                    currentY = ensureSpace(blankH)

                    // 仅在左上角绘制微弱极细的【重做区】字样，下方绝对不画任何横线或网格
                    val tagText = redoTagText
                    val tagY = currentY + FONT_TAG_PT
                    currentPage.drawItems.add(
                        TextLineDrawItem(
                            x = MARGIN_H_PT,
                            y = tagY,
                            text = tagText,
                            fontSize = FONT_TAG_PT,
                            color = COLOR_TEXT_MUTED
                        )
                    )

                    // 纯空白区域直接留出 blankH 高度空间供书写
                    currentY += blankH
                }

                // 7. 题与题之间的分割间距与浅灰分割线
                if (cardIndex < doc.cards.size - 1) {
                    if (CONTENT_BOTTOM_PT - currentY >= 18f) {
                        val dividerY = currentY + 9f
                        currentPage.drawItems.add(
                            DividerLineDrawItem(
                                startX = MARGIN_H_PT,
                                startY = dividerY,
                                stopX = MARGIN_H_PT + USABLE_WIDTH_PT,
                                stopY = dividerY,
                                strokeWidthPt = 0.5f,
                                color = COLOR_DIVIDER
                            )
                        )
                        currentY += 18f
                    } else {
                        // 剩余不足 18pt，直接切入新一页顶部开始下一题
                        currentPage = PageLayout()
                        pages.add(currentPage)
                        currentY = CONTENT_TOP_PT
                    }
                }
            } catch (cardError: Throwable) {
                android.util.Log.e("PdfExporter", "卡片 ${card.questionId} 排版失败", cardError)
                skipped.add(card.header)
            }
        }

        return pages.filter { it.drawItems.isNotEmpty() }.ifEmpty { listOf(PageLayout()) }
    }

    /**
     * 将行内的图文元素添加到页面的具体坐标点。
     */
    private fun emitLineDrawItems(
        line: MeasuredLine,
        lineTopY: Float,
        startX: Float,
        page: PageLayout
    ) {
        var currentX = startX
        line.items.forEach { element ->
            when (element) {
                is TextElement -> {
                    // 文字基线对齐：使文字在行内垂直居中
                    val baselineY = lineTopY + (line.height - element.height) / 2f + element.fontSize * 0.85f
                    page.drawItems.add(
                        TextLineDrawItem(
                            x = currentX,
                            y = baselineY,
                            text = element.text,
                            fontSize = element.fontSize,
                            color = element.color,
                            isBold = element.isBold
                        )
                    )
                    currentX += element.width
                }

                is MathElement -> {
                    // 公式垂直居中对齐
                    val mathTopY = lineTopY + (line.height - element.height) / 2f
                    page.drawItems.add(
                        BitmapDrawItem(
                            rect = RectF(currentX, mathTopY, currentX + element.width, mathTopY + element.height),
                            bitmap = element.bitmap
                        )
                    )
                    currentX += element.width
                }
            }
        }
    }

    // =========================================================================
    // 富文本与公式图文混排断行引擎
    // =========================================================================

    private fun layoutRichTokens(
        tokens: List<RichToken>,
        fontSize: Float,
        textColor: Int,
        maxWidth: Float,
        paint: TextPaint,
        mathMap: Map<String, RenderedMath?>
    ): List<MeasuredLine> {
        val lines = mutableListOf<MeasuredLine>()
        val baseLineHeight = fontSize * 1.3f

        // 行状态收敛在一个稳定的可变对象里：flush 后 sink 内部自换新列表，
        // 后续追加自然落进新行。旧实现把行列表按值传进 appendTextToLine，
        // flush 后函数仍往已提交的旧列表追加——第一个换行点之后的文字全部
        // 挤进第一行并被页边裁掉（v0.0.59 折行修复）。
        val line = ComposingLine { elems ->
            val maxSpanHeight = elems.maxOfOrNull { it.height } ?: fontSize
            lines.add(MeasuredLine(MathLayout.lineHeightFor(baseLineHeight, maxSpanHeight, 2f), elems))
        }

        fun flushLine() {
            line.flushLine()
        }

        paint.textSize = fontSize

        tokens.forEach { token ->
            when (token) {
                is RichToken.MathToken -> {
                    val key = (if (token.display) "d:" else "i:") + token.latex
                    val rendered = mathMap[key]

                    if (token.display) {
                        // 独立块级公式：强制自成一行并居中
                        flushLine()
                        if (rendered != null && !rendered.bitmap.isRecycled) {
                            val fitted = MathLayout.fit(
                                bitmapW = rendered.bitmap.width,
                                bitmapH = rendered.bitmap.height,
                                srcFontPx = rendered.fontPx,
                                targetFontPx = fontSize * MathLayout.MATH_LETTER_RATIO,
                                maxWidthPx = maxWidth,
                                maxHeightPx = fontSize * MathLayout.DISPLAY_MATH_MAX_HEIGHT_RATIO
                            )
                            val element = MathElement(rendered.bitmap, fitted.width, fitted.height)
                            // 独立居中
                            val indent = ((maxWidth - fitted.width) / 2f).coerceAtLeast(0f)
                            if (indent > 0f) {
                                line.elements.add(TextElement("", indent, fitted.height, fontSize, textColor))
                            }
                            line.elements.add(element)
                            flushLine()
                        } else {
                            // 回退为源码居中文本
                            val fallbackText = "$$${token.latex}$$"
                            val textW = paint.measureText(fallbackText)
                            val indent = ((maxWidth - textW) / 2f).coerceAtLeast(0f)
                            if (indent > 0f) {
                                line.elements.add(TextElement("", indent, fontSize, fontSize, textColor))
                            }
                            line.elements.add(TextElement(fallbackText, textW, fontSize, fontSize, textColor))
                            flushLine()
                        }
                    } else {
                        // 行内公式
                        if (rendered != null && !rendered.bitmap.isRecycled) {
                            val fitted = MathLayout.fit(
                                bitmapW = rendered.bitmap.width,
                                bitmapH = rendered.bitmap.height,
                                srcFontPx = rendered.fontPx,
                                targetFontPx = fontSize * MathLayout.MATH_LETTER_RATIO,
                                maxWidthPx = maxWidth,
                                maxHeightPx = fontSize * MathLayout.INLINE_MATH_MAX_HEIGHT_RATIO
                            )
                            if (line.currentLineWidth + fitted.width > maxWidth && line.hasContent) {
                                flushLine()
                            }
                            line.elements.add(MathElement(rendered.bitmap, fitted.width, fitted.height))
                            line.currentLineWidth += fitted.width
                        } else {
                            // 回退为源码文本
                            val fallbackText = "$${token.latex}$"
                            val textW = paint.measureText(fallbackText)
                            if (line.currentLineWidth + textW > maxWidth && line.hasContent) {
                                flushLine()
                            }
                            line.elements.add(TextElement(fallbackText, textW, fontSize, fontSize, textColor))
                            line.currentLineWidth += textW
                        }
                    }
                }

                is RichToken.TextToken -> {
                    // 处理普通文字：按换行符和字/词切分
                    val text = token.text
                    var segmentStart = 0
                    var i = 0

                    while (i < text.length) {
                        val ch = text[i]
                        if (ch == '\n') {
                            // 遇到硬回车换行
                            val word = text.substring(segmentStart, i)
                            if (word.isNotEmpty()) {
                                PdfTextWrap.appendText(
                                    line, word, fontSize, textColor, maxWidth
                                ) { paint.measureText(it) }
                            }
                            flushLine()
                            segmentStart = i + 1
                        }
                        i++
                    }

                    if (segmentStart < text.length) {
                        PdfTextWrap.appendText(
                            line, text.substring(segmentStart), fontSize, textColor, maxWidth
                        ) { paint.measureText(it) }
                    }
                }
            }
        }

        flushLine()
        return lines
    }

    // =========================================================================
    // 页眉页脚与图片工具
    // =========================================================================

    private fun drawHeader(
        canvas: Canvas,
        textPaint: TextPaint,
        linePaint: Paint,
        title: String,
        dateStr: String
    ) {
        textPaint.textSize = FONT_HEADER_PAGE_PT
        textPaint.color = COLOR_TEXT_MUTED
        textPaint.isFakeBoldText = false

        // 左侧标题
        canvas.drawText(title, MARGIN_H_PT, 48f, textPaint)

        // 右侧日期
        val dateWidth = textPaint.measureText(dateStr)
        canvas.drawText(dateStr, MARGIN_H_PT + USABLE_WIDTH_PT - dateWidth, 48f, textPaint)

        // 顶部分割线
        linePaint.strokeWidth = 0.5f
        linePaint.color = COLOR_DIVIDER
        canvas.drawLine(MARGIN_H_PT, 56f, MARGIN_H_PT + USABLE_WIDTH_PT, 56f, linePaint)
    }

    private fun drawFooter(
        canvas: Canvas,
        textPaint: TextPaint,
        pageNumber: Int,
        totalPages: Int
    ) {
        textPaint.textSize = FONT_FOOTER_PAGE_PT
        textPaint.color = COLOR_TEXT_MUTED
        textPaint.isFakeBoldText = false

        val footerText = pageFooterTemplate.format(pageNumber, totalPages)
        val textWidth = textPaint.measureText(footerText)
        val centerX = MARGIN_H_PT + (USABLE_WIDTH_PT - textWidth) / 2f
        canvas.drawText(footerText, centerX, 806f, textPaint)
    }

    private fun decodeSampledBitmap(file: File, maxW: Float, maxH: Float): Pair<Bitmap, Boolean>? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            var inSampleSize = 1
            while ((origW / inSampleSize > maxW * 2) && (origH / inSampleSize > maxH * 2)) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            val rawBmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            // 等比缩放至目标包围盒
            var scale = 1f
            if (rawBmp.width > maxW) {
                scale = minOf(scale, maxW / rawBmp.width.toFloat())
            }
            if (rawBmp.height * scale > maxH) {
                scale = minOf(scale, maxH / (rawBmp.height.toFloat() * scale))
            }

            val targetW = (rawBmp.width * scale).toInt().coerceAtLeast(1)
            val targetH = (rawBmp.height * scale).toInt().coerceAtLeast(1)

            val scaledBmp = if (targetW != rawBmp.width || targetH != rawBmp.height) {
                val scaled = Bitmap.createScaledBitmap(rawBmp, targetW, targetH, true)
                rawBmp.recycle()
                scaled
            } else {
                rawBmp
            }
            scaledBmp to true
        }.getOrNull()
    }
}
