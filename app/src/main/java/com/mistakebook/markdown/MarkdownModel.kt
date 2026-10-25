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
        data class Code(val text: String) : Block
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

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += paragraphBlock(paragraph.toString().trim())
            }
            paragraph.clear()
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            val openMath = displayMath

            when {
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

                line.trimStart().startsWith("```") -> {
                    if (inCode) {
                        blocks += Block.Code(code.toString().trimEnd())
                        code.clear()
                        inCode = false
                    } else {
                        flushParagraph()
                        inCode = true
                    }
                }

                inCode -> code.appendLine(line)

                line.isBlank() -> flushParagraph()

                // 独占一行的 `$$`：进入公式块，攒到下一个独占的 `$$` 为止
                line.trim() == "\$\$" -> {
                    flushParagraph()
                    displayMath = StringBuilder()
                }

                line.trimStart().startsWith("$$") -> {
                    flushParagraph()
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
                    flushParagraph()
                    val closeAt = line.indexOf("\\]", 2)
                    if (closeAt > 2) {
                        val latex = line.trim().substring(2, closeAt).trim()
                        if (latex.isNotEmpty()) blocks += Block.Math(latex, true)
                        val rest = line.substring(closeAt + 2).trim()
                        if (rest.isNotBlank()) paragraph.append(rest)
                    }
                    // 未闭合：不建状态机，落进 else 按正文走——parseInline 里
                    // 同样认 `\[`，闭合后自然升级成公式；流式藏尾见 ChatStreaming。
                }

                line.matches(RULE_REGEX) -> {
                    flushParagraph()
                    blocks += Block.Rule
                }

                line.trimStart().startsWith("#") -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += Block.Heading(level, line.trimStart().drop(level).trim())
                }

                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    blocks += Block.Quote(line.trimStart().removePrefix(">").trim())
                }

                isPipeTableRow(line) -> {
                    flushParagraph()
                    parsePipeTable(lines, line, blocks)
                }

                isImageLine(line) -> {
                    flushParagraph()
                    val match = IMAGE_LINE_REGEX.find(line)
                    if (match != null) {
                        blocks += Block.Image(match.groupValues[2].trim(), match.groupValues[1])
                    }
                }

                isListItem(line) -> {
                    flushParagraph()
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
        }
        flushParagraph()
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
        if (inCode && code.isNotBlank()) blocks += Block.Code(code.toString().trimEnd())
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

    private fun parsePipeTable(allLines: List<String>, header: String, blocks: MutableList<Block>) {
        val rows = mutableListOf<List<String>>()
        val headerCells = header.trim().trim('|').split('|').map { it.trim() }
        if (headerCells.any { it.isNotBlank() }) rows += headerCells
        val index = allLines.indexOf(header)
        for (i in (index + 1) until allLines.size) {
            val line = allLines[i]
            if (!isPipeTableRow(line)) break
            if (line.trim().trim('|').all { it == '-' || it == ':' || it == ' ' }) continue
            rows += line.trim().trim('|').split('|').map { it.trim() }
        }
        if (rows.isNotEmpty()) blocks += Block.Table(rows)
    }
}
