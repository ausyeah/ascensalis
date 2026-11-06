package com.mistakebook.data.chat

import kotlinx.serialization.json.contentOrNull

/**
 * 记忆文档的合并与「该不该自动提取」的判定（纯函数，v0.0.128，issue #42）。
 *
 * 为什么不走 [com.mistakebook.data.repos.MemoryRepository] 的整篇重写：
 * 那是「用户明确要求改写」的路径（一次完整 LLM 调用 + 全文重排版），
 * 对话里随手记一条却付这个代价太贵，而且模型重写有可能把既有条目改掉。
 * 这里只做三件事：去重、追加、按上限裁剪——可单测、无副作用。
 */
object MemoryMerge {

    /** 自动追加的那一节标题（与人工整理的分节并存，一眼能看出是对话里记的）。 */
    const val AUTO_SECTION = "## 对话里记下的"

    /** 与 [MemoryPrompts.MAX_DOC_CHARS] 同口径：超了就裁掉最早的自记条目。 */
    private const val MAX_CHARS = 6000

    /** 文档里已有的条目（`- ` 开头的行，去掉前缀与空白）。 */
    fun bullets(doc: String): List<String> = doc.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /**
     * 把 [facts] 合并进 [doc]。
     *
     * - 已在文档里出现过的条目（忽略全半角空格与大小写差异）直接跳过；
     * - 其余追加到 [AUTO_SECTION] 底下，没有这一节就新建；
     * - 超长时从**自动节的最早条目**开始删，人工写的分节不动。
     *
     * @return 合并后的全文；没有任何新条目时原样返回（调用方据此判断是否要写盘）。
     */
    fun merge(doc: String, facts: List<String>): String {
        val existing = bullets(doc).map(::normalize).toSet()
        val fresh = facts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { normalize(it) !in existing }
            .distinctBy(::normalize)
        if (fresh.isEmpty()) return doc

        val builder = StringBuilder(doc.trimEnd())
        val hasSection = doc.lineSequence().any { it.trim() == AUTO_SECTION }
        if (!hasSection) {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(AUTO_SECTION).append('\n')
        } else {
            // 追加到该节末尾：找到节标题位置，把新条目插在它那一段的末尾
            val lines = builder.toString().split('\n').toMutableList()
            val sectionIndex = lines.indexOfFirst { it.trim() == AUTO_SECTION }
            var insertAt = lines.size
            if (sectionIndex >= 0) {
                insertAt = lines.size
                for (i in sectionIndex + 1 until lines.size) {
                    if (lines[i].trimStart().startsWith("#")) {
                        insertAt = i
                        break
                    }
                }
            }
            val newLines = fresh.map { "- $it" }
            lines.addAll(insertAt, newLines)
            return trimToLimit(lines.joinToString("\n"))
        }
        fresh.forEach { builder.append("- ").append(it).append('\n') }
        return trimToLimit(builder.toString())
    }

    /** 超上限时从自动节里的最早条目开始删；自动节删空了就整体截尾。 */
    private fun trimToLimit(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val lines = text.split('\n').toMutableList()
        val sectionIndex = lines.indexOfFirst { it.trim() == AUTO_SECTION }
        if (sectionIndex < 0) return text.take(MAX_CHARS)
        // 自动节范围：[sectionIndex+1, 下一个标题)
        var end = lines.size
        for (i in sectionIndex + 1 until lines.size) {
            if (lines[i].trimStart().startsWith("#")) { end = i; break }
        }
        var current = text
        var from = sectionIndex + 1
        while (current.length > MAX_CHARS && from < end && from < lines.size) {
            if (lines[from].trimStart().startsWith("- ")) {
                lines.removeAt(from)
                end--
                current = lines.joinToString("\n")
            } else {
                from++
            }
        }
        return if (current.length > MAX_CHARS) current.take(MAX_CHARS) else current
    }

    private fun normalize(fact: String): String =
        fact.lowercase().replace(" ", "").replace("\u3000", "").trim()
}

/**
 * 自动提取的触发闸门（issue #42 第二半：不只是有工具，还要会自己记）。
 *
 * ## 为什么要有闸门
 *
 * 「每轮对话都让模型总结一次记忆」= 每轮多一次 LLM 调用，长会话成本翻倍，
 * 而且绝大多数轮次没有可记的信息。用一条**便宜的本地启发式**先筛：
 * 用户这句话里出现「身份/目标/偏好/约束」的典型措辞，才值得花一次调用。
 *
 * 宁可漏记也不滥记：漏了用户还能用工具或手动补，滥记会把记忆库塞满噪音，
 * 而噪音又会进每一轮的 system prompt。
 */
object MemoryAutoExtract {

    /** 触发词：用户透露长期信息的典型说法。 */
    private val CUES = listOf(
        "我叫", "我是", "我在", "我的目标", "我准备", "我打算", "我想考",
        "我喜欢", "我习惯", "我偏好", "我讨厌", "别给", "以后都", "以后请",
        "记住", "记一下", "别忘了", "每天", "每周", "长期", "考研", "高考",
        "大一", "大二", "大三", "大四", "研一", "研二", "我的专业", "我的学校"
    )

    /** 用户这句话是否可能含长期信息。 */
    fun shouldAttempt(userText: String): Boolean {
        val text = userText.trim()
        if (text.length < 4) return false
        return CUES.any { text.contains(it) }
    }

    /**
     * 解析模型返回的条目：每行一条，允许 `- ` 前缀、允许 JSON 数组。
     * 空行、以「无」/「没有」开头的行丢弃。
     */
    fun parseFacts(raw: String): List<String> {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val jsonArray = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
                .let { it as? kotlinx.serialization.json.JsonArray }
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        val items = jsonArray ?: text.lineSequence().toList()
        return items
            .map {
                it.trim()
                    .removePrefix("-")
                    .removePrefix("•")
                    .replace(Regex("""^\d+[.、)]\s*"""), "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("无") && !it.startsWith("没有") }
            .filter { it.length <= 60 }
            .distinct()
            .take(5)
    }
}