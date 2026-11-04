package com.mistakebook.print

import com.mistakebook.data.prefs.AppFont
import com.mistakebook.data.prefs.FontScale

import com.mistakebook.data.local.displayTitle
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.local.figurePaths
import com.mistakebook.data.local.knowledgePoints
import com.mistakebook.data.local.options
import com.mistakebook.data.local.printImagePath
import com.mistakebook.domain.Option
import java.io.File

/**
 * 导出：三种格式共用的**内容模型**。
 *
 * ## 为什么要抽这一层
 *
 * 早先只有 PDF 一条路，「要显示什么」直接写在 [PdfExporter] 的 `buildCard` 里。
 * 加上 HTML / DOCX 之后，如果各自再写一遍，就会出现三份「这道题要不要显示答案、
 * 标题行拼成什么样、公式切在哪」——而这正是本项目栽过多次的坑：
 * 同一个排版问题在多条渲染路径上各自演化，最后手机上对了、打印还是错的
 * （v0.1.12 教训）。所以**内容一份，版式各按格式**。
 *
 * 这里刻意**不含任何版式信息**（字号、行高、颜色、边距）：
 * 那部分是各格式自己的事，PDF 用 pt + Canvas，HTML 用 CSS，DOCX 用 XML 属性。
 */
/**
 * 导出格式。
 *
 * 把扩展名、MIME、显示名收在一处：**分享出去的 Intent 要 MIME，
 * 落盘的文件名要扩展名，界面要显示名**，三者必须一致——
 * 而它们分散在三个文件里时，迟早有人只改其中一处，
 * 结果是「分享出去的东西打不开」或者「文件没有后缀，系统认不出类型」。
 */
/**
 * 导出格式。
 *
 * ## 为什么只剩 HTML 一种
 *
 * 曾经有三种，逐个说清楚为什么砍掉：
 *
 * - **PDF**（HTML → WebView → 系统打印管线）：实测「PDF 写出失败或超时」，
 *   走的是 `PrintDocumentAdapter` 传 null 回调那条未文档化的路径，
 *   稳定性和可预期性都远不如 HTML。生成的 PDF 也不比「浏览器打印」更好。
 * - **DOCX**（手写 zip + OOXML + OMML）：用户实测「效果很差」。
 *   OMML 在 Google Docs 与部分手机端阅读器支持不好，
 *   而我们又不能为了兼容退回位图（那样 Word 里就是一堆图片、不能编辑）。
 *
 * **HTML 是唯一稳定的一种**：自包含（图片 base64 内嵌、公式原生 MathML）、
 * 浏览器渲染可靠、可以选中搜索、离线能看。
 *
 * 需要 PDF 时用**浏览器的打印功能**（`Ctrl+P` / 分享 → 打印 → 另存为 PDF）。
 * 浏览器打印的排版质量比 WebView 打印管线更好，而且页边距、缩放都能调。
 * 界面上有一行小字引导用户这么做。
 *
 * 所以枚举从三个值变一个，`PrintViewModel` 里的 `when` 也就只剩一个分支——
 * 之前那个「三格式共用同一份内容模型」的架构依然成立，只是退化成了单一格式。
 */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val labelRes: Int,

    /**
     * 短名，用于按钮与提示文案。
     *
     * 不能直接用 [labelRes]：那个带括号说明（如「HTML（推荐）」），
     * 塞进「已选 3 题 · 生成 X」里会长得把按钮拉开。
     */
    val shortLabelRes: Int
) {
    /** 原生 A4 PDF：系统 PdfDocument 生成，直接打印/分享，答题区留白纯净无横线。 */
    PDF(
        "pdf",
        "application/pdf",
        com.mistakebook.R.string.export_format_pdf,
        com.mistakebook.R.string.export_format_pdf_short
    ),

    /** 单文件自包含：图片走 base64 data URL，公式走原生 MathML。 */
    HTML(
        "html",
        "text/html",
        com.mistakebook.R.string.export_format_html,
        com.mistakebook.R.string.export_format_html_short
    );

    /** 导出用的文件名（不含扩展名）。 */
    fun fileName(stamp: String): String = "错题本_$stamp"
}

/** 导出选项。三个格式共用同一套开关。 */
data class ExportOptions(
    /** 是否带上原图 / 题目附图。 */
    val includeImage: Boolean = false,

    /** 是否显示答案与解析。 */
    val showAnswer: Boolean = false,

    /** 留白重做模式：在题目下方留出手写空间。 */
    val blankRedoMode: Boolean = true,

    /** 留白高度（pt）。 */
    val blankHeightPt: Int = 100,

    /**
     * 打印字号档（v0.0.99）。与屏幕上的字号**各存各的**：
     * 手上看着舒服的字号，和纸上给孩子练的字号，不是一回事。
     */
    val fontScale: FontScale = FontScale.DEFAULT,

    /** 打印字体族（无衬线 / 衬线 / 等宽），同样是打印专用的。 */
    val fontFamily: AppFont = AppFont.DEFAULT
)

/**
 * 内容的一段：普通文字或公式。
 *
 * 从 [PdfExporter] 移到这里——原来它是那个类的 private 内部类型，
 * 而 HTML / DOCX 需要同一套切分规则。**重写一遍切分器必然出现细微差异**，
 * 那会导致同一道题在 PDF 里公式正常、在 HTML 里显示成裸的 `$x$`。
 */
sealed interface RichToken {
    data class TextToken(val text: String) : RichToken
    data class MathToken(val latex: String, val display: Boolean) : RichToken
}

/** 按 `$...$` / `$$...$$` 切出公式，其余按「中文逐字 / 西文按词」切分，便于换行。 */
fun tokenizeWithMath(input: String): List<RichToken> {
    val out = mutableListOf<RichToken>()
    val buffer = StringBuilder()
    var i = 0

    fun flushText() {
        if (buffer.isEmpty()) return
        tokenizePlain(buffer.toString()).forEach { out += it }
        buffer.clear()
    }

    while (i < input.length) {
        val ch = input[i]
        if (ch == '$') {
            val display = input.startsWith("$$", i)
            val open = if (display) 2 else 1
            val close = if (display) "$$" else "$"
            val end = input.indexOf(close, i + open)
            if (end > i + open) {
                flushText()
                val body = input.substring(i + open, end).trim()
                if (body.isNotEmpty()) {
                    out += RichToken.MathToken(body, display)
                }
                i = end + close.length
                continue
            }
        }
        buffer.append(ch)
        i++
    }
    flushText()
    return out
}

private fun tokenizePlain(text: String): List<RichToken> {
    val out = mutableListOf<RichToken>()
    val word = StringBuilder()

    fun flushWord() {
        if (word.isNotEmpty()) {
            out += RichToken.TextToken(word.toString())
            word.clear()
        }
    }

    text.forEach { ch ->
        when {
            ch.isWhitespace() -> {
                flushWord()
                out += RichToken.TextToken(ch.toString())
            }
            // 中文与全角标点逐字断行；西文数字按词，避免把单词劈开
            ch.code >= 0x2E80 -> {
                flushWord()
                out += RichToken.TextToken(ch.toString())
            }

            else -> word.append(ch)
        }
    }
    flushWord()
    return out
}

/**
 * 剥掉 LaTeX，只留可读文字。
 *
 * 标题行和知识点里经常混着公式（`对数不等式 $\ln(1+t)$`、`$\int_0^1$`）。
 * 这些位置是**一行小字**，不渲染公式——直接把 `$\ln(1+t)$` 打出来，
 * 纸上是满行反斜杠。题目正文和解析里的公式才值得渲染。
 */
fun plainText(raw: String): String = buildString {
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '$') {
            val display = raw.startsWith("$$", i)
            val open = if (display) 2 else 1
            val close = if (display) "$$" else "$"
            val end = raw.indexOf(close, i + open)
            if (end > i + open) {
                i = end + close.length
                continue
            }
        }
        append(ch)
        i++
    }
}

/** 一道题在导出文档里的**内容**（不含版式）。 */
data class ExportCard(
    /** 清单里的连续序号，1 起。数据库 id 会跳号，用户靠它核对有没有漏题。 */
    val number: Int,
    val questionId: Long,

    /** 标题行：`1. 题目标题 · 学科 · 错因 · 难度 · 知识点`，已剥掉 LaTeX。 */
    val header: String,

    val subjectName: String?,
    val stem: List<RichToken>,
    val options: List<Option>,
    val answer: List<RichToken>,
    val analysis: List<RichToken>,
    val hasAnswer: Boolean,
    val hasAnalysis: Boolean,

    /** 打印用图：优先题目附图，没有才回退原图。为空表示没有可用图。 */
    val imagePath: String,
    val imageIsFigure: Boolean,

    val blankHeightPt: Int
)

/** 整份导出文档。 */
data class ExportDoc(
    val title: String,
    val generatedAt: Long,
    val cards: List<ExportCard>,
    val options: ExportOptions
) {
    /** 没有任何公式——可以走「纯文本快速通道」，省掉整个渲染环节。 */
    val hasMath: Boolean
        get() = cards.any { card ->
            listOf(card.stem, card.answer, card.analysis).any { list -> list.any { it is RichToken.MathToken } } ||
                card.options.any { option -> tokenizeWithMath(option.text).any { it is RichToken.MathToken } }
        }
}

/**
 * 导出结果。三种格式共用同一个返回类型——
 * 上层调度才能写成一句 `when (format)`，而不是三套分支。
 */
data class ExportResult(
    val file: File,

    /** PDF 才有意义；HTML/DOCX 是流式文档，没有页的概念，恒为 0。 */
    val pageCount: Int = 0,

    /**
     * 排版失败被跳过的题目。
     *
     * **单题坏掉不能拖垮整批**——一份 30 题的文档因为第 17 题的公式渲染失败而
     * 完全导不出来，用户只会觉得「这软件不行」。所以跳过并在结果里列出来。
     */
    val skipped: List<String> = emptyList()
)

/**
 * 把 [Question] 转成 [ExportCard]。
 *
 * **「显示什么」的唯一判据。** 三个格式都从这里取内容，
 * 不允许各自再读一遍 [Question] 决定要不要显示答案——那是漂移的开始。
 */
class ExportCardBuilder(private val subjectNames: Map<Long, String>) {

    fun build(question: Question, options: ExportOptions, index: Int): ExportCard {
        val subjectName = question.subjectId?.let { subjectNames[it] }

        // 题头**只留顺序题号**。
        //
        // 原来还带标题、学科、错因、难度星、知识点，一行到尾。
        // 用户实测认为「标注过多不够简洁」——纸上大量空白被这一行吃掉，
        // 而这些信息在应用里都能看到。三种格式统一取这个判据。
        val header = "$index."

        val imagePath = question.printImagePath
        return ExportCard(
            number = index,
            questionId = question.id,
            header = header,
            subjectName = subjectName,
            stem = tokenizeWithMath(question.stem),
            options = question.options,
            answer = tokenizeWithMath(question.answer),
            analysis = tokenizeWithMath(question.analysis),
            hasAnswer = options.showAnswer && question.answer.isNotBlank(),
            hasAnalysis = options.showAnswer && question.analysis.isNotBlank(),
            imagePath = imagePath,
            imageIsFigure = question.figurePaths.isNotEmpty(),
            blankHeightPt = options.blankHeightPt
        )
    }

    fun buildDoc(
        questions: List<Question>,
        options: ExportOptions,
        title: String,
        generatedAt: Long
    ): ExportDoc = ExportDoc(
        title = title,
        generatedAt = generatedAt,
        cards = questions.mapIndexed { index, question -> build(question, options, index + 1) },
        options = options
    )
}
