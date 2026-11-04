package com.mistakebook.data.chat

/**
 * 记忆库的提示词与解析（v0.0.108）。
 *
 * ## 注入怎么优化（参考 ChatGPT / Claude 的记忆做法）
 *
 * 1. **记忆是「背景」，不是「话题」**：必须显式告诉模型别在回答里复述这份清单。
 *    不加这句话，模型会很自然地把记忆当资料念出来（「记得你住在武汉，那么…」），
 *    用户只会觉得被监视。
 * 2. **与当前对话冲突时以当前为准**：记忆是旧快照，人会说变就变。
 * 3. **必须有上限**：条数越多，模型越容易在无关问题上把记忆硬扯进来，
 *    而且每轮都白烧 token。超出上限按「最近更新」保留，丢掉最旧的——旧的
 *    大概率已经被更新的条目覆盖了。
 * 4. **一条一句**：短句可以被单独删除、单独更新；整段散文做不到。
 */
object MemoryPrompts {

    /** 注入上限：条数与字数都卡住，防止记忆库越长越跑偏。 */
    const val MAX_INJECT_ITEMS = 40
    const val MAX_INJECT_CHARS = 1800

    /** 单条记忆的长度上限（用户手填时自动截断，模型整理时也会被裁）。 */
    const val MAX_ITEM_CHARS = 120

    /** 注入用的标题行。 */
    const val HEADER = "【关于用户的长期记忆】"

    /**
     * 拼给模型的记忆块。[items] 按调用方给的顺序（通常是最近更新在前）。
     *
     * 空列表返回空串：**没有记忆就不要注入**，一个空标题只会让模型以为
     * 上下文缺了东西。
     */
    fun injectBlock(items: List<String>): String {
        val usable = items
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_INJECT_ITEMS)
            .fold(mutableListOf<String>()) { acc, item ->
                val used = acc.sumOf { it.length + 3 }
                if (used + item.length <= MAX_INJECT_CHARS) acc.add(item)
                acc
            }
        if (usable.isEmpty()) return ""

        return buildString {
            append(HEADER).append('\n')
            append("下面是你对这位用户的长期了解。它是背景，不是话题：\n")
            append("- 只在确实能让回答更贴合时使用，不要在回答里复述或提及这份清单；\n")
            append("- 与当前问题无关的条目直接忽略，不要牵强关联；\n")
            append("- 用户这次说的话与记忆冲突时，以当前对话为准；\n")
            append("- 记忆不改变回答的语言与格式要求。\n\n")
            usable.forEach { append("- ").append(it).append('\n') }
        }.trimEnd()
    }

    /**
     * 让模型从最近的对话里整理记忆。
     *
     * 只输出**新的**条目（已有的明确列出来让它避开），格式要求纯短句、
     * 一行一条，解析才不需要猜。
     */
    fun extractPrompt(transcript: String, existing: List<String>): String = buildString {
        append("你在整理一位用户与 AI 助手的长期记忆。下面是最近的一些对话片段。\n")
        append("任务：挑出**值得长期记住**的信息，写成一行一条的短句。\n\n")
        append("值得记的：用户的身份与背景、长期偏好（语言/格式/风格）、")
        append("正在做的项目、明显的学习目标或薄弱点、反复出现的约束。\n")
        append("不该记的：一次性的具体问题、闲聊、临时的情绪、")
        append("能从记忆里推出来的重复内容、任何猜测。\n\n")
        append("要求：\n")
        append("- 每条一句话，不超过 40 个字，**必须以「用户」开头**（如「用户偏好繁体中文」）；\n")
        append("- 不是关于用户本人的内容一律不输出（界面提示语、助手说过的话、具体的题目答案都不算）；\n")
        append("- 只输出确实有依据的，宁缺毋滥；一条都挑不出来就只输出「无」；\n")
        append("- 只输出新条目，格式为每行以「- 」开头，不要编号、不要解释、不要标题。\n\n")
        if (existing.isNotEmpty()) {
            append("已经记住的内容（不要重复）：\n")
            existing.take(MAX_INJECT_ITEMS).forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("对话片段：\n")
        append(transcript.trim())
    }

    /**
     * 解析模型输出里的条目前缀。
     *
     * 只认 `-` / `•` / `*` 开头的行：模型偶尔会加标题或解释，
     * 那些行不像条目，收进来就是污染记忆库。
     */
    fun parseCandidates(raw: String): List<String> = raw.lineSequence()
        .map { it.trim() }
        .filter { line -> line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ") }
        .map { it.drop(2).trim() }
        .map { it.take(MAX_ITEM_CHARS) }
        .filter { it.isNotEmpty() }
        .toList()

    /**
     * 只保留「关于用户」的陈述（v0.0.108）。
     *
     * 实测教训：不加这层过滤，模型会把助手的界面提示语（「**描述这张图片**：
     * 比如…」）当成记忆写进库——那些是 App 自己的文案，记下来只会污染每一轮
     * 的上下文。开头的「用户」就是这层过滤最省事的判据，提示词里也要求了。
     */
    fun onlyAboutUser(candidates: List<String>): List<String> =
        candidates.filter { it.startsWith("用户") }

    /**
     * 去掉与已有记忆重复的条目（也去掉这批内部互相重复的）。
     *
     * 归一化只做「空白与常见标点」：`用户 偏好 繁体中文` 和
     * `用户偏好繁体中文` 是同一条，这种差别不该在记忆库里出现两遍。
     */
    fun removeDuplicates(candidates: List<String>, existing: List<String>): List<String> {
        val seen = existing.mapTo(mutableSetOf()) { normalize(it) }
        return candidates.filter { candidate ->
            val key = normalize(candidate)
            if (key.isEmpty() || key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    /** 归一化：去空白、去标点、小写。 */
    internal fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}