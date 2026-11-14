package com.mistakebook.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯等待态判定（v0.0.155）。
 *
 * 用户截图：等待三点与底部的流式输出指示器**上下同时渲染**——
 * 两个「生成中」动画叠在一个气泡里。修复后两者互斥：
 * 纯等待态（思考 / 正文 / 阶段提示都还没有）只显示等待三点，
 * 一旦有任何内容开始流出，就交还给流式指示器。
 */
class WaitingVisibilityTest {

    @Test
    fun `纯等待态只显示等待三点`() {
        assertTrue(
            waitingBeforeContent(
                streaming = true,
                thinkingBlank = true,
                answerBlank = true,
                stage = null
            )
        )
    }

    @Test
    fun `思考开始后交给流式指示器`() {
        assertFalse(
            waitingBeforeContent(
                streaming = true,
                thinkingBlank = false,
                answerBlank = true,
                stage = null
            )
        )
    }

    @Test
    fun `正文开始后交给流式指示器`() {
        assertFalse(
            waitingBeforeContent(
                streaming = true,
                thinkingBlank = true,
                answerBlank = false,
                stage = null
            )
        )
    }

    @Test
    fun `有阶段提示就不算纯等待`() {
        assertFalse(
            waitingBeforeContent(
                streaming = true,
                thinkingBlank = true,
                answerBlank = true,
                stage = "正在联网搜索…"
            )
        )
    }

    @Test
    fun `非流式一律不是等待态`() {
        assertFalse(
            waitingBeforeContent(
                streaming = false,
                thinkingBlank = true,
                answerBlank = true,
                stage = null
            )
        )
    }
}
