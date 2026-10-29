package com.mistakebook.data.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 文档正文提取器（v0.0.54）：把资料库里的二进制文档（.docx / .pdf）
 * 尽力转成纯文本，供预览与对话上下文使用。
 *
 * 零依赖（AGENTS.md：不引入 PRD 之外的库）：
 * - .docx 本质是 zip，解压后用 JDK 自带 XML 解析器读 word/document.xml；
 * - .pdf 解析对象流：FlateDecode 用 Inflater 还原后，从内容流的
 *   Tj / TJ 文本算符里捞字符串。这是**尽力而为**的轻量实现——
 *   扫描件（纯图片）与 CID 自定义编码的 PDF 提不出文字，返回空串，
 *   由调用方给出「无法提取」的提示（不阻断导入与对话）。
 */
object DocumentTextExtractor {

    /** 超过该大小的文档直接放弃提取（手机内存友好）。 */
    private const val MAX_INPUT_BYTES = 64L * 1024 * 1024

    /** 单次提取的输出上限（字符），避免超大文档拖垮调用线程。 */
    private const val MAX_OUTPUT_CHARS = 512 * 1024

    fun extract(file: File): String = when (file.extension.lowercase()) {
        "docx" -> extractDocx(file)
        "pdf" -> extractPdf(file)
        else -> ""
    }

    // ---- docx：zip 里的 word/document.xml ----

    private fun extractDocx(file: File): String = runCatching {
        if (file.length() > MAX_INPUT_BYTES) return@runCatching ""
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes()
                    zip.closeEntry()
                    return@runCatching docxTextFromXml(xml)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            ""
        }
    }.getOrDefault("")

    /**
     * 按 w:p 段落聚合 w:t 文本（段落间换行）。先按 OOXML 命名空间找，
     * 找不到（个别生成器不带标准命名空间）再退回限定名匹配。
     */
    private fun docxTextFromXml(bytes: ByteArray): String = runCatching {
        val nsFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = nsFactory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val wns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        var paragraphs = doc.getElementsByTagNameNS(wns, "p")
        var tagName = "t"
        if (paragraphs.length == 0) {
            val plainFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            val doc2 = plainFactory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            paragraphs = doc2.getElementsByTagName("w:p")
            tagName = "w:t"
        }
        val sb = StringBuilder()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val runs = p.getElementsByTagName(tagName)
            val line = StringBuilder()
            for (j in 0 until runs.length) {
                line.append(runs.item(j).textContent)
            }
            if (line.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            if (sb.length > MAX_OUTPUT_CHARS) break
        }
        sb.toString().take(MAX_OUTPUT_CHARS)
    }.getOrDefault("")

    // ---- pdf：对象流 → 解压 → 文本算符 ----

    private fun extractPdf(file: File): String = runCatching {
        if (file.length() > MAX_INPUT_BYTES) return@runCatching ""
        val bytes = file.readBytes()
        val stream = "stream".toByteArray(Charsets.ISO_8859_1)
        val endStream = "endstream".toByteArray(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var pos = 0
        while (sb.length < MAX_OUTPUT_CHARS) {
            val start = indexOf(bytes, stream, pos) ?: break
            // "stream" 关键字后的换行符不属于流数据
            var dataStart = start + stream.size
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
            val end = indexOf(bytes, endStream, dataStart) ?: break
            val decompressed = inflate(bytes, dataStart, end)
            val content = String(decompressed, Charsets.ISO_8859_1)
            if (content.contains("BT") || content.contains("Tj") || content.contains("TJ")) {
                sb.append(textFromContent(content))
            }
            pos = end + endStream.size
        }
        sb.toString().take(MAX_OUTPUT_CHARS)
    }.getOrDefault("")

    /** FlateDecode（zlib 头）用 Inflater 还原；失败按未压缩原始数据处理。 */
    private fun inflate(bytes: ByteArray, from: Int, until: Int): ByteArray = try {
        val inflater = Inflater()
        inflater.setInput(bytes, from, until - from)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished() && out.size() < MAX_INPUT_BYTES) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        out.toByteArray()
    } catch (_: Throwable) {
        bytes.copyOfRange(from, until)
    }

    /**
     * 从内容流里按出现顺序抽取文本：
     * `(字面量) Tj` / `[ (a) (b) ] TJ` / `<十六进制> Tj`，遇
     * `Td / TD / T* / ET` 视为换行。
     */
    private fun textFromContent(content: String): String {
        val sb = StringBuilder()
        for (match in TEXT_OPERAND.findAll(content)) {
            val token = match.value.trimStart()
            when {
                token.startsWith('[') -> {
                    for (str in STRING_LITERAL.findAll(token)) {
                        val inner = str.value.substring(1, str.value.length - 1)
                        sb.append(decodeLiteral(inner))
                    }
                    for (str in HEX_STRING.findAll(token)) {
                        sb.append(decodeHex(str.value.trim('<', '>')))
                    }
                }
                token.startsWith('(') -> sb.append(decodeLiteral(match.groupValues[1]))
                token.startsWith('<') -> sb.append(decodeHex(match.groupValues[1]))
                else -> sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** 文本操作数 + 算符；捕获组：literal / hex / array（TJ 数组）/ line（换行算符）。 */
    private val TEXT_OPERAND = Regex(
        "\\((?<literal>(?:\\\\.|[^\\\\()])*)\\)\\s*(?:Tj|TJ|'|\")" +
            "|<(?<hex>[0-9A-Fa-f\\s]*)>\\s*(?:Tj|TJ|'|\")" +
            "|\\[(?<array>(?:\\((?:\\\\.|[^\\\\()])*\\)|<[0-9A-Fa-f\\s]*>|[^\\]])*)\\]\\s*TJ" +
            "|(?<line>(?<![A-Za-z0-9])(?:Td|TD|T\\*|ET)(?![A-Za-z0-9]))",
        RegexOption.DOT_MATCHES_ALL
    )

    private val STRING_LITERAL = Regex("\\((?:\\\\.|[^\\\\()])*\\)")
    private val HEX_STRING = Regex("<[0-9A-Fa-f\\s]*>")

    /** PDF 字面量转义：\n \r \t \b \f \( \) \\ 与八进制 \ddd。 */
    private fun decodeLiteral(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append(12.toChar()); i += 2 }
                '(', ')' -> { sb.append(next); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                in '0'..'7' -> {
                    var value = 0
                    var digits = 0
                    while (i + 1 < raw.length && digits < 3 && raw[i + 1] in '0'..'7') {
                        value = value * 8 + (raw[i + 1] - '0')
                        digits++
                        i++
                    }
                    i++
                    sb.append(value.toChar())
                }
                else -> { sb.append(next); i += 2 }
            }
        }
        return sb.toString()
    }

    /**
     * 十六进制字符串：长度是 4 的倍数时按 UTF-16BE 解（ToUnicode 常见），
     * 否则按 Latin-1 逐字节解。CID 字形编码解出来是乱码，宁乱勿崩。
     */
    private fun decodeHex(raw: String): String {
        val clean = raw.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ""
        return if (clean.length % 4 == 0) {
            val bytes = ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.UTF_16BE)
        } else {
            val bytes = ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int? {
        var i = from.coerceAtLeast(0)
        val limit = data.size - pattern.size
        while (i <= limit) {
            if (data[i] == pattern[0]) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return null
    }
}
