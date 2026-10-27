package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DeepSeek 原生 Search API（/v1/tools/search）解析与 Key 挑选的回归测试。
 *
 * 背景：v0.0.41 的 DeepSeek 出口走 Anthropic 兼容端点让模型替我们搜，
 * v0.0.43 起换成官方原生 Search API（2026-10-17 开放）——与 Tavily 对等的
 * 纯搜索服务，任何对话模型都能配一把 DeepSeek Key 调用它。
 *
 * 钉死：
 * 1. results[] 逐条映射，缺 url 的条目丢弃；
 * 2. 摘录字段优先级 summary > content > snippet（官方说 summary 是
 *    intended for a model to read 的粒度）；
 * 3. 坏 JSON / 空 results 不抛异常，返回空列表；
 * 4. pickSearchKey：显式 Key 优先，空串视同未配。
 */
class DeepSeekSearchParseTest {

    @Test
    fun parsesStandardResponse() {
        val body = """
            {"results":[
              {"rerank_score":2.4,"title":"DeepSeek V4 released","url":"https://example.com/v4",
               "snippet":"short","summary":"A 600-char model-ready summary.","content":"full text",
               "site_name":"Example","date":"2026-06-01T00:00:00+00:00","authority":3},
              {"rerank_score":1.1,"title":"Another page","url":"https://example.com/b",
               "summary":"second summary"}
            ]}
        """.trimIndent()
        val results = DeepSeekSearch.parse(body)
        assertEquals(2, results.size)
        assertEquals("DeepSeek V4 released", results[0].title)
        assertEquals("https://example.com/v4", results[0].url)
        // summary 优先于 snippet/content
        assertEquals("A 600-char model-ready summary.", results[0].snippet)
        assertEquals("second summary", results[1].snippet)
    }

    @Test
    fun snippetFallsBackToContentThenSnippet() {
        val noSummary = """
            {"results":[
              {"title":"t1","url":"https://a.com","content":"content text","snippet":"snip"},
              {"title":"t2","url":"https://b.com","snippet":"only snippet"},
              {"title":"t3","url":"https://c.com"}
            ]}
        """.trimIndent()
        val results = DeepSeekSearch.parse(noSummary)
        assertEquals(3, results.size)
        assertEquals("content text", results[0].snippet)
        assertEquals("only snippet", results[1].snippet)
        assertEquals("", results[2].snippet)
    }

    @Test
    fun dropsEntriesWithoutUrl() {
        val body = """{"results":[{"title":"no url"},{"title":"ok","url":"https://x.com","summary":"s"}]}"""
        val results = DeepSeekSearch.parse(body)
        assertEquals(1, results.size)
        assertEquals("https://x.com", results[0].url)
    }

    @Test
    fun emptyAndMalformedBodiesReturnEmptyList() {
        assertEquals(emptyList<WebSearchResult>(), DeepSeekSearch.parse(""))
        assertEquals(emptyList<WebSearchResult>(), DeepSeekSearch.parse("not json"))
        assertEquals(emptyList<WebSearchResult>(), DeepSeekSearch.parse("""{"results":[]}"""))
        assertEquals(emptyList<WebSearchResult>(), DeepSeekSearch.parse("""{"foo":1}"""))
    }

    @Test
    fun pickSearchKeyPrefersExplicit() {
        assertEquals("sk-explicit", pickSearchKey("sk-explicit", "sk-fallback"))
        // 显式空串 / 纯空白视同未配 → 走 fallback
        assertEquals("sk-fallback", pickSearchKey("", "sk-fallback"))
        assertEquals("sk-fallback", pickSearchKey("   ", "sk-fallback"))
        // 两者都空 → null（调用方提示去设置页配 Key，不打注定 401 的请求）
        assertNull(pickSearchKey(null, null))
        assertNull(pickSearchKey("", ""))
        assertNull(pickSearchKey("  ", "   "))
    }
}
