package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结束原因标记（v0.0.154，学 deepseek-ai/chat-demo 的 canContinueReply）。
 *
 * `finish_reason=length` 意味着内容确定不完整，且「继续生成」接得出来——
 * 标记落 content 尾部，显示 / 复制路径统一剥离。此前徽标只活在本次
 * 会话的内存里，重启后残缺回答与完整回答一模一样，用户无从判断。
 */
class ChatFinishTest {

    @Test
    fun `追加标记并可判定`() {
        val raw = ChatThinking.tags("回答") + "\n\n正文" + ChatFinish.LENGTH_MARKER
        assertTrue(ChatFinish.isLengthTruncated(raw))
    }

    @Test
    fun `重复追加不叠加`() {
        val once = "回答" + ChatFinish.LENGTH_MARKER
        assertEquals(once, ChatFinish.appendLengthMarker(once))
    }

    @Test
    fun `剥离标记不伤正文`() {
        val raw = ChatThinking.tags("思考") + "\n\n正文最后一行" + ChatFinish.LENGTH_MARKER
        val cleaned = ChatFinish.strip(raw)
        assertTrue(cleaned.contains("正文最后一行"))
        assertFalse(cleaned.contains("finish-reason"))
    }

    @Test
    fun `split 出的正文不含标记`() {
        // 显示 / 复制都走 ChatThinking.split——标记必须在这里就被剥掉
        val raw = ChatThinking.tags("想一想") + "\n\n答案" + ChatFinish.LENGTH_MARKER
        assertEquals("答案", ChatThinking.answerOf(raw))
        assertEquals("想一想", ChatThinking.thinkingOf(raw))
    }

    @Test
    fun `thinkingOf 也不含标记`() {
        val raw = ChatThinking.tags("想一想" + ChatFinish.LENGTH_MARKER)
        assertFalse(ChatThinking.thinkingOf(raw).contains("finish-reason"))
    }

    @Test
    fun `正常结束的消息没有标记`() {
        assertFalse(ChatFinish.isLengthTruncated("正常回答"))
    }
}
