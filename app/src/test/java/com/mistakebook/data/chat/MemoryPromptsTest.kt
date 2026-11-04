package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆文档的注入与改写提示词（v0.0.112，单文档模型）。
 *
 * 这一层是纯函数，所以能被穷举钉住；而它恰恰是最容易「悄悄变差」的地方——
 * 注入多一句、少一句，界面上一模一样，只有模型的回答会慢慢跑偏。
 */
class MemoryPromptsTest {

    @Test
    fun `没有记忆时不注入空标题`() {
        assertEquals("", MemoryPrompts.injectDocument(""))
        assertEquals("", MemoryPrompts.injectDocument("   \n  "))
    }

    @Test
    fun `注入块必须限制模型复述记忆`() {
        val block = MemoryPrompts.injectDocument("个人背景\n- 用户是通信工程大四学生")
        // 少了这句，模型会自然地念出「记得你住在武汉…」，用户只会觉得被监视
        assertTrue("必须明确禁止复述记忆", block.contains("不要在回答里复述或提及这份记忆"))
        // 记忆是旧快照，人会说变就变
        assertTrue("冲突时以当前对话为准", block.contains("与记忆冲突时，以当前对话为准"))
        assertTrue("无关内容直接忽略", block.contains("不要牵强关联"))
        // 文档正文要原样进注入块
        assertTrue(block.contains("- 用户是通信工程大四学生"))
    }

    @Test
    fun `注入有字数上限且从尾部截`() {
        val doc = buildString {
            repeat(600) { append("第一节内容第 $it 条。") }
        }
        val block = MemoryPrompts.injectDocument(doc)
        assertTrue(
            "注入总量要有上限，否则每轮白烧 token",
            block.length <= MemoryPrompts.MAX_INJECT_CHARS + 200
        )
        assertTrue("截断要有标记", block.contains("超出部分已截断"))
    }

    @Test
    fun `改写系统提示词要求输出完整文档`() {
        val system = MemoryPrompts.REWRITE_SYSTEM
        assertTrue("要说明输出的是完整文档", system.contains("全新的完整记忆文档"))
        assertTrue("要约定分节条目格式", system.contains("开头的短句条目"))
        assertTrue("要禁止客套开场白", system.contains("不要任何解释"))
        // 「（空）」是清空记忆的约定输出，必须钉住
        assertTrue(system.contains("（空）"))
    }

    @Test
    fun `改写请求带当前全文与用户请求`() {
        val prompt = MemoryPrompts.rewriteUserPrompt(
            currentDoc = "个人背景\n- 用户是通信工程大四学生",
            request = "记住：我叫Ausyeah"
        )
        assertTrue("当前记忆要给全", prompt.contains("- 用户是通信工程大四学生"))
        assertTrue("用户的请求要原样带上", prompt.contains("记住：我叫Ausyeah"))
        // 空文档也要有明确的占位说明，模型才知道是从零建
        val empty = MemoryPrompts.rewriteUserPrompt("", "记住：我叫Ausyeah")
        assertTrue(empty.contains("（还没有任何记忆）"))
    }

    @Test
    fun `整理请求不改事实只重排`() {
        assertTrue(MemoryPrompts.ORGANIZE_REQUEST.contains("不改任何事实本身"))
        assertTrue(MemoryPrompts.ORGANIZE_REQUEST.contains("合并重复"))
    }
}
