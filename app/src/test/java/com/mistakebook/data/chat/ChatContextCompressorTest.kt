package com.mistakebook.data.chat

import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文压缩纯逻辑的测试。
 *
 * 钉住四件事：
 * 1. 最近 N 条保留原文，不被压进摘要；
 * 2. 摘要消息自身不参与转录（否则旧摘要被复制进新提示词，越滚越长）；
 * 3. 旧摘要进提示词时带「合并更新」要求（滚动摘要的关键）；
 * 4. 历史不够长时返回空——短会话不压缩。
 */
class ChatContextCompressorTest {

    private fun msg(role: ChatRole, content: String, id: Long = 0L) = ChatMessage(
        id = id,
        sessionId = 1L,
        role = role,
        content = content,
        status = MessageStatus.DONE,
        createdAt = id,
        updatedAt = id
    )

    private fun summaryMsg(content: String, id: Long = 99L) =
        msg(ChatRole.SYSTEM, content, id)

    @Test
    fun `最近六条保留原文`() {
        val messages = (1..10).map { msg(ChatRole.USER, "第 $it 条", it.toLong()) }
        val picked = CompressorLogic.pickToSummarize(messages, keepRecent = 6)
        assertEquals(4, picked.size)
        assertEquals("第 1 条", picked.first().content)
        assertEquals("第 4 条", picked.last().content)
    }

    @Test
    fun `摘要消息自身不进转录`() {
        val messages = listOf(
            summaryMsg(ChatContextAssembler.SUMMARY_PREFIX + "旧结论"),
            msg(ChatRole.USER, "问题一", 1),
            msg(ChatRole.ASSISTANT, "回答一", 2),
            msg(ChatRole.USER, "问题二", 3),
            msg(ChatRole.ASSISTANT, "回答二", 4),
            msg(ChatRole.USER, "问题三", 5),
            msg(ChatRole.ASSISTANT, "回答三", 6),
            msg(ChatRole.USER, "问题四", 7),
            msg(ChatRole.ASSISTANT, "回答四", 8)
        )
        val picked = CompressorLogic.pickToSummarize(messages, keepRecent = 6)
        // 9 条里 1 条是旧摘要 → 候选 8 条 → 留最近 6 → 压 2 条
        assertEquals(2, picked.size)
        assertEquals("问题一", picked[0].content)
        assertFalse(picked.any { it.role == ChatRole.SYSTEM })
    }

    @Test
    fun `历史不够长时不压缩`() {
        val messages = (1..6).map { msg(ChatRole.USER, "第 $it 条", it.toLong()) }
        assertTrue(CompressorLogic.pickToSummarize(messages, keepRecent = 6).isEmpty())
    }

    @Test
    fun `转录带角色前缀`() {
        val transcript = CompressorLogic.buildTranscript(
            listOf(msg(ChatRole.USER, "这道题怎么做"), msg(ChatRole.ASSISTANT, "先求导")),
            maxMessageChars = 100
        )
        assertTrue(transcript.startsWith("用户：这道题怎么做"))
        assertTrue(transcript.contains("助手：先求导"))
    }

    @Test
    fun `超长单条被截断`() {
        val long = "x".repeat(500)
        val transcript = CompressorLogic.buildTranscript(
            listOf(msg(ChatRole.USER, long)),
            maxMessageChars = 100
        )
        // 100 字内容 + "用户：" 前缀
        assertEquals(103, transcript.length)
    }

    @Test
    fun `有旧摘要时提示词要求合并更新`() {
        val prompt = CompressorLogic.buildPrompt(
            oldSummary = ChatContextAssembler.SUMMARY_PREFIX + "第二轮结论：答案为 0",
            transcript = "用户：追问",
            summaryMaxChars = 400
        )
        assertTrue(prompt.contains("在其基础上合并更新"))
        assertTrue(prompt.contains("第二轮结论：答案为 0"))
        // 前缀不能被带进提示词，否则模型会学着输出前缀
        assertFalse(prompt.contains("【上文摘要】"))
    }

    @Test
    fun `没有旧摘要时提示词不提合并`() {
        val prompt = CompressorLogic.buildPrompt(null, "用户：第一问", 400)
        assertFalse(prompt.contains("合并更新"))
        assertTrue(prompt.contains("用户：第一问"))
    }

    @Test
    fun `空串旧摘要等同没有`() {
        val prompt = CompressorLogic.buildPrompt("   ", "用户：第一问", 400)
        assertFalse(prompt.contains("合并更新"))
    }
}
