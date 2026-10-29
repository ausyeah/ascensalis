package com.mistakebook.data.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 零依赖 PDF 文本抽取（java.util.zip.Inflater 解 FlateDecode 流）。
 *
 * 目标场景：Word / LaTeX 导出的文本 PDF、带 ToUnicode CMap 的嵌字字体。
 * 纯图片 PDF 抽不出文字时返回空串，由调用方改走 MinerU 图片识别。
 */
object PdfTextExtractor {

    private const val MIN_PAGE_CHARS = 20
    private const val MAX_STREAM_BYTES = 4L * 1024 * 1024
    private const val MAX_INFLATE_BYTES = 4 * 1024 * 1024
    private const val MAX_OBJECTS = 20000
    private const val MAX_PAGES = 200
    private const val MAX_TOTAL_CHARS = 400_000

    private val OBJECT_REF_REGEX = Regex("^(\\d+)\\s+\\d+\\s+R$")
    private val OBJECT_DEF_REGEX = Regex("(\\d+)\\s+(\\d+)\\s+obj")
    private val INDIRECT_REF_REGEX = Regex("(\\d+)\\s+\\d+\\s+R")
    private val BEGIN_END_BFCHAR_REGEX = Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL)
    private val BFCHAR_ENTRY_REGEX = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
    private val BEGIN_END_BFRANGE_REGEX = Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL)
    private val BFRANGE_ENTRY_REGEX = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")

    class PdfObject(
        val number: Int,
        val dictionary: Map<String, String>,
        private val rawStream: ByteArray?
    ) {
        val data: ByteArray? by lazy { rawStream?.let { decompress(dictionary, it) } }

        fun typeName(): String? = dictionary["/Type"]

        fun get(key: String): String? = dictionary[key]

        fun ref(key: String): Int? =
            OBJECT_REF_REGEX.matchEntire(dictionary[key]?.trim().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()

        fun dictValue(key: String): Map<String, String>? = dictionary[key]?.let { parseDict(it) }
    }

    /** 逐页文本；解析失败或图片型 PDF 返回空串。 */
    fun extractAll(bytes: ByteArray): List<String> {
        val fontToUnicode = mutableMapOf<Int, Map<Int, Char>>()
        val objects = parseObjects(bytes)
        return collectPageObjects(objects)
            .take(MAX_PAGES)
            .map { page -> extractPageText(page, objects, fontToUnicode) }
            .map { text -> if (text.length > MAX_TOTAL_CHARS) text.take(MAX_TOTAL_CHARS) else text }
    }

    fun pageCount(bytes: ByteArray): Int =
        collectPageObjects(parseObjects(bytes)).size

    /** 至少一半页面能抽出 >= 20 个字符时认为是文本 PDF。 */
    fun isTextPdf(bytes: ByteArray): Boolean {
        val pages = extractAll(bytes)
        if (pages.isEmpty()) return false
        return pages.count { it.trim().length >= MIN_PAGE_CHARS } * 2 >= pages.size
    }


    // ===== 对象表 =====

    private fun parseObjects(bytes: ByteArray): Map<Int, PdfObject> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val objects = mutableMapOf<Int, PdfObject>()
        val regex = OBJECT_DEF_REGEX
        var searchIndex = 0
        while (true) {
            if (objects.size >= MAX_OBJECTS) break
            val match = regex.find(text, searchIndex) ?: break
            val number = match.groupValues[1].toInt()
            val bodyStart = match.range.last + 1
            val endObj = text.indexOf("endobj", bodyStart)
            if (endObj < 0) break
            objects[number] = parseObjectBody(number, text.substring(bodyStart, endObj))
            searchIndex = endObj + 6
        }
        return objects
    }

    private fun parseObjectBody(number: Int, body: String): PdfObject {
        val streamIndex = body.indexOf("stream")
        val dictSource = if (streamIndex >= 0) body.substring(0, streamIndex) else body
        val dict = parseDict(dictSource)
        val raw = if (streamIndex >= 0) {
            var dataStart = streamIndex + 6
            if (dataStart < body.length && body[dataStart] == '\r') dataStart++
            if (dataStart < body.length && body[dataStart] == '\n') dataStart++
            var dataEnd = body.lastIndexOf("endstream")
            if (dataEnd < dataStart) dataEnd = body.length
            var value = body.substring(dataStart, dataEnd).toByteArray(Charsets.ISO_8859_1)
            if (value.isNotEmpty() && value.last() == '\n'.code.toByte()) {
                value = value.copyOf(value.size - 1)
                if (value.isNotEmpty() && value.last() == '\r'.code.toByte()) {
                    value = value.copyOf(value.size - 1)
                }
            }
            value
        } else {
            null
        }
        return PdfObject(number, dict, raw)
    }

    /** 解析 `<< /A 1 /B [ 1 2 ] /C << ... >> >>`；嵌套 <<>> 与 [...] 作为字符串值整体保留。 */
    private fun parseDict(source: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = source.indexOf("<<")
        if (index < 0) return result
        index += 2
        while (index < source.length) {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length || source.startsWith(">>", index)) break
            if (source[index] != '/') {
                index++
                continue
            }
            var cursor = index + 1
            while (cursor < source.length &&
                (source[cursor].isLetterOrDigit() || source[cursor] == '#')
            ) cursor++
            val key = source.substring(index, cursor)
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (cursor >= source.length) break
            val valueStart = cursor
            when {
                source.startsWith("<<", cursor) -> {
                    var depth = 0
                    while (cursor < source.length) {
                        if (source.startsWith("<<", cursor)) {
                            depth++
                            cursor += 2
                        } else if (source.startsWith(">>", cursor)) {
                            depth--
                            cursor += 2
                            if (depth == 0) break
                        } else {
                            cursor++
                        }
                    }
                }

                source[cursor] == '[' -> {
                    var depth = 0
                    while (cursor < source.length) {
                        if (source[cursor] == '[') depth++
                        if (source[cursor] == ']') {
                            depth--
                            if (depth == 0) {
                                cursor++
                                break
                            }
                        }
                        cursor++
                    }
                }

                source[cursor] == '(' || source[cursor] == '<' -> {
                    val open = source[cursor]
                    val close = if (open == '(') ')' else '>'
                    var depth = 1
                    cursor++
                    while (cursor < source.length && depth > 0) {
                        when {
                            source[cursor] == '\\' -> cursor += 2
                            open == '(' && source[cursor] == '(' -> {
                                depth++
                                cursor++
                            }

                            source[cursor] == close -> {
                                depth--
                                if (depth == 0) cursor++
                            }

                            else -> cursor++
                        }
                    }
                }

                else -> {
                    // 名字 / 数字 / 关键字 / 间接引用：允许值以 / 开头，遇下一个键的 / 或 >> 结束
                    // （值内部允许空格，例如 "4 0 R"、"199 0 R"）
                    while (cursor < source.length) {
                        val ch = source[cursor]
                        if (source.startsWith(">>", cursor)) break
                        if (ch == '/' && cursor > valueStart) break
                        cursor++
                    }
                }
            }
            val value = source.substring(valueStart, cursor).trim()
            if (value.isNotEmpty()) result[key] = value
            index = cursor
        }
        return result
    }

    private fun collectPageObjects(objects: Map<Int, PdfObject>): List<PdfObject> {
        val pages = objects.values.filter { it.typeName() == "/Page" }.sortedBy { it.number }
        if (pages.isNotEmpty()) return pages
        val root = objects.values.firstOrNull { it.typeName() == "/Catalog" } ?: return emptyList()
        val pagesRef = root.ref("/Pages") ?: return emptyList()
        return collectKids(objects, mutableSetOf(), pagesRef)
    }

    private fun collectKids(
        objects: Map<Int, PdfObject>,
        visited: MutableSet<Int>,
        objectNumber: Int
    ): List<PdfObject> {
        if (!visited.add(objectNumber)) return emptyList()
        val obj = objects[objectNumber] ?: return emptyList()
        if (obj.typeName() == "/Page") return listOf(obj)
        val kids = mutableListOf<PdfObject>()
        obj.get("/Kids")?.let { kidsRaw ->
            INDIRECT_REF_REGEX.findAll(kidsRaw).forEach { match ->
                kids += collectKids(objects, visited, match.groupValues[1].toInt())
            }
        }
        return kids
    }

    // ===== 流解压 =====

    private fun decompress(dictionary: Map<String, String>, raw: ByteArray): ByteArray? {
        // 图片 / 超大 / 已压缩图像流一律不解压：省内存也避免无谓 CPU
        if (dictionary["/Subtype"]?.contains("Image") == true) return null
        if (dictionary["/Type"]?.contains("XObject") == true) return null
        val filterKind = dictionary["/Filter"].orEmpty()
        if (filterKind.contains("DCTDecode") || filterKind.contains("JPXDecode") ||
            filterKind.contains("CCITTFaxDecode") || filterKind.contains("JBIG2Decode")
        ) return null
        dictionary["/Length"]?.trim()?.toLongOrNull()?.let { length ->
            if (length > MAX_STREAM_BYTES) return null
        }
        val filter = dictionary["/Filter"]?.trim().orEmpty()
        return when {
            filter.isBlank() -> raw
            filter.contains("FlateDecode") -> inflate(raw)
            filter.contains("ASCIIHexDecode") -> decodeAsciiHex(raw)
            else -> null
        }
    }

    private fun inflate(raw: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(maxOf(1024, raw.size * 4))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                if (count > 0) {
                    // 解压炸弹守卫（v0.0.65）：几 MB 的恶意/损坏流可以膨胀出
                    // 任意大的字节数组，直接把 App 打成 OOM。超上限按「解不出
                    // 可用内容」处理（返回 null → 该页抽不出文字 → 走 MinerU）。
                    if (out.size() + count > MAX_INFLATE_BYTES) return null
                    out.write(buffer, 0, count)
                }
            }
            out.toByteArray()
        } catch (error: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun decodeAsciiHex(raw: ByteArray): ByteArray {
        val hex = String(
            raw.takeWhile { it != '>'.code.toByte() }.toByteArray(),
            Charsets.ISO_8859_1
        ).filter { !it.isWhitespace() }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    // ===== 文本抽取 =====

    private fun extractPageText(
        page: PdfObject,
        objects: Map<Int, PdfObject>,
        fontToUnicode: MutableMap<Int, Map<Int, Char>>
    ): String {
        val streamBuilder = StringBuilder()
        page.get("/Contents")?.let { contentRefs ->
            INDIRECT_REF_REGEX.findAll(contentRefs).forEach { match ->
                val data = objects[match.groupValues[1].toInt()]?.data
                if (data != null) streamBuilder.append(String(data, Charsets.ISO_8859_1)).append('\n')
            }
        }
        if (streamBuilder.isEmpty()) {
            page.data?.let { streamBuilder.append(String(it, Charsets.ISO_8859_1)) }
        }
        if (streamBuilder.isEmpty()) return ""
        return extractText(
            streamBuilder.toString(),
            resolvePageFonts(page, objects, fontToUnicode),
            fontToUnicode
        )
    }

    private fun resolvePageFonts(
        page: PdfObject,
        objects: Map<Int, PdfObject>,
        fontToUnicode: MutableMap<Int, Map<Int, Char>>
    ): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val fontsRaw = page.dictValue("/Resources")?.get("/Font") ?: return result
        parseDict(fontsRaw).forEach { (name, ref) ->
            val number = INDIRECT_REF_REGEX.find(ref)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            result[name] = number
            val cmap = objects[number]?.ref("/ToUnicode")?.let { objects[it]?.data } ?: return@forEach
            registerToUnicode(number, String(cmap, Charsets.ISO_8859_1), fontToUnicode)
        }
        return result
    }

    private fun extractText(
        content: String,
        fonts: Map<String, Int>,
        fontToUnicode: Map<Int, Map<Int, Char>>
    ): String {
        val out = StringBuilder()
        var fontNumber: Int? = null
        var inText = false
        var index = 0

        fun appendString(text: String) {
            out.append(decodeString(text, fontNumber?.let { fontToUnicode[it] }))
        }


        while (index < content.length) {
            val c = content[index]
            when {
                c == '(' -> {
                    val (text, next) = readLiteralString(content, index)
                    if (inText) appendString(text)
                    index = next
                }

                c == '<' && !content.startsWith("<<", index) -> {
                    val end = content.indexOf('>', index)
                    if (end > index) {
                        if (inText) {
                            val hex = content.substring(index + 1, end).filter { !it.isWhitespace() }
                            val bytes = ByteArray(hex.length / 2) { i ->
                                hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
                            }
                            out.append(String(bytes, Charsets.ISO_8859_1))
                        }
                        index = end + 1
                    } else {
                        index++
                    }
                }

                content.startsWith("BT", index) -> {
                    inText = true
                    index += 2
                }

                content.startsWith("ET", index) -> {
                    inText = false
                    index += 2
                }

                content.startsWith("Tj", index) || content.startsWith("TJ", index) -> index += 2

                content.startsWith("Tf", index) -> {
                    var cursor = index
                    while (cursor > 0 && content[cursor - 1].isWhitespace()) cursor--
                    while (cursor > 0 && !content[cursor - 1].isWhitespace()) cursor--
                    while (cursor > 0 && content[cursor - 1].isWhitespace()) cursor--
                    val fontEnd = cursor
                    while (cursor > 0 && !content[cursor - 1].isWhitespace()) cursor--
                    if (cursor < fontEnd) {
                        fonts[content.substring(cursor, fontEnd)]?.let { fontNumber = it }
                    }
                    index += 2
                }

                content.startsWith("Td", index) || content.startsWith("TD", index) ||
                    content.startsWith("T*", index) || content.startsWith("TL", index) ||
                    content.startsWith("Tm", index) -> index += 2

                else -> index++
            }
        }
        return out.toString()
    }

    private fun readLiteralString(content: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var index = start + 1
        var depth = 1
        while (index < content.length && depth > 0) {
            val c = content[index]
            when {
                c == '\\' && index + 1 < content.length -> {
                    val next = content[index + 1]
                    when (next) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b', 'f' -> Unit
                        '(', ')', '\\' -> out.append(next)
                        in '0'..'7' -> {
                            var digits = ""
                            var cursor = index + 1
                            while (cursor < content.length &&
                                digits.length < 3 &&
                                content[cursor] in '0'..'7'
                            ) {
                                digits += content[cursor]
                                cursor++
                            }
                            out.append(digits.toIntOrNull(8)?.toChar() ?: '?')
                            index = cursor - 1
                        }

                        '\r', '\n' -> {
                            if (next == '\r' && index + 2 < content.length && content[index + 2] == '\n') index++
                        }

                        else -> out.append(next)
                    }
                    index += 2
                }

                c == '(' -> {
                    depth++
                    out.append(c)
                    index++
                }

                c == ')' -> {
                    depth--
                    index++
                    if (depth > 0) out.append(c)
                }

                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString() to index
    }

    private fun decodeString(text: String, toUnicode: Map<Int, Char>?): String {
        if (toUnicode.isNullOrEmpty()) return text
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val builder = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            if (i + 1 < bytes.size) {
                val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val two = toUnicode[code]
                if (two != null) {
                    builder.append(two)
                    i += 2
                    continue
                }
            }
            val one = toUnicode[bytes[i].toInt() and 0xFF]
            builder.append(one ?: bytes[i].toInt().toChar())
            i++
        }
        return builder.toString()
    }

    private fun registerToUnicode(
        objectNumber: Int,
        cmap: String,
        fontToUnicode: MutableMap<Int, Map<Int, Char>>
    ) {
        fontToUnicode[objectNumber] = parseToUnicodeCmap(cmap)
    }

    private fun parseToUnicodeCmap(cmap: String): Map<Int, Char> {
        val result = mutableMapOf<Int, Char>()
        BEGIN_END_BFCHAR_REGEX.find(cmap)?.groupValues?.get(1)
            ?.lines()
            ?.forEach { line ->
                val match = BFCHAR_ENTRY_REGEX.find(line) ?: return@forEach
                val source = match.groupValues[1].toIntOrNull(16) ?: return@forEach
                val target = decodeUtf16Hex(match.groupValues[2]) ?: return@forEach
                result[source] = target
            }
        BEGIN_END_BFRANGE_REGEX.find(cmap)?.groupValues?.get(1)
            ?.lines()
            ?.forEach { line ->
                val match = BFRANGE_ENTRY_REGEX.find(line) ?: return@forEach
                val low = match.groupValues[1].toIntOrNull(16) ?: return@forEach
                val high = match.groupValues[2].toIntOrNull(16) ?: return@forEach
                val start = match.groupValues[3].toIntOrNull(16) ?: return@forEach
                for (code in low..high) result[code] = (start + (code - low)).toChar()
            }
        return result
    }

    private fun decodeUtf16Hex(hex: String): Char? {
        if (hex.isBlank()) return null
        if (hex.length <= 4) return hex.toIntOrNull(16)?.toChar()
        return try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            String(bytes, java.nio.charset.StandardCharsets.UTF_16BE).firstOrNull()
        } catch (error: Exception) {
            null
        }
    }
}
