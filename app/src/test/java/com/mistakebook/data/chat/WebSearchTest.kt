package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网搜索的纯函数回归：注入文本格式化 + 两个搜索出口的响应解析。
 *
 * 网络调用本身不在单测里（要真网），但解析是这条链路上最容易碎的环节
 * （两家响应结构不同、DeepSeek 还有两种块形态），必须有钉子。
 */
class WebSearchTest {

    @Test
    fun formatterEmptyResultsGivesEmptyString() {
        assertEquals("", WebSearchFormatter.format("任意问题", emptyList()))
    }

    @Test
    fun formatterIncludesQueryAndEntries() {
        val text = WebSearchFormatter.format(
            "2026 考研大纲 变化",
            listOf(
                WebSearchResult("大纲发布", "https://example.com/a", "今年数学二新增…"),
                WebSearchResult("政策解读", "https://example.com/b", "")
            )
        )
        assertTrue(text.contains("【联网搜索结果】"))
        assertTrue(text.contains("2026 考研大纲 变化"))
        assertTrue(text.contains("[1] 大纲发布"))
        assertTrue(text.contains("https://example.com/a"))
        assertTrue(text.contains("今年数学二新增…"))
        // 无摘录的条目不输出空行内容，但标题与 URL 在
        assertTrue(text.contains("[2] 政策解读"))
        assertFalse(text.contains("(无标题)"))
    }

    @Test
    fun formatterTrimsOverlongQuery() {
        val longQuery = "长".repeat(500)
        val text = WebSearchFormatter.format(longQuery, listOf(WebSearchResult("t", "u", "s")))
        // 查询被截到 120 字符，注入块不至于被超长问题撑爆
        assertFalse(text.contains("长".repeat(200)))
        assertTrue(text.contains("长".repeat(120)))
    }

    @Test
    fun tavilyParseExtractsResults() {
        val body = """
            {"query":"q","results":[
              {"title":"T1","url":"https://a.com/1","content":"片段一","score":0.9},
              {"title":"T2","url":"https://a.com/2","content":""}
            ],"response_time":0.4}
        """.trimIndent()
        val results = TavilySearch.parse(body)
        assertEquals(2, results.size)
        assertEquals("T1", results[0].title)
        assertEquals("https://a.com/1", results[0].url)
        assertEquals("片段一", results[0].snippet)
        assertEquals("", results[1].snippet)
    }

    @Test
    fun tavilyParseToleratesGarbage() {
        assertTrue(TavilySearch.parse("not json").isEmpty())
        assertTrue(TavilySearch.parse("{\"results\":[]}").isEmpty())
        assertTrue(TavilySearch.parse("{\"results\":[{\"title\":\"无URL\"}]}").isEmpty())
    }

    @Test
    fun deepSeekParseReadsResultsArray() {
        // v0.0.43 起 DeepSeek 出口走原生 Search API（/v1/tools/search）：
        // results 数组逐条映射，summary 优先作摘录（官方 intended for a model to read）。
        val body = """
            {"results":[
              {"rerank_score":2.4,"title":"DeepSeek 官网","url":"https://d.com/1",
               "snippet":"短摘","summary":"模型可读的长摘要","authority":3},
              {"rerank_score":1.1,"title":"第二条","url":"https://d.com/2","content":"正文"}
            ]}
        """.trimIndent()
        val results = DeepSeekSearch.parse(body)
        assertEquals(2, results.size)
        assertEquals("DeepSeek 官网", results[0].title)
        assertEquals("https://d.com/1", results[0].url)
        assertEquals("模型可读的长摘要", results[0].snippet)
        // 没有 summary 时降级 content
        assertEquals("正文", results[1].snippet)
    }

    @Test
    fun deepSeekParseKeepsEveryResultEntry() {
        // 原生 API 每个 result 独立成条：没有「来源块 + 引用块」两处合并的问题，
        // 也就不需要 dedup——出现两条同 url 就原样保留两条，交给调用方格式化。
        val body = """{"results":[
            {"title":"A","url":"https://d.com/1","summary":"s1"},
            {"title":"B","url":"https://d.com/1","summary":"s2"}
        ]}"""
        val results = DeepSeekSearch.parse(body)
        assertEquals(2, results.size)
        assertEquals("A", results[0].title)
        assertEquals("B", results[1].title)
    }

    @Test
    fun deepSeekParseToleratesGarbage() {
        assertTrue(DeepSeekSearch.parse("{}").isEmpty())
        assertTrue(DeepSeekSearch.parse("oops").isEmpty())
    }

    @Test
    fun searchProviderKeyRoundTrip() {
        assertEquals(ChatSearchProvider.TAVILY, ChatSearchProvider.fromKey("tavily"))
        assertEquals(ChatSearchProvider.TAVILY, ChatSearchProvider.fromKey("TAVILY"))
        assertEquals(ChatSearchProvider.DEEPSEEK, ChatSearchProvider.fromKey("deepseek"))
        // 缺省/未知一律回 DeepSeek（用户指定的默认出口）
        assertEquals(ChatSearchProvider.DEEPSEEK, ChatSearchProvider.fromKey(null))
        assertEquals(ChatSearchProvider.DEEPSEEK, ChatSearchProvider.fromKey("whatever"))
    }
}
