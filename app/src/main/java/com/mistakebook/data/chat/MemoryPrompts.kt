package com.mistakebook.data.chat

/**
 * 记忆库的提示词与注入（v0.0.112，单文档模型）。
 *
 * 记忆是**一篇分节的文档**（个人背景 / 学习情况 / 当前关注 / 偏好…），
 * 不是一条条孤立的短句：模型改写时能前后照应（把「用户在准备考研」和
 * 「用户每天只背 20 个单词」合并进同一节），注入时也少一层拼装。
 *
 * ## 注入怎么优化（参考 ChatGPT / Claude 的记忆做法）
 *
 * 1. **记忆是「背景」，不是「话题」**：必须显式告诉模型别在回答里复述这份记忆。
 *    不加这句话，模型会很自然地把记忆当资料念出来，用户只会觉得被监视。
 * 2. **与当前对话冲突时以当前为准**：记忆是旧快照，人会说变就变。
 * 3. **必须有上限**：文档越长，模型越容易在无关问题上把记忆硬扯进来，
 *    而且每轮都白烧 token。超出上限**从尾部截**——文档按小节组织，
 *    尾部通常是补充性的细枝末节。
 */
object MemoryPrompts {

    /** 注入的字数上限（中文字符近似 token 的 1.5 倍，4000 字 ≈ 6k token 封顶）。 */
    const val MAX_INJECT_CHARS = 4000

    /** 改写产物的字数上限：超过说明模型跑题开始写文章了，弃用。 */
    const val MAX_DOC_CHARS = 6000

    /** 注入用的标题行。 */
    const val HEADER = "【关于用户的长期记忆】"

    /**
     * 拼给模型的记忆块。[doc] 为空返回空串：**没有记忆就不要注入**，
     * 一个空标题只会让模型以为上下文缺了东西。
     */
    fun injectDocument(doc: String): String {
        val usable = doc.trim()
        if (usable.isEmpty()) return ""
        val body = if (usable.length > MAX_INJECT_CHARS) {
            usable.take(MAX_INJECT_CHARS) + "\n…（超出部分已截断）"
        } else {
            usable
        }
        return buildString {
            append(HEADER).append('\n')
            append("下面是你对这位用户的长期了解。它是背景，不是话题：\n")
            append("- 只在确实能让回答更贴合时使用，不要在回答里复述或提及这份记忆；\n")
            append("- 与当前问题无关的内容直接忽略，不要牵强关联；\n")
            append("- 用户这次说的话与记忆冲突时，以当前对话为准；\n")
            append("- 记忆不改变回答的语言与格式要求。\n\n")
            append(body)
        }
    }

    /** 改写任务的 system 提示词（记住/忘记请求与整理共用一套格式约定）。 */
    val REWRITE_SYSTEM: String = """
        你是用户的长期记忆管理器。你会收到「当前记忆」和一条「请求」，
        你的任务是输出一份**全新的完整记忆文档**。

        文档格式约定：
        - 按主题分节，节标题独占一行、用纯文字（如：个人背景、学习情况、当前关注、偏好）；
        - 每节里用「- 」开头的短句条目，一条一个事实，不超过 40 字；
        - 只记长期有效的事实（身份、目标、偏好、约束、正在进行的事），
          不记一次性的具体问题和闲聊；
        - 内容相互矛盾的保留较新的说法，重复的合并成一条；
        - 请求要求遗忘的内容连同相关条目整段删除。

        输出要求：**直接输出改写后的完整文档**，不要任何解释、
        不要「好的」「以下是」之类的开场白；如果改写后为空，只输出「（空）」。
    """.trimIndent()

    /** 改写请求的 user 消息：当前全文 + 用户的请求。 */
    fun rewriteUserPrompt(currentDoc: String, request: String): String = buildString {
        append("【当前记忆】\n")
        if (currentDoc.isBlank()) append("（还没有任何记忆）\n") else append(currentDoc.trim()).append('\n')
        append("\n【请求】\n")
        append(request.trim())
    }

    /** 整理请求：不改含义，只把现有内容重排成分节条目的清晰形式。 */
    const val ORGANIZE_REQUEST: String =
        "把上面的记忆整理成一份结构清晰的分节条目式文档：合并重复与相似条目，" +
            "按主题分节，每节用「- 」短句；不改任何事实本身，也不要新增内容。" +
            "若当前记忆为空，输出「（空）」。"
}
