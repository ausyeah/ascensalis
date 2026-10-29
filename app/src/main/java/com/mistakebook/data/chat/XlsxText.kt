package com.mistakebook.data.chat

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * XLSX 抽文本（**零依赖**，与 [DocxText] 同一套思路）。
 *
 * ## 为什么需要它（v0.0.57 用户反馈「xls 不支持」）
 *
 * 此前附件分派只认 PDF / DOCX / 纯文本，xlsx 落进 [com.mistakebook.domain.AttachmentKind.OTHER]：
 * 界面显示「就绪」，模型却一个字都收不到——用户以为发了成绩表，
 * 模型却说「你没有发文件」。这里补齐 xlsx 的本地抽取。
 *
 * ## XLSX 是什么
 *
 * 一个 zip 包：
 * - `xl/sharedStrings.xml`：共享字符串表（单元格 `t="s"` 时存的是这里的下标）；
 * - `xl/worksheets/sheet1.xml`、`sheet2.xml`…：每张表一行 `<row>`、一格 `<c>`。
 *
 * 单元格取值规则：
 * - `t="s"` → `<v>` 是 sharedStrings 的下标；
 * - `t="inlineStr"` → `<is><t>…</t></is>` 内联字符串；
 * - `t="str"` → `<v>` 是公式结果字符串；
 * - 其它（缺省 / `n`）→ `<v>` 是数字，原样输出。
 *
 * ## 为什么用正则而不是 XML 解析器
 *
 * 与 [DocxText] 一致：只关心「标签之间的字」，不关心 XML 是否合法。
 * 为了抽一张表去搭 SAX 回调、处理命名空间，成本远高于收益。
 * 代价写在明处：正文里若出现字面量 `<c>` 会被误切（正常 xlsx 不会）。
 *
 * ## 已知取舍
 *
 * **日期不做转换**：xlsx 里日期是「1900 起的序列号」，要还原得连带解析
 * `styles.xml` 的 numFmt。教辅场景里的表格以分数/文字为主，日期序列号
 * 也不影响模型理解结构，所以先不引入这层复杂度；真需要时再加。
 */
object XlsxText {

    /**
     * 抽取整个工作簿的文本，按工作表顺序拼接。
     *
     * @return 抽出的纯文本；不是 xlsx 或抽不出内容时返回空串。
     */
    fun extract(bytes: ByteArray): String {
        val entries = readZip(bytes) ?: return ""
        val shared = entries[SHARED_STRINGS_PATH]?.let(::parseSharedStrings).orEmpty()
        // 按文件名排序，保证每次输出顺序稳定（zip 条目顺序本身不一定稳定）。
        val sheets = entries
            .filterKeys { it.startsWith(SHEET_PREFIX) && it.endsWith(".xml") }
            .toSortedMap(compareBy(String::sheetOrder))
        if (sheets.isEmpty()) return ""
        val sb = StringBuilder()
        sheets.forEach { (path, xml) ->
            if (xml.isEmpty()) return@forEach
            val table = parseSheet(xml, shared)
            if (table.isBlank()) return@forEach
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【").append(sheetName(path)).append("】\n")
            sb.append(table)
        }
        return sb.toString().trim()
    }

    /** 纯解析：sharedStrings.xml → 字符串列表（一个 `<si>` 一条）。 */
    fun parseSharedStrings(xml: String): List<String> {
        if (xml.isEmpty()) return emptyList()
        return SI_OPEN.findAll(xml).map { si ->
            val end = xml.indexOf("</si>", si.range.last + 1)
            val body = if (end < 0) xml.substring(si.range.last + 1) else xml.substring(si.range.last + 1, end)
            // 一个 <si> 可能被拆成多个 <r><t>（富文本），全部拼起来。
            T_OPEN.findAll(body).joinToString("") { t ->
                val tEnd = body.indexOf("</t>", t.range.last + 1)
                if (tEnd < 0) "" else unescape(body.substring(t.range.last + 1, tEnd))
            }
        }.toList()
    }

    /**
     * 纯解析：一张工作表 xml → 以 `\t` 分列、`\n` 分行的文本。
     *
     * 按单元格的列引用（`A1`、`B1`…）定位列号，空列补空字符串，
     * 这样错位的表格不会因为「少读几个空格」而串行。
     */
    fun parseSheet(xml: String, shared: List<String>): String {
        if (xml.isEmpty()) return ""
        val sb = StringBuilder()
        ROW_OPEN.findAll(xml).forEach { row ->
            // 自闭合空行 `<row .../>`：没有单元格，跳过。
            // 不先判掉的话，下面 indexOf("</row>") 会一路找到**下一行**的
            // 结束标签，把两行拼成一行解析。
            val openEnd = xml.indexOf('>', row.range.last + 1)
            if (openEnd < 0) return@forEach
            if (xml[openEnd - 1] == '/') return@forEach
            val rowEnd = xml.indexOf("</row>", openEnd + 1)
            if (rowEnd < 0) return@forEach
            val rowBody = xml.substring(openEnd + 1, rowEnd)
            val cells = mutableListOf<String>()
            var maxCol = -1
            CELL.findAll(rowBody).forEach { cell ->
                val attrs = cell.groupValues[1]
                val inner = cell.groupValues.getOrElse(2) { "" }
                val col = columnIndex(attr(attrs, "r"))
                val text = cellText(attrs, inner, shared)
                // 空单元格也按列号占位，保证后面的列不左移。
                while (cells.size <= col) cells.add("")
                if (col >= 0) cells[col] = text
                if (col > maxCol) maxCol = col
            }
            if (maxCol < 0) return@forEach
            // 去掉行尾的空列，表格右边全空时不拖一堆制表符。
            while (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.lastIndex)
            if (cells.isEmpty()) return@forEach
            sb.append(cells.joinToString("\t")).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    // ------------------------------------------------------------ 内部

    /** 读 zip 里全部 xml 条目（只保留感兴趣的两个前缀）。 */
    private fun readZip(bytes: ByteArray): Map<String, String>? = runCatching {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == SHARED_STRINGS_PATH || (name.startsWith(SHEET_PREFIX) && name.endsWith(".xml"))) {
                    out[name] = zip.readBytes().toString(Charsets.UTF_8)
                } else {
                    zip.closeEntry()
                }
                entry = zip.nextEntry
            }
        }
        out
    }.getOrNull()

    /** 单元格文本：按 `t` 属性决定怎么取值。 */
    private fun cellText(attrs: String, inner: String, shared: List<String>): String {
        val type = attr(attrs, "t")
        if (type == "inlineStr") {
            return T_OPEN.findAll(inner).joinToString("") { t ->
                val end = inner.indexOf("</t>", t.range.last + 1)
                if (end < 0) "" else unescape(inner.substring(t.range.last + 1, end))
            }
        }
        val v = V_TAG.find(inner)?.groupValues?.get(1).orEmpty()
        if (v.isEmpty()) return ""
        if (type == "s") {
            // 共享字符串下标。越界（损坏文件）时退回原值，总比整行空掉强。
            val index = v.trim().toIntOrNull()
            return index?.let { shared.getOrNull(it) } ?: v
        }
        return unescape(v)
    }

    /** `B7` → 1（0 基）。没有引用时按出现顺序由调用方补位（这里返回 -1）。 */
    fun columnIndex(ref: String): Int {
        var col = -1
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = (if (col < 0) 0 else (col + 1) * 26) + (ch - 'A')
            } else if (ch in 'a'..'z') {
                col = (if (col < 0) 0 else (col + 1) * 26) + (ch - 'a')
            } else {
                break
            }
        }
        return if (col < 0) 0 else col
    }

    /** 从属性串里取 `name="value"`。 */
    private fun attr(attrs: String, name: String): String =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1).orEmpty()

    /** 工作表名：`sheet1.xml` → `sheet1`；有 workbook.xml 时将来可换成真实表名。 */
    private fun sheetName(path: String): String =
        path.substringAfterLast('/').removeSuffix(".xml")

    /** 让 sheet2 排在 sheet10 前面（字典序会把 10 排到 2 前）。 */
    private fun String.sheetOrder(): String = SUB_INDEX.find(this)?.value?.padStart(6, '0') ?: this

    /** XML 实体还原。与 [DocxText.unescape] 相同的五个。 */
    fun unescape(text: String): String {
        if ('&' !in text) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private const val SHARED_STRINGS_PATH = "xl/sharedStrings.xml"
    private const val SHEET_PREFIX = "xl/worksheets/sheet"

    private val SI_OPEN = Regex("<si[ >]")
    private val T_OPEN = Regex("<t[ >]")
    private val V_TAG = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val ROW_OPEN = Regex("<row[ >]")
    private val CELL = Regex("<c([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val SUB_INDEX = Regex("""sheet(\d+)""")
}
