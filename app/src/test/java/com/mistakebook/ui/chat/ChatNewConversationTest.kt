package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顶栏「新对话」落点判定 [newConversationTarget] 的边界单测（v0.0.90）。
 *
 * 纯 JVM：判定只是一个 Long 比大小，不依赖 Android。
 *
 * 钉住的行为：点「新对话」要么换到另一条会话，要么原地不动——
 * 绝不能把同一个地址 pop 出来再 push 回去（返回栈白翻一层，界面毫无变化）。
 */
class ChatNewConversationTest {

    @Test
    fun `落到另一条会话`() {
        // 常态：当前是题目会话（或无会话），解析出的是一条自由会话
        assertEquals(7L, newConversationTarget(currentSessionId = null, resolvedSessionId = 7L))
        assertEquals(7L, newConversationTarget(currentSessionId = 3L, resolvedSessionId = 7L))
    }

    @Test
    fun `解析结果就是当前会话时哪儿也不去`() {
        // 用户已经待在一条空白的自由会话里：openFreeSession 复用了它，
        // 再导航一次只是把同一个路由地址翻来翻去
        assertNull(newConversationTarget(currentSessionId = 7L, resolvedSessionId = 7L))
    }

    @Test
    fun `当前没有会话时任何解析结果都是新落点`() {
        // 题目会话的 sessionId 是 null（NavHost 只在给了 id 时才传）
        assertEquals(1L, newConversationTarget(currentSessionId = null, resolvedSessionId = 1L))
        assertEquals(0L, newConversationTarget(currentSessionId = null, resolvedSessionId = 0L))
    }
}
