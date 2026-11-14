package com.mistakebook.ui.chat

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考过程与正文的拆分测试。
 *
 * 三个字段**共用一列**，全靠 `<think>` 标记区分，所以标记处理错了就是
 * 「思考混进正文」（用户复制出去的东西莫名其妙）或「空气泡」。
 */
class ChatThinkingTest {

    private fun split(raw: String, streaming: Boolean = false) = ChatThinking.split(raw, streaming)

    // ------------------------------------------------------------ 基本

    @Test
    fun `没有标记时全部是正文`() {
        assertEquals("" to "答案是 42", split("答案是 42"))
    }

    @Test
    fun `只有思考没有正文`() {
        // 推理模型拒答前只思考不回答——这时必须显示思考，不能显示空气泡
        val raw = "\n<think>让我想想，这题超纲了</think>"
        assertEquals("让我想想，这题超纲了" to "", split(raw))
    }

    @Test
    fun `思考在前正文在后`() {
        val raw = "\n<think>先求导</think>\n\n所以答案是 0"
        assertEquals("先求导" to "所以答案是 0", split(raw))
    }

    @Test
    fun `结果能原样拼回去`() {
        // 反向用例：拼不回去就意味着「存进去的和显示出来的不一样」
        val raw = ChatThinking.tags("思考中…") + "\n\n最终答案"
        assertEquals("思考中…", ChatThinking.thinkingOf(raw))
        assertEquals("最终答案", ChatThinking.answerOf(raw))
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `没有闭合标记时整段都算思考`() {
        // 模型中途断流，标记只写了一半。
        // 宁可多显示成思考，也不能让思考混进正文——正文是可以被复制走的，
        // 混进去会让复制出来的东西莫名其妙。
        // 显式传 streaming=true：断流就是流式中，此时后面确实还是思考
        val (thinking, answer) = split("\n<think>想了半天还没想完", streaming = true)
        assertEquals("想了半天还没想完", thinking)
        assertEquals("", answer)
    }

    @Test
    fun `空标记不产生空思考块`() {
        assertEquals("" to "正文", split("\n<think>\n</think>\n\n正文"))
    }

    @Test
    fun `正文里的尖括号不受影响`() {
        val raw = "\n<think>比较 x < 2</think>\n\n当 x < 2 时成立"
        assertEquals("比较 x < 2", ChatThinking.thinkingOf(raw))
        assertEquals("当 x < 2 时成立", ChatThinking.answerOf(raw))
    }

    @Test
    fun `正文里含 think 字样不被误判`() {
        val raw = "我觉得应该 I think 一下再决定"
        assertEquals("" to "我觉得应该 I think 一下再决定", split(raw))
    }

    @Test
    fun `答案里含美元符号不影响`() {
        // 公式本身就用 $ 分隔，这里必须用 ${'$'} 转义，否则 Kotlin 会把它当模板
        val dollar = "$"
        val raw = "\n<think>用 ${dollar}x${dollar} 表示未知数</think>\n\n${dollar} x = 5 ${dollar}"
        assertEquals("用 ${dollar}x${dollar} 表示未知数", ChatThinking.thinkingOf(raw))
        assertEquals("${dollar} x = 5 ${dollar}", ChatThinking.answerOf(raw))
    }

    @Test
    fun `多对标记全部合并`() {
        // 原来这条测试要求「取第一个」，理由是「流式写入时可能重复追加」。
        //
        // 但用户截图证明那样是错的：真实输出里有**好几对**标记，
        // 只拆第一对的话，后面的标记会原样留在正文里显示给用户。
        //
        // 所以改成全部合并：所有思考都保留（用户能看到完整推理），
        // 所有标记都不泄漏。
        val raw = "\n<think>思考</think>正文\n\n<think>又是思考</think>"
        val (thinking, answer) = split(raw)
        assertTrue("第一段思考要保留", thinking.contains("思考"))
        assertTrue("第二段思考也要保留", thinking.contains("又是思考"))
        assertTrue("答案里不该丢内容", answer.contains("正文"))
        assertFalse("正文不该有字面量标记", answer.contains("<think>"))
        assertFalse("正文不该有字面量标记", answer.contains("</think>"))
    }

    @Test
    fun `纯空白正文被 trim 掉`() {
        val (thinking, answer) = split("\n<think>思考</think>\n\n\n")
        assertEquals("思考", thinking)
        assertEquals("", answer)
    }

    // ------------------------------------------------------------ tags

    @Test
    fun `tags 给裸文本套上标记`() {
        assertEquals("\n<think>思考</think>", ChatThinking.tags("思考"))
    }

    @Test
    fun `tags 不给已带标记的内容套两层`() {
        // 模型自己吐了 <think> 时再套一层，界面上会出现「思考」里又有个「思考」
        val already = "\n<think>思考</think>"
        assertEquals(already, ChatThinking.tags(already))
        assertTrue(ChatThinking.isWrapped(already))
    }

    @Test
    fun `空文本套标记后拆回来还是空`() {
        assertEquals("" to "", split(ChatThinking.tags("")))
    }

    @Test
    fun `isWrapped 能识别两种形态`() {
        assertTrue(ChatThinking.isWrapped("\n<think>x</think>"))
        assertTrue(ChatThinking.isWrapped("<think>x</think>"))
        assertFalse(ChatThinking.isWrapped("普通文本"))
    }

    // ---- v0.0.153：思考用时存进开标记 data-ms 属性 ----

    @Test
    fun `带用时的包裹能读回用时`() {
        val raw = ChatThinking.tags("想一想", durationMs = 12_345L)
        assertEquals(12_345L, ChatThinking.durationOf(raw))
        // 拆分语义不受属性影响
        assertEquals("想一想", ChatThinking.thinkingOf(raw))
        assertEquals("", ChatThinking.answerOf(raw))
    }

    @Test
    fun `普通包裹读不到用时返回 null`() {
        val raw = ChatThinking.tags("想一想")
        assertNull(ChatThinking.durationOf(raw))
    }

    @Test
    fun `已有标记的内容加时长不重复包裹`() {
        val raw = ChatThinking.tags("想一想")
        assertEquals(raw, ChatThinking.tags(raw, durationMs = 500L))
        assertNull(ChatThinking.durationOf(raw))
    }

    @Test
    fun `thinking 与正文混排时用时仍可读`() {
        val raw = ChatThinking.tags("想一想", durationMs = 12_000L) + "\n\n答案"
        assertEquals(12_000L, ChatThinking.durationOf(raw))
        assertEquals("答案", ChatThinking.answerOf(raw))
    }
}
