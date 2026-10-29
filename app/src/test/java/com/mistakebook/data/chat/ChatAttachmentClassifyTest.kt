package com.mistakebook.data.chat

import com.mistakebook.domain.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 附件类型分派测试。
 *
 * 分派错了不会崩，只会「UI 显示就绪但模型什么都没收到」——
 * 这种故障极难排查，所以每个分支都钉住。
 */
class ChatAttachmentClassifyTest {

    private val preparer = ChatAttachmentPreparer

    private fun classify(fileName: String, mime: String? = null) =
        ChatAttachmentPreparer.classify(fileName, mime)

    // ------------------------------------------------------------ 图片

    @Test
    fun `MIME 是 image 就算图片`() {
        listOf("image/jpeg", "image/png", "image/webp", "image/heic")
            .forEach { mime ->
                assertEquals(mime, AttachmentKind.IMAGE, classify("无扩展名", mime))
            }
    }

    @Test
    fun `没有 MIME 时按扩展名认图片`() {
        listOf("a.jpg", "a.JPEG", "a.png", "a.webp", "a.heic", "a.bmp")
            .forEach { name -> assertEquals(name, AttachmentKind.IMAGE, classify(name, null)) }
    }

    // ------------------------------------------------------------ PDF

    @Test
    fun `PDF 被识别`() {
        assertEquals(AttachmentKind.PDF, classify("试卷.pdf", "application/pdf"))
        assertEquals(AttachmentKind.PDF, classify("试卷.PDF", null))
    }

    // ------------------------------------------------------------ 文本

    @Test
    fun `纯文本与 Markdown 被识别`() {
        listOf("a.txt", "a.md", "a.markdown", "a.csv")
            .forEach { name ->
                assertEquals(name, AttachmentKind.TEXT, classify(name, null))
            }
        assertEquals(AttachmentKind.TEXT, classify("x", "text/plain"))
        assertEquals(AttachmentKind.TEXT, classify("x", "text/markdown"))
    }

    @Test
    fun `无扩展名又无 MIME 的文件不猜`() {
        // 反向用例：文件名「readme」看着像文本，但既没扩展名也没 MIME。
        // 猜成文本的话，用户传了个二进制文件，界面显示「就绪」而抽出来一堆乱码，
        // 模型拿着乱码回答——比老实说「不支持」糟糕得多。
        assertEquals(AttachmentKind.OTHER, classify("readme", null))
    }

    // ------------------------------------------------------------ DOCX

    @Test
    fun `DOCX 被识别`() {
        val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(AttachmentKind.DOCX, classify("作业.docx", mime))
        assertEquals(AttachmentKind.DOCX, classify("作业.DOCX", null))
    }

    @Test
    fun `老 doc 归 OTHER 而不是 DOCX`() {
        // 反向用例：老 doc 是二进制格式，零依赖解不开。
        // 归成 DOCX 会让 UI 显示「就绪」，而模型其实什么都没收到——
        // 用户以为文件给了 AI，其实没有，而且没有任何报错。
        assertEquals(AttachmentKind.OTHER, classify("作业.doc", null))
        assertEquals(
            AttachmentKind.OTHER,
            classify("作业.doc", "application/msword")
        )
    }

    // ------------------------------------------------------------ 其它

    @Test
    fun `认不出的类型归 OTHER`() {
        listOf("a.zip", "a.mp4", "a.exe", "a.pptx", "无扩展名")
            .forEach { name -> assertEquals(name, AttachmentKind.OTHER, classify(name, null)) }
        // v0.0.57：xls 已支持（老版二进制格式在读取时给「请另存为」提示）
        assertEquals(AttachmentKind.XLSX, classify("a.xls", null))
    }

    @Test
    fun `MIME 与扩展名冲突时以 MIME 为准`() {
        // 部分网盘会把 PNG 报成 application/octet-stream，
        // 但也有把 jpg 报成 application/pdf 的畸形情况——这时信 MIME 更安全
        assertEquals(AttachmentKind.IMAGE, classify("photo.pdf", "image/jpeg"))
    }

    @Test
    fun `MIME 为空串等同 null`() {
        assertEquals(AttachmentKind.PDF, classify("a.pdf", ""))
    }

    // ------------------------------------------------------------ 落盘扩展名

    @Test
    fun `图片统一落成 jpg`() {
        // 已转码成 JPEG，再按原扩展名存会让 mime 判断与实际内容不一致
        assertEquals("jpg", ChatAttachmentPreparer.extensionFor(AttachmentKind.IMAGE, "a.png"))
        assertEquals("jpg", ChatAttachmentPreparer.extensionFor(AttachmentKind.IMAGE, "a.webp"))
    }

    @Test
    fun `文本按原类型落盘`() {
        assertEquals("md", ChatAttachmentPreparer.extensionFor(AttachmentKind.TEXT, "笔记.md"))
        assertEquals("txt", ChatAttachmentPreparer.extensionFor(AttachmentKind.TEXT, "笔记.txt"))
    }

    @Test
    fun `其它类型沿用原扩展名`() {
        assertEquals("zip", ChatAttachmentPreparer.extensionFor(AttachmentKind.OTHER, "a.zip"))
        assertEquals("bin", ChatAttachmentPreparer.extensionFor(AttachmentKind.OTHER, "无扩展名"))
    }

    @Test
    fun `PDF 与 DOCX 保留原扩展名`() {
        assertEquals("pdf", ChatAttachmentPreparer.extensionFor(AttachmentKind.PDF, "a.pdf"))
        assertEquals("docx", ChatAttachmentPreparer.extensionFor(AttachmentKind.DOCX, "a.docx"))
    }
}
