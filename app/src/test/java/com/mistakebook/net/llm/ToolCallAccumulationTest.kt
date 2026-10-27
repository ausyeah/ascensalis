package com.mistakebook.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.0.43 工具调用链路的回归测试。
 *
 * 钉死两件事：
 * 1. [accumulateToolCall]：流式分片按 index 拼回完整调用（id/name 取首个非空、
 *    arguments 顺序拼接）——拼错一步，模型拿到的就是残缺参数，搜索直接失败；
 * 2. [SearchTools.parseSearchQuery]：坏 JSON / 缺字段必须返回 null 而不是抛异常，
 *    由上层把「参数缺失」回传给模型自愈。
 */
class ToolCallAccumulationTest {

    private fun delta(
        index: Int = 0,
        id: String? = null,
        name: String? = null,
        arguments: String? = null
    ) = StreamToolCallDelta(
        index = index,
        id = id,
        type = if (id != null) "function" else null,
        function = if (name != null || arguments != null) {
            StreamFunctionDelta(name = name, arguments = arguments)
        } else {
            null
        }
    )

    @Test
    fun singleShardCall() {
        val calls = accumulateToolCall(
            emptyList(),
            delta(index = 0, id = "call_1", name = "web_search", arguments = "{\"query\":\"天气\"}")
        )
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("{\"query\":\"天气\"}", calls[0].arguments)
    }

    @Test
    fun argumentsFragmentsConcatenateInOrder() {
        // 典型流式：首片带 id+name，后续片只带 arguments 片段
        var calls = accumulateToolCall(
            emptyList(),
            delta(index = 0, id = "call_abc", name = "web_search", arguments = "{\"que")
        )
        calls = accumulateToolCall(calls, delta(index = 0, arguments = "ry\":\"北京"))
        calls = accumulateToolCall(calls, delta(index = 0, arguments = "天气\"}"))
        assertEquals(1, calls.size)
        assertEquals("{\"query\":\"北京天气\"}", calls[0].arguments)
        // id/name 只认第一个非空，后续空片不能覆盖
        assertEquals("call_abc", calls[0].id)
        assertEquals("web_search", calls[0].name)
    }

    @Test
    fun differentIndexesAreSeparateCalls() {
        var calls = accumulateToolCall(
            emptyList(),
            delta(index = 0, id = "c0", name = "web_search", arguments = "{\"query\":\"a\"}")
        )
        calls = accumulateToolCall(
            calls,
            delta(index = 1, id = "c1", name = "web_search", arguments = "{\"query\":\"b\"}")
        )
        assertEquals(2, calls.size)
        assertEquals("c0", calls[0].id)
        assertEquals("c1", calls[1].id)
        assertEquals("{\"query\":\"b\"}", calls[1].arguments)
    }

    @Test
    fun parallelCallsInterleaveFragments() {
        // 并行调用时服务端把两条调用的分片交错下发，必须按 index 各自归位
        var calls = accumulateToolCall(
            emptyList(),
            delta(index = 0, id = "c0", name = "web_search", arguments = "{\"query\":")
        )
        calls = accumulateToolCall(calls, delta(index = 1, id = "c1", name = "web_search", arguments = "{\"query\":"))
        calls = accumulateToolCall(calls, delta(index = 0, arguments = "\"量子计算\"}"))
        calls = accumulateToolCall(calls, delta(index = 1, arguments = "\"核聚变\"}"))
        assertEquals(2, calls.size)
        assertEquals("{\"query\":\"量子计算\"}", calls[0].arguments)
        assertEquals("{\"query\":\"核聚变\"}", calls[1].arguments)
    }

    @Test
    fun accumulationDoesNotMutateInput() {
        val first = accumulateToolCall(
            emptyList(),
            delta(index = 0, id = "c0", name = "web_search", arguments = "{}")
        )
        val second = accumulateToolCall(first, delta(index = 0, arguments = "x"))
        // 纯函数：旧列表不被修改
        assertEquals(1, first.size)
        assertEquals("{}", first[0].arguments)
        assertEquals("{}x", second[0].arguments)
    }

    @Test
    fun parsesWellFormedArguments() {
        assertEquals("北京天气", SearchTools.parseSearchQuery("{\"query\":\"北京天气\"}"))
        // 非 ASCII 不需要 JSON 转义，模型直出即可；带转义的引号也能解析
        assertEquals("他说\"你好\"", SearchTools.parseSearchQuery("{\"query\":\"他说\\\"你好\\\"\"}"))
    }

    @Test
    fun returnsNullForBadJsonOrMissingField() {
        assertNull(SearchTools.parseSearchQuery(""))
        assertNull(SearchTools.parseSearchQuery("   "))
        assertNull(SearchTools.parseSearchQuery("not json at all"))
        assertNull(SearchTools.parseSearchQuery("{\"other\":\"x\"}"))
        assertNull(SearchTools.parseSearchQuery("{\"query\":\"\"}"))
        assertNull(SearchTools.parseSearchQuery("{\"query\":\"   \"}"))
        assertNull(SearchTools.parseSearchQuery("{\"query\":123}"))
    }
}
