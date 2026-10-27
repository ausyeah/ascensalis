package com.mistakebook.data.chat

/**
 * 把 MinerU 的 markdown 产物整理成**可以喂给文本模型**的上下文。
 *
 * ## 为什么要有这个
 *
 * 用户接的大模型不一定是多模态的（DeepSeek-chat、Qwen-plus 都看不见图）。
 * 但本 App 已经接了 MinerU——它能把图片里的文字、公式逐字抽出来。
 * 于是「给不识图的模型发图」就有了一条可行路径：
 * **先让 MinerU 读图，再把读出来的文字喂给模型。**
 *
 * 纯函数、无 Android 依赖，可 JVM 单测。
 */
object ChatOcrText {

    /**
     * 上下文里给图片文字加的标题。
     *
     * 模型需要知道这段文字**是图片的内容**，而不是用户自己打的话——
     * 否则它会以为用户在描述图片（「这道题如图所示」），答非所问。
     */
    const val IMAGE_TEXT_HEADER = "【图片中的文字内容（由识图工具提取）】"

    /** 单张图片最多注入多少字符。 */
    const val MAX_IMAGE_TEXT_CHARS = 8_000

    /**
     * 整理 MinerU 的 markdown 产物。
     *
     * 做三件事：
     * 1. **剥掉图片引用**（`![](xxx.jpg)`）——文本模型看不见图，
     *    留着只会让它以为「这里应该有一张图」，反而胡猜。
     * 2. **把残留的图片路径清掉**——MinerU 有时会把绝对路径写进正文，
     *    那是本机路径，发给模型既无意义又泄露信息。
     * 3. **截断**到 [MAX_IMAGE_TEXT_CHARS]，防止超长内容撑爆上下文预算。
     *
     * @return 整理后的文本；空则返回空串（调用方据此判断「这张图没识别出东西」）
     */
    fun prepare(markdown: String): String {
        if (markdown.isBlank()) return ""
        val stripped = IMAGE_REF.replace(markdown, "")
        val cleaned = ABSOLUTE_PATH.replace(stripped, "").trim()
        if (cleaned.isBlank()) return ""
        return if (cleaned.length <= MAX_IMAGE_TEXT_CHARS) {
            cleaned
        } else {
            cleaned.take(MAX_IMAGE_TEXT_CHARS) + "\n…（图片文字过长，已截断）"
        }
    }

    /**
     * 拼出最终注入到用户消息里的那段文字。
     *
     * @param prepared [prepare] 的结果，或 [failureNote] 的失败说明
     * @return 带头部的完整段落；[prepared] 为空时返回空串
     */
    fun section(prepared: String): String {
        if (prepared.isBlank()) return ""
        // 失败说明换一个头：模型需要知道「这是张没能读出来的图」，
        // 而不是「图里的字就是这个」——否则它会拿着失败说明当题目内容分析。
        val header = if (isFailureNote(prepared)) IMAGE_FAILED_HEADER else IMAGE_TEXT_HEADER
        return "$header\n$prepared"
    }

    // ------------------------------------------------------------ 失败说明

    /**
     * 识别失败时写进附件 `textExcerpt` 的说明前缀。
     *
     * ## 为什么失败也要落库
     *
     * 磁盘缓存只对**识别成功**的图生效。失败若不落任何东西，
     * 这张图就永远是「没有 OCR 文字」——用户每发一句话，
     * 它就被重新上传一次，一句话烧一次 MinerU 额度。
     *
     * 落一条说明同时解决三件事：
     * 1. **不再重传**（有文字了，不进待识别队列）；
     * 2. **模型不再失明**——它明确知道「这里有一张图，但没能读出内容」，
     *    可以如实让用户补充文字，而不是对着空气瞎猜；
     * 3. **用户看得见原因**（通知条 + 这条说明）。
     *
     * 想重试：通知条上的「重试」会把这些说明清掉重新排队。
     */
    const val FAILURE_NOTE_PREFIX = "（这张图片未能识别出文字）"

    /** 失败说明在上下文里的头部。 */
    const val IMAGE_FAILED_HEADER = "【图片识别失败】"

    /** 拼一条失败说明；原因可空。 */
    fun failureNote(reason: String): String =
        if (reason.isBlank()) FAILURE_NOTE_PREFIX else "$FAILURE_NOTE_PREFIX：$reason"

    /** 这段文字是不是失败说明（据此决定重试时清不清、上下文里用什么头）。 */
    fun isFailureNote(text: String): Boolean = text.startsWith(FAILURE_NOTE_PREFIX)

    /** markdown 图片引用：`![alt](path)`。 */
    private val IMAGE_REF = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")

    /**
     * 残留的绝对路径（形如 `/data/user/0/.../xxx.jpg`）。
     *
     * 刻意只删「以 / 开头且含图片扩展名」的那一段，
     * 而不是所有斜杠——LaTeX 里的 `\frac` 之类不能误伤。
     */
    private val ABSOLUTE_PATH = Regex("(?m)\\S*/[\\w\\-. ]+\\.(?:jpg|jpeg|png|webp|bmp|gif)")
}
