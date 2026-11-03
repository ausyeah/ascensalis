package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话中央空状态出场条件 [showChatEmptyState] 的边界单测（v0.0.90）。
 *
 * 纯 JVM：判定只是「读完了 && 没消息」，不依赖 Android。
 *
 * 钉住的行为：加载中不许出场。进会话要先读数据库，这几百毫秒里
 * 消息数同样是 0——不挡这一下，用户会看见鲸鱼闪一下再跳回「加载中」。
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
}
