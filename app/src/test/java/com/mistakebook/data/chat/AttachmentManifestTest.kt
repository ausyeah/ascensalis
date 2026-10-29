package com.mistakebook.data.chat

import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.domain.AttachmentKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件清单单测。
 *
 * 防的是「不可识图的模型根本接收不到文件」这个回归：
 * 清单必须说清**发过什么**、**能不能读**、**该调哪个工具**。
 * 少了任何一条，模型要么不知道有附件，要么不知道该用哪个工具。
 */
class AttachmentManifestTest {

    private fun image(
        name: String = "shot.jpg",
        excerpt: String = ""
    ) = ChatAttachment(
        messageId = 1L,
        sessionId = 1L,
        kind = AttachmentKind.IMAGE,
        localPath = "/tmp/$name",
        fileName = name,
        mimeType = "image/jpeg",
        status = com.mistakebook.domain.AttachmentStatus.READY,
        textExcerpt = excerpt,
        createdAt = 0L
    )

    private fun doc(
        name: String = "notes.pdf",
        excerpt: String = "正文内容"
    ) = ChatAttachment(
        messageId = 1L,
        sessionId = 1L,
        kind = AttachmentKind.PDF,
        localPath = "/tmp/$name",
        fileName = name,
        mimeType = "application/pdf",
        status = com.mistakebook.domain.AttachmentStatus.READY,
        textExcerpt = excerpt,
        createdAt = 0L
    )

    @Test
    fun `清单说明发了几个附件并列出文件名`() {
        val text = AttachmentManifest.build(
            attachments = listOf(image(), doc()),
            visionToolAvailable = false,
            ocrToolAvailable = false
        )
        assertTrue(text.contains(AttachmentManifest.HEADER))
        assertTrue(text.contains("2 个附件"))
        assertTrue(text.contains("shot.jpg"))
        assertTrue(text.contains("notes.pdf"))
    }

    @Test
    fun `有识图工具时提示模型调用 describe_image`() {
        val text = AttachmentManifest.build(
            attachments = listOf(image()),
            visionToolAvailable = true,
            ocrToolAvailable = false
        )
        assertTrue(text.contains("describe_image"))
    }

    @Test
    fun `只有 OCR 工具时提示模型调用 ocr_image`() {
        val text = AttachmentManifest.build(
            attachments = listOf(image()),
            visionToolAvailable = false,
            ocrToolAvailable = true
        )
        assertTrue(text.contains("ocr_image"))
        assertTrue(!text.contains("describe_image"))
    }

    @Test
    fun `没有任何工具时如实说明读不到图`() {
        val text = AttachmentManifest.build(
            attachments = listOf(image()),
            visionToolAvailable = false,
            ocrToolAvailable = false
        )
        assertTrue(text.contains("没有可用的识图/OCR 工具"))
    }

    @Test
    fun `图片已有文字时不再让模型去调工具`() {
        val text = AttachmentManifest.build(
            attachments = listOf(image(excerpt = "【图片中的文字内容】题目正文")),
            visionToolAvailable = true,
            ocrToolAvailable = true
        )
        assertTrue(text.contains("图片文字已提取"))
        assertTrue(!text.contains("describe_image"))
    }

    @Test
    fun `文档抽不到正文时如实说明`() {
        val text = AttachmentManifest.build(
            attachments = listOf(doc(excerpt = "")),
            visionToolAvailable = true,
            ocrToolAvailable = true
        )
        assertTrue(text.contains("未能提取到正文"))
    }

    // ---- v0.0.57：图片确实随请求发出时也要给降级提示 ----

    @Test
    fun `图片已随消息发送时仍告诉模型读不到可以调工具`() {
        // 用户反馈「大模型说自己没有看图的工具」：supportsVision 默认 true，
        // 图片 data URL 确实发了，但服务端/模型其实读不到。清单必须说明
        // 「已随消息发送」并给出降级工具，模型才不会断言「我没有看图工具」。
        val text = AttachmentManifest.build(
            attachments = listOf(image()),
            visionToolAvailable = true,
            ocrToolAvailable = true,
            imagesAttached = true
        )
        assertTrue(text.contains("图片已随消息发送"))
        assertTrue(text.contains("describe_image"))
        assertTrue(text.contains("ocr_image"))
    }

    @Test
    fun `图片已发送但没有工具时不给不存在的工具`() {
        val text = AttachmentManifest.build(
            attachments = listOf(image()),
            visionToolAvailable = false,
            ocrToolAvailable = false,
            imagesAttached = true
        )
        assertTrue(text.contains("图片已随消息发送"))
        assertTrue(text.contains("没有可用的识图/OCR 工具"))
    }
}
