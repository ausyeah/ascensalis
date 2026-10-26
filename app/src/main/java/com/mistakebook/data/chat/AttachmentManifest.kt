package com.mistakebook.data.chat

import com.mistakebook.data.local.entities.ChatAttachment
import com.mistakebook.domain.AttachmentKind

/**
 * 附件清单：**看不见图片的模型**怎么知道用户发了文件。
 *
 * ## 为什么需要它（v0.0.49）
 *
 * 用户反馈：「不可识图的模型根本接收不到文件」。
 *
 * 拆开看是三件事叠在一起：
 * 1. 图片本体（base64 data URL）在模型不识图时被丢掉——这是对的，
 *    发了也只会得到「不支持图片」的报错；
 * 2. OCR 文字只在「没配识图模型」时才预塞——于是配了识图模型时，
 *    模型既看不到图、也拿不到文字；
 * 3. **模型根本不知道这条消息里有过附件**——没有附件清单，
 *    它就不会想到去调 describe_image / ocr_image。
 *
 * 第 3 条是关键：工具再齐全，模型不知道要用就等于没有。
 * 清单把「发过什么、能不能读、该调哪个工具」讲清楚，
 * 模型据此自己决定要不要读。
 *
 * **纯逻辑、无 Android 依赖，可 JVM 单测。**
 */
object AttachmentManifest {

    /** 清单的抬头。模型需要认出这是「附件说明」而不是用户自己说的话。 */
    const val HEADER = "【附件清单】"

    /**
     * 生成附件清单。
     *
     * @param visionToolAvailable 是否提供了识图工具（describe_image）。
     * @param ocrToolAvailable 是否提供了 OCR 工具（ocr_image）。
     */
    fun build(
        attachments: List<ChatAttachment>,
        visionToolAvailable: Boolean,
        ocrToolAvailable: Boolean
    ): String = buildString {
        append(HEADER)
        append("用户随这条消息发送了 ${attachments.size} 个附件：\n")
        attachments.forEach { attachment ->
            val name = attachment.fileName.ifBlank { "附件" }
            when (attachment.kind) {
                AttachmentKind.IMAGE -> {
                    append("- 图片：$name")
                    if (attachment.textExcerpt.isNotBlank()) {
                        append("（图片文字已提取，见上文）")
                    } else if (visionToolAvailable) {
                        append("（你无法直接查看图片；需要内容请调用 describe_image 工具识图）")
                    } else if (ocrToolAvailable) {
                        append("（你无法直接查看图片；需要内容请调用 ocr_image 工具做 OCR）")
                    } else {
                        append("（你无法直接查看图片，且当前没有可用的识图/OCR 工具）")
                    }
                    append('\n')
                }

                else -> {
                    append("- 文件：$name")
                    if (attachment.textExcerpt.isBlank()) {
                        append("（未能提取到正文）")
                    } else {
                        append("（正文已提取，见上文）")
                    }
                    append('\n')
                }
            }
        }
    }
}
