package com.mistakebook.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 资料库路径穿越防护与读写行为的单测（任务书 T6 硬性要求）。
 *
 * 穿越反例覆盖 Windows / POSIX 两种分隔符、多重穿越、伪装扩展名、
 * 绝对路径注入；正例覆盖四种一期扩展名。
 */
class LibraryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("library")

    // ---- 路径安全 ----

    @Test
    fun `simple names resolve inside root`() {
        val root = root()
        listOf("a.md", "笔记.txt", "data.json", "表.csv", "资料.docx", "试卷.pdf").forEach { name ->
            val f = LibraryStore.resolveLibraryPath(root, name)
            assertNotNull("应接受 $name", f)
            assertTrue(f!!.path.startsWith(root.canonicalPath))
        }
    }

    @Test
    fun `parent traversal is rejected`() {
        val root = root()
        assertNull(LibraryStore.resolveLibraryPath(root, "../evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, "..\\evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, "a/../../evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, ".."))
        assertNull(LibraryStore.resolveLibraryPath(root, "x.md/.."))
    }

    @Test
    fun `absolute path injection is rejected`() {
        val root = root()
        assertNull(LibraryStore.resolveLibraryPath(root, "C:\\Windows\\evil.md"))
        assertNull(LibraryStore.resolveLibraryPath(root, "/etc/passwd.md"))
    }

    @Test
    fun `unsupported or missing extension is rejected`() {
        val root = root()
        assertNull(LibraryStore.resolveLibraryPath(root, "file.exe"))
        assertNull(LibraryStore.resolveLibraryPath(root, "file.doc"))
        assertNull(LibraryStore.resolveLibraryPath(root, "noext"))
        assertNull(LibraryStore.resolveLibraryPath(root, ""))
        assertNull(LibraryStore.resolveLibraryPath(root, "  "))
    }

    @Test
    fun `nested names are rejected outright`() {
        val root = root()
        // 目录分量语义直接拒绝：资料库是平铺目录，不做子目录（也不给穿越留门缝）
        assertNull(LibraryStore.resolveLibraryPath(root, "sub/dir/x.md"))
    }

    // ---- 读写行为 ----

    @Test
    fun `write then read round trips`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "note.md")!!
        val bytes = LibraryStore.write(f, "# 标题\n\n正文内容")
        assertTrue(bytes > 0)
        val (text, truncated) = LibraryStore.read(f)!!
        assertEquals("# 标题\n\n正文内容", text)
        assertFalse(truncated)
    }

    @Test
    fun `read truncates over 50KB`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "big.txt")!!
        LibraryStore.write(f, "x".repeat(LibraryStore.MAX_READ_BYTES + 100))
        val (text, truncated) = LibraryStore.read(f)!!
        assertTrue(truncated)
        assertEquals(LibraryStore.MAX_READ_BYTES, text.toByteArray().size)
    }

    @Test
    fun `append grows file`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "log.md")!!
        LibraryStore.write(f, "A")
        LibraryStore.append(f, "B")
        assertEquals("AB", LibraryStore.read(f)!!.first)
    }

    @Test
    fun `delete removes file`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "gone.md")!!
        LibraryStore.write(f, "x")
        assertTrue(LibraryStore.delete(f))
        assertFalse(f.exists())
    }

    @Test
    fun `list returns files sorted`() {
        val root = root()
        LibraryStore.write(LibraryStore.resolveLibraryPath(root, "b.md")!!, "1")
        LibraryStore.write(LibraryStore.resolveLibraryPath(root, "a.md")!!, "2")
        val names = LibraryStore.list(root).map { it.name }
        assertEquals(listOf("a.md", "b.md"), names)
    }

    // ---- LibraryTool 分发 ----

    @Test
    fun `tool list and write round trip`() {
        val root = root()
        val w = LibraryTool.dispatch(root, "library_write", mapOf("name" to "x.md", "content" to "hello"))
        assertTrue(w.ok)
        val r = LibraryTool.dispatch(root, "library_read", mapOf("name" to "x.md"))
        assertTrue(r.ok)
        assertTrue(r.message.contains("hello"))
    }

    @Test
    fun `tool rejects traversal in every op`() {
        val root = root()
        listOf("library_read", "library_write", "library_edit", "library_delete").forEach { op ->
            val r = LibraryTool.dispatch(root, op, mapOf("name" to "../evil.md", "content" to "x"))
            assertFalse("$op 应拒绝穿越", r.ok)
        }
    }

    @Test
    fun `tool edit replaces first occurrence`() {
        val root = root()
        LibraryTool.dispatch(root, "library_write", mapOf("name" to "e.md", "content" to "AAA BBB"))
        val r = LibraryTool.dispatch(
            root, "library_edit",
            mapOf("name" to "e.md", "old" to "AAA", "content" to "CCC")
        )
        assertTrue(r.ok)
        assertEquals("CCC BBB", LibraryStore.read(LibraryStore.resolveLibraryPath(root, "e.md")!!)!!.first)
    }

    @Test
    fun `tool edit replace fails when old text missing`() {
        val root = root()
        LibraryTool.dispatch(root, "library_write", mapOf("name" to "e.md", "content" to "AAA"))
        val r = LibraryTool.dispatch(
            root, "library_edit",
            mapOf("name" to "e.md", "old" to "ZZZ", "content" to "CCC")
        )
        assertFalse(r.ok)
    }

    // ---- 二进制文档（.docx / .pdf）----

    /** 组一个最小 docx（zip + word/document.xml），用于提取测试。 */
    private fun docxWith(paragraphs: List<String>): ByteArray {
        val body = paragraphs.joinToString("") { p ->
            "<w:p><w:r><w:t>$p</w:t></w:r></w:p>"
        }
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body</w:body></w:document>"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** 组一个最小 PDF，内容流可选 Flate 压缩。 */
    private fun pdfWithContent(content: String, flate: Boolean): ByteArray {
        val body = "BT\n$content\nET"
        val bodyBytes = body.toByteArray(Charsets.ISO_8859_1)
        val streamBytes: ByteArray
        val filter = if (flate) {
            val deflater = java.util.zip.Deflater()
            deflater.setInput(bodyBytes)
            deflater.finish()
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(tmp)
                buf.write(tmp, 0, n)
            }
            deflater.end()
            streamBytes = buf.toByteArray()
            " /Filter /FlateDecode"
        } else {
            streamBytes = bodyBytes
            ""
        }
        val text = "%PDF-1.4\n" +
            "1 0 obj\n<< /Length ${streamBytes.size}$filter >>\n" +
            "stream\n" + String(streamBytes, Charsets.ISO_8859_1) + "\nendstream\nendobj\n" +
            "trailer\n<< /Root 1 0 R >>\n%%EOF\n"
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    // v0.0.85：DOCTYPE / 实体展开防护与超限放弃分支。
    @Test
    fun `docx with doctype entity expansion is rejected safely`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "投毒.docx")!!
        // 最小 docx，document.xml 带 DOCTYPE 内部实体与引用
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<!DOCTYPE w:document [<!ENTITY x \"boom\">]>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body><w:p><w:r><w:t>a&x;b</w:t></w:r></w:p></w:body></w:document>"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        f.writeBytes(out.toByteArray())
        // 不抛（外层 runCatching 兜底），且实体未被展开、解析被直接拒绝
        val text = DocumentTextExtractor.extract(f)
        assertFalse(text.contains("boom"))
        assertEquals("", text)
    }

    @Test
    fun `oversized docx abandons extraction`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "超大.docx")!!
        // 超过 MAX_INPUT_BYTES 一个字节即触发放弃分支（内容无所谓，先查长度）
        f.writeBytes(ByteArray(DocumentTextExtractor.MAX_INPUT_BYTES.toInt() + 1))
        assertEquals("", DocumentTextExtractor.extract(f))
    }

    @Test
    fun `docx paragraphs are extracted`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "作业.docx")!!
        f.writeBytes(docxWith(listOf("第一段 &amp; 标题", "第二段")))
        val (text, truncated) = LibraryStore.read(f)!!
        assertEquals("第一段 & 标题\n第二段", text)
        assertFalse(truncated)
    }

    @Test
    fun `pdf literal strings are extracted with line breaks`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "讲义.pdf")!!
        f.writeBytes(
            pdfWithContent("/F1 12 Tf 72 720 Td (Hello \\(World\\)) Tj 0 -20 Td (Line Two) Tj", flate = false)
        )
        val (text, _) = LibraryStore.read(f)!!
        assertEquals("Hello (World)\nLine Two", text)
    }

    @Test
    fun `pdf flate stream is inflated before extraction`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "压缩.pdf")!!
        f.writeBytes(pdfWithContent("(Deep inside) Tj", flate = true))
        val (text, _) = LibraryStore.read(f)!!
        assertEquals("Deep inside", text)
    }

    @Test
    fun `pdf without text is flagged as scanned instead of null`() {
        val root = root()
        val f = LibraryStore.resolveLibraryPath(root, "扫描件.pdf")!!
        f.writeBytes(pdfWithContent("/F1 1 Tf 0 0 Td", flate = false))
        // v0.0.77：扫描型 PDF 不再只吐一句「未能提取」——结构化标记
        // scannedPdf，让上层转页面图走识图/OCR（用户反馈 5）
        val result = LibraryStore.readDetailed(f)!!
        assertTrue("扫描件应被打标记", result.scannedPdf)
        assertTrue(result.text.contains("扫描件"))
        assertFalse(result.truncated)
        // read() 的旧契约保持：仍返回提示文案而不是 null
        val (text, truncated) = LibraryStore.read(f)!!
        assertTrue(text.contains("扫描件"))
        assertFalse(truncated)
    }

    @Test
    fun `tool cannot write or edit binary docs`() {
        val root = root()
        val w = LibraryTool.dispatch(root, "library_write", mapOf("name" to "a.pdf", "content" to "x"))
        assertFalse("应拒绝写入 pdf", w.ok)
        val f = LibraryStore.resolveLibraryPath(root, "a.pdf")!!
        // 文本要够长：PdfTextQuality 要求 ≥8 个字符才认作文本层可用，
        // 否则会被判成扫描件（那时 library_read 会走「请识图」分支）
        f.writeBytes(pdfWithContent("(keep this readable text) Tj", flate = false))
        val e = LibraryTool.dispatch(root, "library_edit", mapOf("name" to "a.pdf", "content" to "x"))
        assertFalse("应拒绝编辑 pdf", e.ok)
        val r = LibraryTool.dispatch(root, "library_read", mapOf("name" to "a.pdf"))
        assertTrue(r.ok)
        assertTrue(r.message.contains("keep"))
    }
}
