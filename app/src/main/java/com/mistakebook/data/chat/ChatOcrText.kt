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
    const val IMAGE_TEXT_HEADER = "【图片中的文字内容（由 MinerU 自动识别）】"

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
     * @param prepared [prepare] 的结果
     * @return 带头部的完整段落；[prepared] 为空时返回空串
     */
    fun section(prepared: String): String {
        if (prepared.isBlank()) return ""
        return "$IMAGE_TEXT_HEADER\n$prepared"
    }

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
