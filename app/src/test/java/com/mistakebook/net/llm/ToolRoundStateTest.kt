package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具循环跨轮累积的语义测试。
 *
 * 对应 v0.0.43 用户反馈「模型调用搜索之后就断输出」：
 * 一条助手消息跨多个 API 轮次，UI/DB 视角必须连续（跨轮累积），
 * API 视角的 echo 只回传本轮（DeepSeek reasoning_content 协议）。
 */
class ToolRoundStateTest {

    @Test
    fun singleRoundAccumulates() {
        val s = ToolRoundState()
        s.onThinking("想")
        s.onDelta("答")
        assertEquals("想", s.thinking)
        assertEquals("答", s.answer)
        assertEquals("想", s.roundThinking)
        assertEquals("答", s.roundAnswer)
    }

    @Test
    fun crossRoundKeepsEarlierContent() {
        val s = ToolRoundState()
        // 第一轮：开场白 + 思考，然后模型调了 web_search
        s.onThinking("先想想")
        s.onDelta("我来搜索一下。")
        s.beginNextRound()
        // 第二轮：搜索回来的最终答案
        s.onThinking("再看看结果")
        s.onDelta("根据搜索结果，答案是 42。")

        // UI/DB 视角：两轮拼接，第一轮的开场白不能丢
        assertEquals("先想想再看看结果", s.thinking)
        assertEquals("我来搜索一下。根据搜索结果，答案是 42。", s.answer)
    }

    @Test
    fun echoCarriesOnlyCurrentRound() {
        val s = ToolRoundState()
        s.onThinking("第一轮思考")
        s.onDelta("第一轮正文")
        s.beginNextRound()
        s.onThinking("第二轮思考")
        s.onDelta("第二轮正文")

        // API 视角：echo 只带本轮（DeepSeek：每条 assistant 消息带自己的 reasoning_content）
        assertEquals("第二轮思考", s.roundThinking)
        assertEquals("第二轮正文", s.roundAnswer)
    }

    @Test
    fun beginNextRoundResetsRoundBuffersOnly() {
        val s = ToolRoundState()
        s.onDelta("甲")
        s.onThinking("乙")
        s.beginNextRound()
        assertEquals("", s.roundAnswer)
        assertEquals("", s.roundThinking)
        // 跨轮累积不受影响
        assertEquals("甲", s.answer)
        assertEquals("乙", s.thinking)
    }

    @Test
    fun emptyTextIgnored() {
        val s = ToolRoundState()
        s.onDelta("")
        s.onThinking("")
        assertEquals("", s.answer)
        assertEquals("", s.thinking)
    }

    @Test
    fun multiRoundThinkingAccumulatesInOrder() {
        val s = ToolRoundState()
        s.onThinking("轮1")
        s.beginNextRound()
        s.onThinking("轮2")
        s.beginNextRound()
        s.onThinking("轮3")
        assertEquals("轮1轮2轮3", s.thinking)
        assertEquals("轮3", s.roundThinking)
    }

    @Test
    fun needsReasoningEchoOfficialEndpoint() {
        assertTrue(
            ToolLoopPolicy.needsReasoningEcho(
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat"
            )
        )
        assertTrue(
            ToolLoopPolicy.needsReasoningEcho(
                baseUrl = "https://api.deepseek.com",
                model = "anything"
            )
        )
    }

    @Test
    fun needsReasoningEchoRelayServingDeepseekModel() {
        // 中转站：baseUrl 不含 deepseek，但模型名是 deepseek 系
        assertTrue(
            ToolLoopPolicy.needsReasoningEcho(
                baseUrl = "https://relay.example.com/v1",
                model = "deepseek-v3.2"
            )
        )
    }

    @Test
    fun needsReasoningEchoFalseForOtherChannels() {
        assertFalse(
            ToolLoopPolicy.needsReasoningEcho(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o"
            )
        )
        assertFalse(
            ToolLoopPolicy.needsReasoningEcho(
                baseUrl = "https://dashscope.aliyuncs.com/v1",
                model = "qwen-max"
            )
        )
    }
}
