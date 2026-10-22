package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 流的收尾时机。
 *
 * ## 用户报告
 *
 * > 输出结束了还是终止图标
 *
 * 截图里内容、表格全都渲染完了，右下角却还是「停止」方块。
 *
 * ## 为什么
 *
 * 那个图标由 `ChatUiState.isStreaming` 决定，而它只在
 * `ChatViewModel.collectStream` 的 `finally` 里被置回 `false`。
 * 所以链路是：
 *
 * ```
 * collect 不返回 → finally 不跑 → isStreaming 一直 true → 图标一直是「停止」
 * ```
 *
 * 早先只有两条收尾路径：`data: [DONE]` 哨兵、读到 EOF。
 * 实测有些服务端**两条都不给**——发完最后一帧（`finish_reason` 已带）
 * 就把连接挂着，既不发哨兵也不关。下一行 `readUtf8Line()` 于是永久阻塞。
 *
 * ## 测试怎么模拟「挂住」
 *
 * [drainSse] 把读行做成注入的 lambda。测试里给它一个**再被调用就抛异常**的源：
 * 真的挂住时读到这里就会炸，而不是安静地返回、把 bug 放过去。
 */
class SseDrainTest {

    /** 正常收尾的最后一帧。`usage` 是**顶层**字段，不在 choices 里。 */
    private fun terminalFrame(finishReason: String, usage: Boolean = false): String {
        val u = if (usage) ""","usage":{"prompt_tokens":11,"completion_tokens":22}""" else ""
        return """data:{"choices":[{"delta":{},"finish_reason":"$finishReason"}]$u}"""
    }

    private fun deltaFrame(text: String): String =
        """data:{"choices":[{"delta":{"content":"$text"}}]}"""

    /**
     * 把若干行喂进去；**行用完之后再读就抛异常**，
     * 代表「服务端不吭声地挂着连接」。
     */
    private fun source(vararg lines: String): () -> String? {
        var i = 0
        return {
            if (i < lines.size) lines[i++] else error("流已结束却还在读下一行——这就是用户遇到的卡死")
        }
    }

    /**
     * 同上，但行用完之后返回 `null`（EOF）而不是抛异常——
     * 用来测「服务端不发哨兵、直接关连接」这条老路径。
     *
     * 和 [source] 的区别很重要：空行**不等于** EOF，
     * `drainSse` 会跳过空行继续读，所以拿空行当 EOF 会误报成卡死。
     */
    private fun sourceEndingWithEof(vararg lines: String): () -> String? {
        var i = 0
        return { if (i < lines.size) lines[i++] else null }
    }

    // ------------------------------------------------------------------
    // 一、核心回归：finish_reason 之后不许再多读一行
    // ------------------------------------------------------------------

    @Test
    fun `finish_reason 之后立即收尾不再多读`() {
        val result = drainSse(
            readLine = source(
                deltaFrame("你好"),
                deltaFrame("世界"),
                terminalFrame("stop")
            )
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals("stop", result.finishReason)
    }

    /**
     * 用户截图那次的形态：**没有 `[DONE]`，连接也不关**。
     *
     * 如果实现还在等哨兵，这里就会因为多读一行而抛异常——测试变红。
     */
    @Test
    fun `不发哨兵也不关连接时靠 finish_reason 收尾`() {
        val deltas = ArrayList<String>()
        val result = drainSse(
            readLine = source(
                deltaFrame("| 场景 | 耗时 |"),
                deltaFrame("| --- | --- |"),
                deltaFrame("| 单条简单公式 | < 50ms |"),
                terminalFrame("stop")
            ),
            onDelta = { deltas.add(it) }
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(3, deltas.size)
    }

    // ------------------------------------------------------------------
    // 二、另外两条老路径不能被改坏
    // ------------------------------------------------------------------

    @Test
    fun `哨兵路径照旧`() {
        val result = drainSse(
            readLine = source(deltaFrame("你好"), "data: [DONE]")
        )
        assertEquals("done", result.endedBy)
    }

    @Test
    fun `EOF 路径照旧`() {
        val deltas = ArrayList<String>()
        val result = drainSse(
            readLine = sourceEndingWithEof(deltaFrame("你好"), deltaFrame("世界")),
            onDelta = { deltas.add(it) }
        )
        assertEquals("eof", result.endedBy)
        assertEquals(2, deltas.size)
    }

    /**
     * 中转站常不发 `finish_reason`，只发哨兵。
     * 这种情况必须靠哨兵/EOF 收尾，不能因为「没等到 finish_reason」而当成异常。
     */
    @Test
    fun `全程没有 finish_reason 也能正常结束`() {
        val result = drainSse(readLine = source(deltaFrame("只有正文"), "data: [DONE]"))
        assertNull(result.finishReason)
        assertEquals("done", result.endedBy)
    }

    // ------------------------------------------------------------------
    // 三、usage 与截断标记
    // ------------------------------------------------------------------

    /** 尾帧带 usage 时要能取到，否则 token 数会存成 0。 */
    @Test
    fun `收尾帧携带的 usage 要被记下`() {
        val result = drainSse(
            readLine = source(deltaFrame("正文"), terminalFrame("stop", usage = true))
        )
        assertEquals(11, result.promptTokens)
        assertEquals(22, result.completionTokens)
    }

    /** usage 也可能单独出现在后续帧里，取最后一个非空值。 */
    @Test
    fun `分帧携带的 usage 取最后一个非空值`() {
        val result = drainSse(
            readLine = source(
                deltaFrame("正文"),
                """data:{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":7}}""",
                terminalFrame("stop")
            )
        )
        assertEquals(5, result.promptTokens)
        assertEquals(7, result.completionTokens)
    }

    /**
     * `length` = 被 max_tokens 截断，界面靠它挂「已截断」标记。
     * 提前收尾不能把这个信号丢掉。
     */
    @Test
    fun `被截断的 finish_reason 要原样透出`() {
        val result = drainSse(readLine = source(deltaFrame("半句话"), terminalFrame("length")))
        assertEquals("length", result.finishReason)
        assertEquals("finish_reason", result.endedBy)
    }

    // ------------------------------------------------------------------
    // 四、坏帧与噪声行不能打断收尾
    // ------------------------------------------------------------------

    @Test
    fun `坏帧不中断流`() {
        val deltas = ArrayList<String>()
        val result = drainSse(
            readLine = source(
                deltaFrame("第一段"),
                "data:{这不是合法 JSON",
                deltaFrame("第二段"),
                terminalFrame("stop")
            ),
            onDelta = { deltas.add(it) }
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(2, deltas.size)
    }

    @Test
    fun `注释行与空行被跳过`() {
        val deltas = ArrayList<String>()
        val result = drainSse(
            readLine = source(
                ": keep-alive",
                "",
                deltaFrame("正文"),
                ": keep-alive",
                terminalFrame("stop")
            ),
            onDelta = { deltas.add(it) }
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(listOf("正文"), deltas)
    }

    /** 空正文但有思考内容时，思考要照常送出去。 */
    @Test
    fun `思考内容照常透出`() {
        val thinking = ArrayList<String>()
        drainSse(
            readLine = source(
                """data:{"choices":[{"delta":{"reasoning_content":"先想想"}}]}""",
                terminalFrame("stop")
            ),
            onThinking = { thinking.add(it) }
        )
        assertEquals(listOf("先想想"), thinking)
    }

    // ------------------------------------------------------------------
    // 六、finish 之后的 usage 帧补读（token 用量不为 0 的来源）
    // ------------------------------------------------------------------

    /**
     * `stream_options.include_usage` 生效时的规范形态：
     * finish 帧之后还有一个 usage 帧（`choices` 为空），然后才是 `[DONE]`。
     *
     * 补读不到它，token 用量就永远是 0——用户反馈「次数有了但具体量都是 0」。
     */
    @Test
    fun `finish 之后的 usage 帧被补读合并`() {
        val result = drainSse(
            readLine = source(deltaFrame("正文"), terminalFrame("stop")),
            afterFinish = source(
                """data:{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":22,"prompt_cache_hit_tokens":5}}""",
                "data: [DONE]"
            )
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(11, result.promptTokens)
        assertEquals(22, result.completionTokens)
        assertEquals(5, result.cachedTokens)
    }

    /**
     * 服务端**挂着不发** usage 帧（连 `[DONE]` 都没有）。
     *
     * 补读必须兜住异常：拿不到数字是小事，把一次已经成功的流变成失败
     * 会让界面从「答完了」跳成「出错了」。
     */
    @Test
    fun `补读挂住时照常收尾不炸`() {
        val result = drainSse(
            readLine = source(deltaFrame("正文"), terminalFrame("stop")),
            afterFinish = { error("服务端不吭声了") }
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(0, result.promptTokens)
        assertEquals(0, result.completionTokens)
    }

    /** 补读只等到哨兵：服务端不回 usage 帧，数字保持 0，不收尾失败。 */
    @Test
    fun `补读只等到哨兵时 usage 为 0`() {
        val result = drainSse(
            readLine = source(deltaFrame("正文"), terminalFrame("stop")),
            afterFinish = source("data: [DONE]")
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(0, result.promptTokens)
    }

    /** 不传 afterFinish 时保持老行为：finish 之后一行都不多读。 */
    @Test
    fun `默认不补读`() {
        val result = drainSse(
            readLine = source(deltaFrame("正文"), terminalFrame("stop"))
        )
        assertEquals("finish_reason", result.endedBy)
        assertEquals(0, result.promptTokens)
    }

    // ------------------------------------------------------------------
    // 七、终止判定本身
    // ------------------------------------------------------------------

    @Test
    fun `null 不算结束`() {
        assertFalse(isTerminalFinish(null))
    }

    /** 服务端偶尔发空串或字符串 "null" 占位，不能当成结束。 */
    @Test
    fun `空串与 null 字面量不算结束`() {
        assertFalse(isTerminalFinish(""))
        assertFalse(isTerminalFinish("null"))
    }

    @Test
    fun `各终止原因都算结束`() {
        assertTrue(isTerminalFinish("stop"))
        assertTrue(isTerminalFinish("length"))
        assertTrue(isTerminalFinish("content_filter"))
        assertTrue(isTerminalFinish("tool_calls"))
    }
}