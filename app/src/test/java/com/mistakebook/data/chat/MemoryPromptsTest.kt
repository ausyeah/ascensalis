package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆库的注入与解析（v0.0.108）。
 *
 * 这一层是纯函数，所以能被穷举钉住；而它恰恰是最容易「悄悄变差」的地方——
 * 注入多一句、少一句，界面上一模一样，只有模型的回答会慢慢跑偏。
 */
class MemoryPromptsTest {

    @Test
    fun `没有记忆时不注入空标题`() {
        assertEquals("", MemoryPrompts.injectBlock(emptyList()))
        assertEquals("", MemoryPrompts.injectBlock(listOf("", "   ")))
    }

    @Test
    fun `注入块必须限制模型复述记忆`() {
        val block = MemoryPrompts.injectBlock(listOf("用户是通信工程大四学生"))
        // 少了这句，模型会自然地念出「记得你住在武汉…」，用户只会觉得被监视
        assertTrue("必须明确禁止复述记忆", block.contains("不要在回答里复述或提及这份清单"))
        // 记忆是旧快照，人会说变就变
        assertTrue("冲突时以当前对话为准", block.contains("与记忆冲突时，以当前对话为准"))
        assertTrue("无关条目直接忽略", block.contains("不要牵强关联"))
        assertTrue("条目本身要在", block.contains("- 用户是通信工程大四学生"))
    }

    @Test
    fun `注入有上限`() {
        val many = (1..80).map { "第 $it 条记忆" }
        val block = MemoryPrompts.injectBlock(many)
        val items = block.lines().filter { it.startsWith("- ") && !it.startsWith("- 只在") && !it.startsWith("- 与当前") && !it.startsWith("- 用户") && !it.startsWith("- 记忆") }
        assertTrue("条数不能超过上限", items.size <= MemoryPrompts.MAX_INJECT_ITEMS)
        assertEquals("只保留最近的那些（列表头部）", "第 1 条记忆", items.first().removePrefix("- "))

        val long = (1..30).map { "很长的记忆条目" + "字".repeat(90) }
        val chars = MemoryPrompts.injectBlock(long).lines()
            .filter { it.startsWith("- ") }
            .sumOf { it.length }
        assertTrue("注入总量要有字数上限，否则每轮白烧 token", chars <= MemoryPrompts.MAX_INJECT_CHARS + 200)
    }

    @Test
    fun `只解析减号开头的条目`() {
        val raw = """
            以下是一些建议记住的内容：
            - 用户是通信工程大四学生
            • 用户在准备考研
            * 用户偏好繁体中文
            1. 这条是编号，不是条目
            这条没有前缀，也不是条目
        """.trimIndent()
        assertEquals(
            listOf("用户是通信工程大四学生", "用户在准备考研", "用户偏好繁体中文"),
            MemoryPrompts.parseCandidates(raw)
        )
    }

    @Test
    fun `解析时裁掉过长的条目`() {
        val long = "字".repeat(MemoryPrompts.MAX_ITEM_CHARS + 50)
        val parsed = MemoryPrompts.parseCandidates("- $long")
        assertEquals(MemoryPrompts.MAX_ITEM_CHARS, parsed.single().length)
    }

    @Test
    fun `只保留关于用户的陈述`() {
        // 实测踩过的坑：不加这层过滤，模型会把助手界面的提示语
        // （「**描述这张图片**：比如…」）当成用户信息写进记忆库
        val raw = listOf(
            "**描述这张图片**：比如「这是什么游戏的角色？」",
            "**提出相关问题**：比如「帮我分析这个角色的设计特点」",
            "用户是通信工程大四学生",
            "用户偏好简洁的回答"
        )
        assertEquals(
            listOf("用户是通信工程大四学生", "用户偏好简洁的回答"),
            MemoryPrompts.onlyAboutUser(raw)
        )
    }

    @Test
    fun `整理时明确要求只输出新条目`() {
        val prompt = MemoryPrompts.extractPrompt(
            transcript = "用户：我在准备考研",
            existing = listOf("用户是通信工程大四学生")
        )
        assertTrue(prompt.contains("只输出新条目"))
        assertTrue("已有记忆要列出来让它避开", prompt.contains("- 用户是通信工程大四学生"))
        assertTrue("要求每条以「用户」开头", prompt.contains("必须以「用户」开头"))
        assertTrue(prompt.contains("用户：我在准备考研"))
    }

    @Test
    fun `去重忽略空白标点与大小写`() {
        val existing = listOf("用户 偏好 繁体中文", "User prefers English.")
        val candidates = listOf(
            "用户偏好繁体中文",          // 只差空白
            "USER PREFERS ENGLISH",     // 只差大小写与标点
            "用户在准备考研"             // 新条目
        )
        assertEquals(
            listOf("用户在准备考研"),
            MemoryPrompts.removeDuplicates(candidates, existing)
        )
    }

    @Test
    fun `同一批里重复的也只留一条`() {
        val fresh = MemoryPrompts.removeDuplicates(
            listOf("用户喜欢简洁的回答", "用户喜欢简洁的回答"),
            emptyList()
        )
        assertEquals(1, fresh.size)
    }

    @Test
    fun `空条目永远不会进库`() {
        assertEquals(emptyList<String>(), MemoryPrompts.removeDuplicates(listOf("", "   "), emptyList()))
        assertFalse(MemoryPrompts.normalize("，。！").isNotEmpty())
    }
}