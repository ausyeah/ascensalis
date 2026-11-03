package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入栏主按钮角色与可用性（v0.0.90）的边界单测。
 *
 * 纯 JVM：两个函数都只是布尔判断，不依赖 Android。
 *
 * 钉住的是用户点名的那条：**流式中输入框空着才显示停止，打了字就得是发送**
 * ——用来 steer 当前输出。旧实现只看 streaming，想插话必须先停止再发送。
 */
class ChatPrimaryActionTest {

    @Test
    fun `没在生成时永远是发送`() {
        assertEquals(ChatPrimaryAction.SEND, chatPrimaryAction(streaming = false, hasInput = false))
        assertEquals(ChatPrimaryAction.SEND, chatPrimaryAction(streaming = false, hasInput = true))
    }

    @Test
    fun `生成中且输入框空着是停止`() {
        assertEquals(ChatPrimaryAction.STOP, chatPrimaryAction(streaming = true, hasInput = false))
    }

    @Test
    fun `生成中且打了字是 steer`() {
        // 这一条是新行为：发送按钮插进正在进行的输出，改方向不用先停
        assertEquals(ChatPrimaryAction.STEER, chatPrimaryAction(streaming = true, hasInput = true))
    }

    @Test
    fun `停止与 steer 永远可点`() {
        assertTrue(chatPrimaryEnabled(ChatPrimaryAction.STOP, hasInput = false, hasReadyAttachment = false))
        assertTrue(chatPrimaryEnabled(ChatPrimaryAction.STEER, hasInput = false, hasReadyAttachment = false))
    }

    @Test
    fun `普通发送没内容就点不动`() {
        assertFalse(chatPrimaryEnabled(ChatPrimaryAction.SEND, hasInput = false, hasReadyAttachment = false))
    }

    @Test
    fun `普通发送有字或有就绪附件就能点`() {
        assertTrue(chatPrimaryEnabled(ChatPrimaryAction.SEND, hasInput = true, hasReadyAttachment = false))
        // 只带了图没打字也要能发（附件本身就是内容）
        assertTrue(chatPrimaryEnabled(ChatPrimaryAction.SEND, hasInput = false, hasReadyAttachment = true))
    }
}
