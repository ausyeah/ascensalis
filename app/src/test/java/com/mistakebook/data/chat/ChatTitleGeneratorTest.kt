package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话标题生成（v0.0.108）。
 *
 * 用户的原话：「直接用最后一句话作为标题太难受了」。这里不测网络，
 * 只钉「模型输出怎么变成一行能看的标题」——模型最爱在标题前后加
 * 「标题：」、引号、多行解释，这一层不收拾干净，历史列表会比原来更难看。
 */
class ChatTitleGeneratorTest {

    // cleanTitle 是纯函数，不需要真实 stream：直接调伴生对象上的实现
    private fun clean(raw: String) = ChatTitleGenerator.cleanTitle(raw)

    @Test
    fun `去掉标题前缀与引号`() {
        assertEquals("二次函数求根", clean("标题：二次函数求根"))
        assertEquals("二次函数求根", clean("标题: 二次函数求根"))
        assertEquals("复习计划", clean("「复习计划」"))
        assertEquals("复习计划", clean("\"复习计划\""))
    }

    @Test
    fun `多行只取第一行`() {
        assertEquals(
            "考研数学复习",
            clean("考研数学复习\n\n说明：这段对话主要讨论复习安排。")
        )
    }

    @Test
    fun `去掉句末标点`() {
        assertEquals("洛必达法则适用条件", clean("洛必达法则适用条件。"))
        assertEquals("单词记忆方法", clean("单词记忆方法！"))
    }

    @Test
    fun `空输出或纯标点返回 null`() {
        assertNull(clean(""))
        assertNull(clean("   \n  "))
        assertNull(clean("。"))
        assertNull(clean("「」"))
    }

    @Test
    fun `明显在解释的冗长输出不要`() {
        // 模型跑偏时会给一整段说明，截断成标题只会得到一句更怪的话
        val long = "这段对话讨论了用户在准备考研期间如何安排数学复习计划以及相关的注意事项和心态调整"
        assertNull(clean(long))
    }

    @Test
    fun `略超长时截断并加省略号`() {
        val justOver = "考研数学复习计划与每日时间安排表单"  // 17 个字，上限 16
        val cleaned = clean(justOver)
        assertTrue(cleaned != null)
        assertTrue("截断后要带省略号", cleaned!!.endsWith("…"))
    }

    @Test
    fun `没配置模型时不发请求`() {
        val source = readSource("src/main/java/com/mistakebook/data/chat/ChatTitleGenerator.kt")
        // 空配置打过去只会拿到 401，还会白记一条失败用量
        assertTrue(source.contains("if (profile == null || !profile.isConfigured()) return null"))
    }

    @Test
    fun `标题调用必须走流式并留足思考预算`() {
        // 实测踩过的坑：sensenova 这类推理模型在非流式返回里 message.content
        // 是空的（正文根本没吐），流式才有字；预算给 32/512/1024 时，
        // 光「想」就把额度花完，正文照样是空——标题永远生成不出来。
        val source = readSource("src/main/java/com/mistakebook/data/chat/ChatTitleGenerator.kt")
        assertTrue("必须走流式 complete(", source.contains("stream.complete("))
        assertTrue("请求体必须是 stream = true", source.contains("stream = true"))
        assertTrue("思考预算要给够", source.contains("max_tokens = 2048"))
        assertTrue(
            "空输出时保留原截断标题（cleanTitle 返回 null）",
            source.contains("return cleanTitle(out.toString())")
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            java.io.File(path),
            java.io.File("../$path"),
            java.io.File(path.removePrefix("app/")),
            java.io.File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}
