package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考用时累计（v0.0.153，学 deepseek-ai/chat-demo 的
 * 「已思考（用时 N 秒）」）。
 *
 * 关键场景是**工具循环**：思考 → 搜索（十几秒）→ 再思考 → 作答。
 * 「已思考」若是首尾差，搜索时间会被算进思考里；所以按段累计——
 * 第一条思考开表、正文 / 工具 / 流结束停表，多轮累加。
 */
class ThinkingDurationTest {

    private fun state() = ToolRoundState(nowMs = { clock })
    private var clock = 0L

    @Test
    fun `单段思考用时等于首尾差`() {
        val rounds = state()
        rounds.markThinkingStart()   // t=0
        clock = 4_000
        rounds.onThinking("想")
        rounds.markThinkingStop()    // t=4000
        assertEquals(4_000L, rounds.thinkingMillis)
    }

    @Test
    fun `工具循环的多段思考累加`() {
        val rounds = state()
        // 第一轮思考 2s
        rounds.markThinkingStart(); clock = 2_000; rounds.onThinking("a")
        rounds.markThinkingStop()
        // 搜索 10s（不计时）
        clock = 12_000
        rounds.onDelta("")
        // 第二轮思考 3s
        rounds.markThinkingStart(); clock = 15_000; rounds.onThinking("b")
        rounds.markThinkingStop()    // t=15000
        assertEquals(5_000L, rounds.thinkingMillis)
    }

    @Test
    fun `重复开表不重置起点`() {
        val rounds = state()
        rounds.markThinkingStart()   // t=0
        clock = 1_000
        rounds.onThinking("a")
        rounds.markThinkingStart()   // 已在计时，应忽略
        clock = 3_000
        rounds.markThinkingStop()
        assertEquals(3_000L, rounds.thinkingMillis)
    }

    @Test
    fun `流结束时仍在思考也算到结束为止`() {
        val rounds = state()
        rounds.markThinkingStart()   // t=0
        clock = 6_000
        rounds.onThinking("想")
        // 没有显式 stop：Ended 处理会调 markThinkingStop（见 ChatGenerationService）
        assertEquals(0L, rounds.thinkingMillis)
        rounds.markThinkingStop()
        assertEquals(6_000L, rounds.thinkingMillis)
    }

    @Test
    fun `没有思考就没有用时`() {
        val rounds = state()
        clock = 5_000
        rounds.onDelta("答案")
        rounds.markThinkingStop()
        assertEquals(0L, rounds.thinkingMillis)
        assertTrue(rounds.thinking.isBlank())
        assertFalse(rounds.answer.isBlank())
    }
}
