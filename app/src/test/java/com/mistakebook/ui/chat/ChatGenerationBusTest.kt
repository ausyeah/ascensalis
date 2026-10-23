package com.mistakebook.ui.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 后台生成状态总线的回归测试。
 *
 * 总线是「前台服务 ↔ 界面」之间唯一的实时通道：
 * 服务写、界面读。它必须满足两条：
 * 1. 增量内容能按序发出（界面据此做打字机效果）；
 * 2. 终态带齐「截断 / 失败 / 输入 token」这几个界面要用的字段。
 */
class ChatGenerationBusTest {

    @Test
    fun `初始没有快照`() = runBlocking {
        ChatGenerationBus.clear()
        assertNull(ChatGenerationBus.snapshot.first())
    }

    @Test
    fun `发布增量后能读到最新内容`() = runBlocking {
        ChatGenerationBus.clear()
        ChatGenerationBus.publish(ChatGenerationBus.Snapshot(assistantId = 7L, content = "半句"))
        val snap = ChatGenerationBus.snapshot.first()
        assertEquals(7L, snap?.assistantId)
        assertEquals("半句", snap?.content)
        assertEquals(false, snap?.finished)
    }

    @Test
    fun `终态带齐截断与失败标记`() = runBlocking {
        ChatGenerationBus.clear()
        ChatGenerationBus.publish(
            ChatGenerationBus.Snapshot(
                assistantId = 9L,
                content = "完整回答",
                finished = true,
                promptTokens = 1234,
                truncated = true,
                failed = false
            )
        )
        val snap = ChatGenerationBus.snapshot.first()
        assertEquals(true, snap?.finished)
        assertEquals(1234, snap?.promptTokens)
        assertEquals(true, snap?.truncated)
        assertEquals(false, snap?.failed)
    }

    @Test
    fun `失败时带错误文案`() = runBlocking {
        ChatGenerationBus.clear()
        ChatGenerationBus.publish(
            ChatGenerationBus.Snapshot(
                assistantId = 11L,
                finished = true,
                failed = true,
                errorText = "服务不可用"
            )
        )
        val snap = ChatGenerationBus.snapshot.first()
        assertEquals(true, snap?.failed)
        assertEquals("服务不可用", snap?.errorText)
    }

    @Test
    fun `clear 之后回到空`() = runBlocking {
        ChatGenerationBus.publish(ChatGenerationBus.Snapshot(assistantId = 1L, content = "x"))
        ChatGenerationBus.clear()
        assertNull(ChatGenerationBus.snapshot.first())
    }
}
