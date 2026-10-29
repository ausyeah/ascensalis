package com.mistakebook.print

import android.graphics.BitmapFactory
import android.util.Base64
import com.mistakebook.math.MathRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HTML 导出。**单个自包含文件**，双击就能在任何浏览器打开。
 *
 * ## 三件事决定了它必须自包含
 *
 * 1. 用户可能**离线**打开——这是错题本的典型用法（打印出来在飞机上做）。
 * 2. 要**发给同学/老师**——发一个附件比发一个网址靠谱，而带 JS 的东西在
 *    微信里会被拦。
 * 3. 图片与公式不能是**外部引用**——文件一换目录就全裂。
 *
 * 所以：图片走 base64 data URL 内联，公式走 MathML（浏览器原生渲染，
 * 无需 JS），**整份文件不依赖任何外部资源**。
 *
 * ## 公式为什么是 MathML 而不是位图
 *
 * 位图方案的两个问题在这里都很致命：文件大几十倍；公式里的字**搜不到、复制不出来**。
 * MathML 是文字，KaTeX 直接能生成（`output: 'mathml'`），
 * 且 Chrome 109+ / Safari / Firefox 都原生支持。
 * 拿不到 MathML 时**逐个公式**回退到位图（`title` 里保留 LaTeX 原文），
 * 而不是让整份文档失败。
 */
class HtmlExporter(private val mathRenderer: MathRenderer) {

    /**
     * 公式的渲染方式。
     *
     * - [MATHML]：原生 MathML。文字、可搜索、无 JS、离线可看——**分享出去的 HTML 用它**。
     * - [KATEX]：走 assets 里的 KaTeX。需要在 WebView 里打开，**PDF 路径用它**——
     *   不知道设备的 WebView 版本支不支持 MathML（MathML Core 要 Chrome 109+），
     *   而 KaTeX 在 [com.mistakebook.math.MathRenderer] 里已经用了好久。
     */
    enum class FormulaMode { MATHML, KATEX }

    /** 只生成 HTML，不写文件。[HtmlPdfExporter] 直接复用这一份。 */
    suspend fun buildHtml(doc: ExportDoc, mode: FormulaMode = FormulaMode.MATHML): HtmlDocument {
        val skipped = mutableListOf<String>()
        val body = buildString {
                append(DOCTYPE)
                append("<html lang=\"zh-CN\"><head>")
                append("<meta charset=\"utf-8\">")
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                append("<title>").append(escape(doc.title)).append("</title>")
                if (mode == FormulaMode.KATEX) {
                    append("""<link rel="stylesheet" href="katex.min.css">""")
                    append("""<script src="katex.min.js"></script>""")
                }
                append("<style>").append(CSS).append("</style>")
                append("</head><body>")
                append("<h1>").append(escape(doc.title)).append("</h1>")
                append("<p class=\"meta\">共 ").append(doc.cards.size)
                append(" 道题 · 导出于 ").append(formatTime(doc.generatedAt)).append("</p>")
                // 打印提示只在**屏幕**上显示，不进纸。
                //
                // 打印时 @media print 会把元素藏起来——用户要的是干净的纸，
                // 而这行提示对他没用了（他正在打印，说明已经会用浏览器打印了）。
                append("<p class=\"print-hint\">需要 PDF？用浏览器的打印功能（菜单里选「打印」）即可。</p>")

                doc.cards.forEach { card ->
                    try {
                        append(renderCard(card, doc, skipped, mode))
                    } catch (error: Throwable) {
                        // 单题坏掉不拖垮整批
                        android.util.Log.e("HtmlExporter", "题目 ${card.questionId} 渲染失败", error)
                        skipped += card.header.take(40)
                    }
                }
                if (mode == FormulaMode.KATEX) {
                    append(KATEX_BOOTSTRAP)
                }
                append("</body></html>")
            }
        return HtmlDocument(html = body, skipped = skipped)
    }

    /** 写文件。分享用的 HTML 走这里，用原生 MathML（自包含、无 JS、离线可看）。 */
    suspend fun export(doc: ExportDoc, target: File): ExportResult = withContext(Dispatchers.IO) {
        val built = buildHtml(doc, FormulaMode.MATHML)
        target.parentFile?.mkdirs()
        target.writeText(built.html, Charsets.UTF_8)
        ExportResult(file = target, skipped = built.skipped)
    }

    /**
     * 一次 HTML 构建的产物。
     *
     * 拆出来而不是让 `buildHtml` 直接写文件，是因为 PDF 路径要拿到 HTML 喂给 WebView，
     * 同时**也要拿到「哪些题渲染失败了」**——两条路径共用一次渲染结果。
     *
     * 早先的写法是把 `skipped` 存成实例字段再从 `export` 读回来：
     * 编译器不报错，但并发导出两个任务会互相覆盖，且 `skipped` 属于哪次调用
     * 完全取决于调用顺序。这种「用实例状态当返回值」在重构时最容易漏掉调用点。
     */
    data class HtmlDocument(val html: String, val skipped: List<String>)

    private suspend fun renderCard(
        card: ExportCard,
        doc: ExportDoc,
        skipped: MutableList<String>,
        mode: FormulaMode
    ): String =
        buildString {
            append("<section class=\"card\"")
            card.subjectName?.let { append(" data-subject=\"").append(escape(it)).append("\"") }
            append(">")
            append("<h2>").append(escape(card.header)).append("</h2>")

            if (card.stem.any { it is RichToken.TextToken }) {
                append("<div class=\"stem\">")
                append(renderTokens(card.stem, mode))
                append("</div>")
            }

            // 附图优先，打原图兜底——与 PDF 那条路保持一致
            if (doc.options.includeImage && card.imagePath.isNotBlank()) {
                val dataUrl = readImageAsDataUrl(card.imagePath)
                if (dataUrl != null) {
                    append("<figure><img src=\"").append(dataUrl).append("\" alt=\"")
                    append(escape(if (card.imageIsFigure) "题目附图" else "原题照片"))
                    append("\"><figcaption>")
                    append(if (card.imageIsFigure) "题目附图" else "原题照片")
                    append("</figcaption></figure>")
                }
            }

            if (card.options.isNotEmpty()) {
                append("<ol class=\"options\" type=\"A\">")
                card.options.forEach { option ->
                    append("<li>").append(renderTokens(tokenizeWithMath(option.text), mode)).append("</li>")
                }
                append("</ol>")
            }

            if (card.hasAnswer) {
                append("<div class=\"answer\"><span class=\"tag\">答案</span>")
                append(renderTokens(card.answer, mode))
                append("</div>")
            }
            if (card.hasAnalysis) {
                append("<div class=\"analysis\"><span class=\"tag\">解析</span>")
                append(renderTokens(card.analysis, mode))
                append("</div>")
            }

            // 留白重做区。HTML 里没有「纸」，但留一块空白在手写时（打印或屏幕）都有用。
            if (doc.options.blankRedoMode) {
                append("<div class=\"blank\" style=\"height:")
                    .append((card.blankHeightPt / 3f).coerceAtLeast(40f))
                    .append("pt\"></div>")
            }
            append("</section>")
        }

    /** token 序列 -> HTML。文本转义，公式走 MathML。 */
    private suspend fun renderTokens(tokens: List<RichToken>, mode: FormulaMode): String = buildString {
        tokens.forEach { token ->
            when (token) {
                is RichToken.TextToken -> append(escape(token.text))
                is RichToken.MathToken -> append(renderFormula(token, mode))
            }
        }
    }

    private suspend fun renderFormula(token: RichToken.MathToken, mode: FormulaMode): String {
        if (mode == FormulaMode.KATEX) {
            // 留给页面上的 KaTeX 脚本渲染。data-tex 逃义后就是原始 LaTeX。
            val display = if (token.display) "1" else "0"
            return """<span class="tex" data-tex="${escape(token.latex)}" data-display="$display"></span>"""
        }
        val ml = mathRenderer.toMathMl(token.latex, token.display)
        if (ml != null) {
            // MathML 自带语义，浏览器直接渲染；alt/title 里留 LaTeX 原文兜底
            val wrapper = if (token.display) "math display=\"block\"" else "math"
            return "<$wrapper title=\"${escape(token.latex)}\">$ml</$wrapper>"
        }
        // 回退：公式位图 + LaTeX 原文写在 alt 里，至少还能搜到
        val bitmap = mathRenderer.render(token.latex, token.display)
        val dataUrl = bitmap?.let { renderBitmapAsDataUrl(it.bitmap) }
        val latexText = escape(token.latex)
        return if (dataUrl != null) {
            val cls = if (token.display) "formula block" else "formula"
            "<img class=\"$cls\" src=\"$dataUrl\" alt=\"$latexText\">"
        } else {
            "<code class=\"latex\">$latexText</code>"
        }
    }

    private fun readImageAsDataUrl(path: String): String? = runCatching {
        val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: return null
        // 原图可能是好几 MB，内联进 HTML 会让文件巨大。
        // 超过上限就跳过——用户可以在选项里关掉「含原图」，而一个几百 MB 的
        // HTML 分享出去是打不开的。
        if (bytes.size > MAX_INLINE_IMAGE_BYTES) {
            android.util.Log.w("HtmlExporter", "图片过大，跳过内联: $path (${bytes.size})")
            return null
        }
        "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }.getOrNull()

    private fun renderBitmapAsDataUrl(bitmap: android.graphics.Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        return "data:image/png;base64,${Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)}"
    }

    /** HTML 转义。题干里出现 `<` `&` 是常事（不等号、LaTeX 残留），不转义会破版。 */
    private fun escape(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private fun formatTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private companion object {
        const val DOCTYPE = "<!DOCTYPE html>"

        /**
         * KaTeX 模式的页面脚本。
         *
         * 只把 `<span class="tex">` 里的 LaTeX 渲染成 KaTeX，不做其他事。
         * 公式宽度由 KaTeX 自己量，而行高由内联 CSS 控制——
         * 两者分工才不会出现「公式很大纸上小」这类不协调。
         */
        val KATEX_BOOTSTRAP = """
<script>
  document.querySelectorAll('span.tex').forEach(function (el) {
    var latex = el.getAttribute('data-tex') || '';
    var display = el.getAttribute('data-display') === '1';
    try {
      katex.render(latex, el, {
        displayMode: display,
        throwOnError: false,
        strict: false,
        output: 'html'
      });
    } catch (e) {
      el.textContent = latex;
    }
  });
</script>
        """.trimIndent()

        /** 单张内联图片上限 2MB。 */
        const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024

        /**
         * 打印友好是硬要求：错题本导出来多半是要打印的，
         * 所以默认隐藏答案解析栏由 CSS 控制，且分页时不把一道题切成两页。
         */
        val CSS = """
            /* 打印提示：只在屏幕上出现，打印时藏起来 */
            .print-hint {
              color: #888;
              font-size: 12px;
              margin: 0 0 16px;
            }

            @page { size: A4; margin: 12mm 12mm; }
            * { box-sizing: border-box; }
            body {
              font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Source Han Sans SC", sans-serif;
              /* v0.0.57：用户反馈打印出来「整体太大、一页装不下几道题」。
                 统一把字号/行高/间距压一档，仍是同一套 HTML→PDF（WYSIWYG）。 */
              font-size: 13px; line-height: 1.5; color: #1a1a1a; margin: 0; padding: 14px;
              max-width: 720px;
            }
            h1 { font-size: 18px; margin: 0 0 3px; }
            .meta { color: #888; font-size: 11px; margin: 0 0 14px; }
            .card {
              border: 1px solid #e6e6e6; border-left: 4px solid #d0d0d0;
              border-radius: 6px; padding: 10px 12px; margin: 0 0 12px;
              page-break-inside: avoid; break-inside: avoid;
            }
            h2 { font-size: 13px; font-weight: 600; margin: 0 0 7px; color: #333; }
            .stem { font-size: 13px; }
            .options { padding-left: 20px; margin: 6px 0; }
            .options li { margin: 2px 0; }
            .answer, .analysis {
              margin-top: 8px; padding-top: 6px; border-top: 1px dashed #e0e0e0;
              font-size: 12.5px; color: #444;
            }
            .tag {
              display: inline-block; font-size: 10px; color: #fff; background: #8a8a8a;
              border-radius: 3px; padding: 1px 6px; margin-right: 6px; vertical-align: 1px;
            }
            figure { margin: 8px 0; text-align: center; }
            figure img { max-width: 50%; }
            figcaption { font-size: 10.5px; color: #999; margin-top: 3px; }
            .blank { margin-top: 8px; border-bottom: 1px dashed #ddd; }
            img.formula { vertical-align: middle; margin: 0 2px; }
            img.formula.block { display: block; margin: 6px auto; }
            code.latex {
              font-family: "SFMono-Regular", Consolas, monospace; font-size: 11px;
              background: #f6f6f6; padding: 1px 4px; border-radius: 3px;
            }
            math { font-size: 1em; }
            math[display="block"] { margin: 6px 0; }
            /* 打印时用 KaTeX 的展开样式：行内公式不能换行，块公式中央对齐 */
            .katex { font-size: 1em; }
            .katex-display { margin: 8px 0; text-align: center; }
            .katex-display > .katex { text-align: center; }
            span.tex { white-space: nowrap; }
            @media print {
              body { padding: 0; max-width: none; }
              .card { border-color: #ddd; }
              /* 屏幕上的操作提示不进纸 */
              .print-hint { display: none; }
            }
        """.trimIndent()
    }
}