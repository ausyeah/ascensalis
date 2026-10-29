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
        ocrToolAvailable: Boolean,
        /**
         * 图片本体是否**已经随这条请求发给模型**了（当前模型标记为支持识图）。
         *
         * v0.0.56 新增。原先清单只在「模型不识图」时才拼，于是
         * `supportsVision` 一旦被误标为 true（它默认就是 true！），模型既拿不到
         * 图（data URL 转换失败 / 服务端不收）也拿不到清单和工具，只能回一句
         * 「我没有看图的工具」。现在清单**总是**拼，并如实说明图片是否已随
         * 请求发出、读不到时该调哪个工具。
         */
        imagesAttached: Boolean = false
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
                    } else {
                        // v0.0.57：先说明图片是否已经发出，再按**实际可用的工具**
                        // 给降级建议。早先只要 imagesAttached=true 就无脑写
                        // 「可调用 describe_image 识图、ocr_image 取文字」——
                        // 工具根本没配时等于让模型去调不存在的工具，它只能回
                        // 「我没有看图的工具」。
                        append(if (imagesAttached) "（图片已随消息发送" else "（你无法直接查看图片")
                        val tools = buildList {
                            if (visionToolAvailable) add("describe_image")
                            if (ocrToolAvailable) add("ocr_image")
                        }
                        when {
                            tools.isEmpty() -> append("，且当前没有可用的识图/OCR 工具")
                            imagesAttached ->
                                append("；若你读不到图片内容，可调用 ")
                                    .append(tools.joinToString(" / "))
                            else ->
                                append("；需要内容请调用 ")
                                    .append(tools.joinToString(" / "))
                        }
                        append("）")
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
