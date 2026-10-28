package com.mistakebook.markdown

import androidx.collection.LruCache

/**
 * 轻量 Markdown / MinerU 输出解析：标题、段落、列表、引用、分割线、代码块、
 * HTML 表格与 Markdown 管道表格、图片、行内与独立公式。
 */
object MarkdownParser {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class ListItem(val marker: String, val text: String) : Block
        data class Quote(val text: String) : Block
        data class Table(val rows: List<List<String>>) : Block
        data class Image(val path: String, val alt: String) : Block
        data class Math(val latex: String, val display: Boolean, val complete: Boolean = true) : Block
        /** 围栏代码块的**语言标记**（```svg / ```python / 无标记都是空串）。 */
        data class Code(val text: String, val language: String = "") : Block

        /**
         * ```svg 围栏：不走等宽文本渲染，走 [com.mistakebook.ui.common.SvgBlock]。
         *
         * [source] 已经是**清洗过**的 SVG（去脚本 / 外链等），
         * 渲染层可以放心交给解码器。
         */
        data class Svg(val source: String) : Block
        data object Rule : Block
    }

    private val tableTag = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val rowTag = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val cellTag = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val htmlTag = Regex("<[^>]+>")

    fun parse(input: String): List<Block> {
        if (input.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        // 先把 HTML 表格抠出来单独成块，其余按行处理
        val segments = splitByTables(input)
        segments.forEach { segment ->
            if (segment.startsWith("\u0000TABLE\u0000")) {
                parseHtmlTable(segment.removePrefix("\u0000TABLE\u0000"))?.let { blocks += it }
            } else {
                parsePlain(segment.trimEnd(), blocks)
            }
        }
        return blocks
    }

    /**
     * 带缓存的 [parse]。
     *
     * ## 解决什么
     *
     * LazyColumn 滚动时，新进入视口的 item 会在主线程跑整段解析
     * （逐行正则 + 块结构），离屏 item 被销毁后滑回来还要再解一遍——
     * 用户看到的现象就是「对话之间滑动时列表抽风、一闪一闪、滑不下去」。
     *
     * 解析是**纯函数**（同输入同输出、无副作用），结果 Block 也只读，
     * 所以缓存整个列表是安全的。流式期间每条新文本 miss 一次、付一次解析，
     * 历史消息滑出再滑回则直接命中，主线程不再反复被解析堵住。
     *
     * 上限 [PARSE_CACHE_ENTRIES] 条：一个会话页可见 + 邻近的消息远小于此，
     * 流式增长由 LRU 自然淘汰最早的半成品。
     */
    fun parseCached(input: String): List<Block> {
        if (input.isBlank()) return emptyList()
        parseCache[input]?.let { return it }
        val parsed = parse(input)
        parseCache.put(input, parsed)
        return parsed
    }

    /** 解析缓存条数。见 [parseCached]。 */
    private const val PARSE_CACHE_ENTRIES = 128

    /**
     * 未闭合 SVG 的产出上限（字符数）。超过它的未闭合 SVG 在流式期间先不产出图形块，
     * 见 [unclosedSvgBlockOrNull]。设得够大（32KB）以保证正常示意图不受影响。
     */
    private const val UNCLOSED_SVG_EMIT_LIMIT = 32 * 1024

    private val parseCache = LruCache<String, List<Block>>(PARSE_CACHE_ENTRIES)

    private fun splitByTables(input: String): List<String> {
        val result = mutableListOf<String>()
        var last = 0
        tableTag.findAll(input).forEach { match ->
            if (match.range.first > last) {
                result += input.substring(last, match.range.first)
            }
            result += "\u0000TABLE\u0000" + match.groupValues[1]
            last = match.range.last + 1
        }
        if (last < input.length) result += input.substring(last)
        return result
    }

    private fun parseHtmlTable(html: String): Block.Table? {
        val rows = rowTag.findAll(html).map { rowMatch ->
            cellTag.findAll(rowMatch.groupValues[1])
                .map { cell -> cleanCell(cell.groupValues[1]) }
                .toList()
        }.filter { it.isNotEmpty() }.toList()
        return if (rows.isEmpty()) null else Block.Table(rows)
    }

    private fun cleanCell(raw: String): String =
        htmlTag.replace(raw, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()

    private fun parsePlain(text: String, blocks: MutableList<Block>) {
        if (text.isBlank()) return
        val lines = text.split('\n')
        val paragraph = StringBuilder()
        var inCode = false
        val code = StringBuilder()
        /** 当前围栏的语言标记（```svg → "svg"）；无标记为空串。 */
        var codeLanguage = ""

        // 跨行 `$$` 公式的状态。
        //
        // 早先这里只有「`$$` 与内容同一行」这一种形状：`$$x=1$$`。
        // 遇到独占一行的 `$$` 时算出空 latex，然后**什么都不加**——
        // 整块推导被静默丢弃，中间那行掉进段落分支以裸 LaTeX 显示。
        //
        // 为什么题目区没事、气泡有事：识别链路的提示词禁止在公式内换行，
        // 所以它给过来的 `$$` 永远是单行；聊天的提示词只说「独立成行用 $$...$$」，
        // 而 LLM 输出 `$$\n\begin{aligned}...\n\end{aligned}\n$$` 是常规写法，必然踩中。
        var displayMath: StringBuilder? = null

        // 跨行 `\[ ... \]` 的状态。
        //
        // 早先只支持「`\[` 与 `\]` 同一行」；多行 display 公式（模型写
        // `\[\n\begin{aligned}...\n\end{aligned}\n\]` 是常规写法）会掉进
        // else 分支按正文走，被拆成好几个 paragraph——块级公式的
        // `\left{`、`&` 于是漏进普通文本，整块渲染不出来。
        var bracketMath: StringBuilder? = null

        // 跨行裸 `<svg> ... </svg>` 的状态。
        //
        // ```svg 围栏的约定只写在**错题识别**的提示词里（PromptTemplates），
        // 聊天系统提示词没有教——而模型在对话正文里画图时经常直接输出
        // `<svg>` 标签，不走任何围栏。只认围栏的话这段 XML 会掉进段落
        // 分支，以源码形式躺在气泡里：用户看到的正是「思考里的 SVG 能
        // 渲染（恰好带了围栏），正式回答里全是代码（裸标签）」。
        // 这里兜底：行首 `<svg` 进入跨行收集，到 `</svg>` 为止整体出图形块，
        // 与思考/正文共用同一条 RichText 渲染路径，两侧行为一致。
        var rawSvg: StringBuilder? = null

        /**
         * 裸 LaTeX 救援（v0.0.51 用例 B1）：整段输出 LaTeX 却没有 `$$` 包裹。
         *
         * 判定交给 [com.mistakebook.pipeline.LatexSanitizer.rescueUndelimited]，
         * 只在「独立成段」的文本上调用——段内混有正文的由启发式的
         * 中文/标点反例拦下。救援失败（null）按原样走普通段落。
         * 所有 flush 点统一走这里，消息中部的裸公式段也能救到。
         */
        fun flushParagraphWithRescue() {
            if (paragraph.isNotBlank()) {
                val raw = paragraph.toString().trim()
                val rescued = runCatching {
                    com.mistakebook.pipeline.LatexSanitizer.rescueUndelimited(raw)
                }.getOrNull()
                if (rescued != null) {
                    val latex = rescued.trim().removePrefix("$$").removeSuffix("$$").trim()
                    blocks += Block.Math(latex, true)
                    paragraph.clear()
                    return
                }
                blocks += paragraphBlock(raw)
            }
            paragraph.clear()
        }

        // 用**索引循环**而不是 forEach：管道表格必须能「吃掉」自己那几行，
        // 否则外层循环会把表格的每一行都重新当一次表头再解析一遍，
        // 同一张表被输出 N 次（用户截图：表格内容连续重复 3 次）。
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val rawLine = lines[lineIndex]
            val line = rawLine.trimEnd()
            val openMath = displayMath
            val openBracket = bracketMath

            // 本轮结束后要接着处理的行号。管道表格会把它**吃掉**的那些行
            // 一并返回（见 [parsePipeTable]），其余分支都是一行一行推进。
            var nextLineIndex = lineIndex + 1

            when {
                // 正在 `\[ ... \]` 公式块里：找闭合的 `\]`
                openBracket != null -> {
                    val closeAt = line.indexOf("\\]")
                    if (closeAt < 0) {
                        openBracket.appendLine(line)
                    } else {
                        openBracket.append(line.substring(0, closeAt))
                        val latex = openBracket.toString().trim()
                        if (latex.isNotEmpty()) blocks += Block.Math(latex, true)
                        bracketMath = null
                        val rest = line.substring(closeAt + 2)
                        if (rest.isNotBlank()) paragraph.append(rest)
                    }
                }

                // 正在公式块里：先找闭合的 `$$`，找到就把剩下半行也收进来
                openMath != null -> {
                    val closeAt = line.indexOf("$$")
                    if (closeAt < 0) {
                        openMath.appendLine(line)
                    } else {
                        openMath.append(line.substring(0, closeAt))
                        val latex = openMath.toString().trim()
                        if (latex.isNotEmpty()) blocks += Block.Math(latex, true)
                        displayMath = null
                        // 闭合 `$$` 之后同一行剩下的内容是正文，不能丢
                        val rest = line.substring(closeAt + 2)
                        if (rest.isNotBlank()) paragraph.append(rest)
                    }
                }

                // 正在裸 SVG 块里：找闭合的 `</svg>`（同一行闭合也认）。
                // 放在围栏分支之前：SVG 内容里出现 ``` 不应该截断图形。
                rawSvg != null -> {
                    val closeAt = line.indexOf("</svg>", ignoreCase = true)
                    if (closeAt < 0) {
                        rawSvg.appendLine(line)
                    } else {
                        rawSvg.append(line.substring(0, closeAt + "</svg>".length))
                        val source = rawSvg.toString().trim()
                        if (source.isNotEmpty()) blocks += Block.Svg(sanitizeSvg(source))
                        rawSvg = null
                        // 闭合之后同一行剩下的内容是正文，不能丢
                        val rest = line.substring(closeAt + "</svg>".length)
                        if (rest.isNotBlank()) paragraph.append(rest)
                    }
                }

                line.trimStart().startsWith("```") -> {
                    if (inCode) {
                        // 闭合：按语言分流。svg 走图形块，其余（含无标记）
                        // 走等宽代码块——语言标记一并带出去，渲染层可据此
                        // 决定要不要显示语言标签。
                        val text = code.toString().trimEnd()
                        blocks += if (isSvgFence(codeLanguage, text)) {
                            Block.Svg(sanitizeSvg(text))
                        } else {
                            Block.Code(text, codeLanguage)
                        }
                        code.clear()
                        codeLanguage = ""
                        inCode = false
                    } else {
                        flushParagraphWithRescue()
                        // 开围栏那行的剩余内容就是语言标记：
                        // ```svg / ```python / ```（空）
                        codeLanguage = line.trimStart()
                            .removePrefix("```")
                            .trim()
                            // 有些模型会写成 ```svg title="xx"，只取第一个词
                            .substringBefore(' ')
                            .substringBefore('\t')
                        inCode = true
                    }
                }

                inCode -> code.appendLine(line)

                // 行首裸 `<svg`：进入跨行收集（见 [rawSvg] 声明处的说明）。
                // 必须排在 `inCode` 之后：围栏代码块里的 `<svg` 是代码内容，不能拦。
                line.trimStart().startsWith("<svg", ignoreCase = true) -> {
                    flushParagraphWithRescue()
                    val body = line.trimStart()
                    val closeAt = body.indexOf("</svg>", ignoreCase = true)
                    if (closeAt >= 0) {
                        val source = body.substring(0, closeAt + "</svg>".length)
                        blocks += Block.Svg(sanitizeSvg(source))
                        val rest = body.substring(closeAt + "</svg>".length)
                        if (rest.isNotBlank()) paragraph.append(rest)
                    } else {
                        rawSvg = StringBuilder(body)
                    }
                }

                line.isBlank() -> flushParagraphWithRescue()

                // 独占一行的 `$$`：进入公式块，攒到下一个独占的 `$$` 为止
                line.trim() == "\$\$" -> {
                    flushParagraphWithRescue()
                    displayMath = StringBuilder()
                }

                line.trimStart().startsWith("$$") -> {
                    flushParagraphWithRescue()
                    val closeAt = line.indexOf("$$", 2)
                    if (closeAt > 2) {
                        val latex = line.trim().removePrefix("$$").substring(0, closeAt - 2).trim()
                        if (latex.isNotEmpty()) blocks += Block.Math(latex, true)
                        val rest = line.substring(closeAt + 2).trim()
                        if (rest.isNotBlank()) paragraph.append(rest)
                    } else {
                        displayMath = StringBuilder(line.trim().removePrefix("$$"))
                    }
                }

                // 单行 `\[ ... \]`：LaTeX display 定界符。与 `$$` 同权——
                // 模型（尤其 DeepSeek/o 系）输出独立公式时发 `\[..\]` 是常态，
                // 只认 `$$` 会让整块公式以源码形式躺在段落里。
                line.trimStart().startsWith("\\[") -> {
                    flushParagraphWithRescue()
                    val closeAt = line.indexOf("\\]", 2)
                    if (closeAt > 2) {
                        val latex = line.trim().substring(2, closeAt).trim()
                        if (latex.isNotEmpty()) blocks += Block.Math(latex, true)
                        val rest = line.substring(closeAt + 2).trim()
                        if (rest.isNotBlank()) paragraph.append(rest)
                    } else {
                        // 未闭合：进入跨行状态机，攒到下一个 `\]` 为止。
                        // 多行 display 公式（`\[\n\begin{aligned}…\n\]`）是常规写法，
                        // 早先这里「落进 else 按正文走」，结果整块公式被拆散成
                        // 若干 paragraph，块级结构（\left{、&）漏进普通文本，
                        // 整块渲染不出来。
                        bracketMath = StringBuilder(line.trim().removePrefix("\\["))
                    }
                }

                line.matches(RULE_REGEX) -> {
                    flushParagraphWithRescue()
                    blocks += Block.Rule
                }

                line.trimStart().startsWith("#") -> {
                    flushParagraphWithRescue()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += Block.Heading(level, line.trimStart().drop(level).trim())
                }

                line.trimStart().startsWith(">") -> {
                    flushParagraphWithRescue()
                    blocks += Block.Quote(line.trimStart().removePrefix(">").trim())
                }

                isPipeTableRow(line) -> {
                    flushParagraphWithRescue()
                    // 表格必须**吃掉**自己的所有行：否则外层循环会把表格的
                    // 每一行都重新当一次表头再解析一遍，同一张表被输出 N 次。
                    nextLineIndex = parsePipeTable(lines, lineIndex, blocks)
                }

                isImageLine(line) -> {
                    flushParagraphWithRescue()
                    val match = IMAGE_LINE_REGEX.find(line)
                    if (match != null) {
                        blocks += Block.Image(match.groupValues[2].trim(), match.groupValues[1])
                    }
                }

                isListItem(line) -> {
                    flushParagraphWithRescue()
                    val marker = line.trimStart().takeWhile { !it.isWhitespace() }
                    blocks += Block.ListItem(
                        marker = marker,
                        text = line.trimStart().drop(marker.length).trim()
                    )
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
            lineIndex = nextLineIndex
        }
        flushParagraphWithRescue()
        // 没闭合的 `$$`：照样产出块。
        //
        // 流式输出时正文尾端正停在 `$$x = ` 这种半截状态，之后每个新 chunk
        // 都会重跑整个解析。如果这里把没闭合的丢弃，那条公式在生成期间
        // 永远不显示，生成结束才突然冒出来——而如果模型真没写闭合符，
        // 它就永远不显示。两种都不可接受：有内容就渲染。
        displayMath?.let { open ->
            val latex = open.toString().trim()
            if (latex.isNotEmpty()) blocks += Block.Math(latex, display = true, complete = false)
        }
        // 同理：未闭合的 `\[ …`（流式尾端 / 模型漏写闭合符）。
        bracketMath?.let { open ->
            val latex = open.toString().trim()
            if (latex.isNotEmpty()) blocks += Block.Math(latex, display = true, complete = false)
        }
        // 同上：流式渲染期间围栏可能还没闭合，有内容就先渲染。
        // SVG 围栏未闭合且超长时走降级（见 [unclosedSvgBlockOrNull]）：先不产出，
        // 避免每个 chunk 都对全量前缀重跑清洗（O(n²)）；闭合后整段重解析自然恢复。
        if (inCode && code.isNotBlank()) {
            val text = code.toString().trimEnd()
            if (isSvgFence(codeLanguage, text)) {
                unclosedSvgBlockOrNull(text)?.let { blocks += it }
            } else {
                blocks += Block.Code(text, codeLanguage)
            }
        }
        // 同上：流式尾端 / 模型漏写闭合的裸 `<svg …`。同样走 [unclosedSvgBlockOrNull]，
        // 小 SVG 有内容就出块，超长未闭合先降级占位、闭合后恢复。
        rawSvg?.let { open ->
            unclosedSvgBlockOrNull(open.toString())?.let { blocks += it }
        }
    }

    /**
     * 围栏内容是否应按 SVG 图形块渲染。
     *
     * 三种形态都要认（模型输出不可控）：
     * - 语言标记就是 `svg`（提示词约定的正路）；
     * - 语言标记为 `xml` / `html` 或干脆为空，但内容以 `<svg` 开头——
     *   模型「知道」自己写的是 SVG，只是语言标记随手写；
     * - 其余（python、js 等）无论内容是什么都按代码块走，
     *   避免把讲 SVG 的示例代码误渲染成图。
     */
    private fun isSvgFence(language: String, content: String): Boolean {
        if (language.equals("svg", ignoreCase = true)) return true
        if (!content.trimStart().startsWith("<svg", ignoreCase = true)) return false
        return language.isBlank() ||
            language.equals("xml", ignoreCase = true) ||
            language.equals("html", ignoreCase = true)
    }

    /**
     * 流式期间**未闭合** SVG 的产出策略（v0.0.54 后续优化 · Task B）。
     *
     * 未闭合的 SVG（围栏没关 / 裸 `<svg>` 没闭合）在流式里每来一个 chunk 都会被
     * 整段重清洗一次；长 SVG + 高频 token 下，这是 O(n²) 的主线程正则开销
     * （每个 chunk 都对不断变长的历史前缀重跑 [sanitizeSvg]）。
     *
     * 策略：
     * - 空 → 不产出；
     * - 长度 ≤ [UNCLOSED_SVG_EMIT_LIMIT] → 照旧清洗后产出（有内容就先渲染，行为不变）；
     * - 超长 → **先不产出**（返回 null，界面留空占位），等 `</svg>` / 围栏闭合后
     *   走闭合路径整段清洗产出，自然恢复。
     *
     * 这样把未闭合阶段每 chunk 的开销从 O(全长) 压到 O(1)（超长）或 O(上限)（小图），
     * 配合 [SvgSanitizer] 的结果缓存堵住闭合后的重复清洗，两端一起消除 O(n²)。
     *
     * 安全底线不变：不产出 = 没有任何未清洗 / 半截的危险内容进入渲染层；
     * 一旦产出，必是过了 [sanitizeSvg] 全量清洗的内容。
     */
    private fun unclosedSvgBlockOrNull(source: String): Block.Svg? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > UNCLOSED_SVG_EMIT_LIMIT) return null
        return Block.Svg(sanitizeSvg(trimmed))
    }

    private fun paragraphBlock(text: String): Block {
        // 整段只是一个公式时按独立公式渲染
        if (text.startsWith("$$") && text.endsWith("$$") && text.length > 4) {
            return Block.Math(text.removePrefix("$$").removeSuffix("$$").trim(), true)
        }
        if (text.startsWith("$") && text.endsWith("$") && text.length > 2 &&
            text.count { it == '$' } == 2
        ) {
            return Block.Math(text.removePrefix("$").removeSuffix("$").trim(), false)
        }
        // LaTeX 标准定界符的整段形态：`\[ ... \]` display、`\( ... \)` inline
        if (text.startsWith("\\[") && text.endsWith("\\]") && text.length > 4) {
            return Block.Math(text.substring(2, text.length - 2).trim(), true)
        }
        if (text.startsWith("\\(") && text.endsWith("\\)") && text.length > 4) {
            return Block.Math(text.substring(2, text.length - 2).trim(), false)
        }
        return Block.Paragraph(text)
    }

    private fun isListItem(line: String): Boolean =
        LIST_ITEM_REGEX.containsMatchIn(line)

    private fun isImageLine(line: String): Boolean =
        IMAGE_LINE_REGEX.containsMatchIn(line)

    /**
     * 这几个正则**必须在顶层编译一次**。
     *
     * 它们对**每一行**都要跑一次。写在函数体里时，一篇 300 行的回答
     * 就是上千次正则编译——而流式输出每 120ms 重解析一次整篇，
     * 主线程直接被正则编译吃满，滚动和打字一起掉帧。
     */
    private val LIST_ITEM_REGEX = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
    private val IMAGE_LINE_REGEX = Regex("^\\s*!\\[[^\\]]*\\]\\(([^)]+)\\)\\s*$")
    private val RULE_REGEX = Regex("^\\s*[-*_]{3,}\\s*$")

    private fun isPipeTableRow(line: String): Boolean =
        line.trimStart().startsWith("|") && line.trimEnd().endsWith("|") &&
            line.count { it == '|' } >= 2

    /**
     * 解析一张管道表格，**并返回表格结束后的下一行下标**。
     *
     * ## 为什么必须返回下标（而不是就地解析完就算）
     *
     * 旧实现只接收表头行、就地往下吞行，**不告诉调用方吞到哪儿了**。
     * 调用方（[parsePlain]）当时用的是 `lines.forEach`，会把**每一行**
     * 都独立喂进来——于是表格的第 2 行、第 3 行也各自被当成「表头」
     * 再解析一次，同一张表被输出 3 次、4 次。
     *
     * 用户截图里的现象正是这个：一段 Markdown 表格的内容在气泡里
     * 连续出现 3 遍。根因不在渲染层，在解析层。
     *
     * 现在改成索引循环 + 返回下一行下标，表格整体只产出**一个**块。
     *
     * @param startIndex 表头所在行下标。
     * @return 表格最后一行之后的行下标（= 下一个待处理行）。
     */
    /**
     * 是否是 Markdown 表格的**分隔行**（`| --- | :---: |`）。
     *
     * 判据：整行由 `|`、`-`、`:`、空白组成，且至少有一个 `-`，
     * 且每一格都不含除 `-`/`:` 以外的字符。
     */
    private fun isTableSeparatorRow(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = trimmed.trim('|').split('|')
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' }
        }
    }

    private fun parsePipeTable(
        allLines: List<String>,
        startIndex: Int,
        blocks: MutableList<Block>
    ): Int {
        val rows = mutableListOf<List<String>>()
        val headerCells = allLines[startIndex].trim().trim('|').split('|').map { it.trim() }
        if (headerCells.any { it.isNotBlank() }) rows += headerCells
        var i = startIndex + 1
        while (i < allLines.size) {
            val line = allLines[i]
            if (!isPipeTableRow(line)) break
            // 分隔行（`| --- | --- |`）不产出内容行。
            //
            // 注意判定方式：单元格内容是「若干个 `-`（可带 `:` 对齐标记）」，
            // 不是单个字符 `-`——Markdown 标准写法就是 `| --- |`，
            // 按单字符判定会把它当成数据行，表格就会多出一行「---」。
            if (isTableSeparatorRow(line)) {
                i++
                continue
            }
            rows += line.trim().trim('|').split('|').map { it.trim() }
            i++
        }
        if (rows.isNotEmpty()) blocks += Block.Table(rows)
        // 表格至少占一行：即使内容为空（只有表头）也要跳过这一行，
        // 否则它会被外层循环再次当成表头解析。
        return maxOf(i, startIndex + 1)
    }
}
