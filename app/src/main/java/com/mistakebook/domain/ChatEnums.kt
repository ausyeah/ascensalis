package com.mistakebook.domain

/**
 * 对话相关的枚举。
 *
 * 全部入库存 `name`（见 [com.mistakebook.data.local.Converters]），
 * 而不是 `ordinal`——`ordinal` 一旦调整顺序，历史数据全部错位且无从察觉。
 */

/** 消息说话方。 */
enum class ChatRole {
    USER,
    ASSISTANT,

    /**
     * 系统插入的分隔条。
     *
     * 目前用于「已省略 N 条早期对话」——上下文超预算时在正文里显式告诉用户，
     * 避免他以为 AI 突然失忆。**这类消息不发给模型**，只是给人看。
     */
    SYSTEM
}

/**
 * 消息状态。
 *
 * 流式回复期间先落一条 [STREAMING] 的行，边收边更新同一条；
 * 正常结束改 [DONE]，出错改 [FAILED]，用户中断改 [CANCELED]。
 *
 * 这样做的原因：**一条用户消息只对应一条助手消息**，
 * 不需要靠「最后一条是不是空的」去猜状态——那种写法在重进页面时必然出错。
 */
enum class MessageStatus {
    /** 正在流式接收，content 会持续增长。 */
    STREAMING,
    DONE,
    FAILED,

    /** 用户主动停止，或页面退出时仍在流式——**不该算失败**。 */
    CANCELED
}

/** 附件类型。 */
enum class AttachmentKind {
    /** 图片：压缩后以 base64 data URL 走 `image_url`。 */
    IMAGE,

    /** PDF / TXT / MD / DOCX：本地抽文本拼进 prompt，**不上传**。 */
    PDF,
    TEXT,
    DOCX,

    /**
     * 资料库文件（v0.0.58）：用户 @资料库 选中、本轮注入过上下文的文件。
     *
     * 只为**在对话历史里可见**而存在——不参与构造请求（原文已由服务
     * 注入 system 上下文，见 `ChatGenerationService` 的 extraContext）。
     * 用户反馈「勾选了资料库没有显示具体文件，在对话历史里」：
     * 以前注入是一次性的，发完就查无此物。
     */
    LIBRARY,
    /**
     * Excel 表格（xlsx；老版 xls 走同一分支但会给「请另存为」提示）。
     *
     * 抽成以 `\t` 分列、`\n` 分行的文本，见 [com.mistakebook.data.chat.XlsxText]。
     * v0.0.57 新增——此前 xlsx 归 OTHER，界面显示就绪但模型收不到任何内容。
     */
    XLSX,

    /** 认不出的类型：仍可在 UI 上显示文件名，但不参与构造请求。 */
    OTHER
}

/**
 * 附件处理状态。
 *
 * 与 [MessageStatus] 分开是因为**粒度不同**：一条消息可以带多个附件，
 * 其中图片好了、PDF 抽文本失败是正常情况，不该让整条消息失败。
 */
enum class AttachmentStatus {
    /** 压缩 / 抽文本进行中。 */
    PREPARING,

    /** 可用：图片已生成 data URL，或文本已落盘。 */
    READY,

    /** 处理失败，[com.mistakebook.data.local.entities.ChatAttachment.errorMessage] 有原因。 */
    FAILED
}
