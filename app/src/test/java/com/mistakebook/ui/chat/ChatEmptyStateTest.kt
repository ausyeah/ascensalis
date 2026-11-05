package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话中央空状态出场条件 [showChatEmptyState] 的边界单测（v0.0.90）。
 *
 * 纯 JVM：判定只是「读完了 && 没消息」，不依赖 Android。
 *
 * 钉住的行为：加载中不许出场。进会话要先读数据库，这几百毫秒里
 * 消息数同样是 0——不挡这一下，用户会看见标志闪一下再跳回「加载中」。
 */
class ChatEmptyStateTest {

    @Test
    fun `读完了且一条消息都没有时出场`() {
        assertTrue(showChatEmptyState(loading = false, messageCount = 0))
    }

    @Test
    fun `加载中不出场哪怕消息数为零`() {
        assertFalse(showChatEmptyState(loading = true, messageCount = 0))
    }

    @Test
    fun `有任何消息就不出场`() {
        assertFalse(showChatEmptyState(loading = false, messageCount = 1))
        assertFalse(showChatEmptyState(loading = false, messageCount = 30))
    }

    @Test
    fun `加载中且有消息同样不出场`() {
        // 回到已有会话：消息先到、loading 后翻 false，两种组合都不能闪空状态
        assertFalse(showChatEmptyState(loading = true, messageCount = 2))
    }

    /**
     * v0.0.121（issue #34）：调用点改传全部消息数。
     *
     * 用户报「注入上下文的题目第一时间没有显示出来，而是回应之后才加载出来」：
     * 题目上下文是注入消息，不计入可见数——原来调用点传 visibleMessageCount，
     * 一进题目会话空状态就盖住整页，题目卡要等模型回应后才出现。
     * 纯函数本身不变；这里钉住调用点必须数**全部**消息。
     */
    @Test
    fun `调用点数的是全部消息而不是可见消息`() {
        val screen = readSource("src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue(
            "空状态判定必须传 state.messages.size（注入消息也算内容）",
            screen.contains("showChatEmptyState(state.loading, state.messages.size)")
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}
