package com.mistakebook.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片 OCR 文本整理测试。
 *
 * 这段文本是**唯一**能让不识图的模型知道「图里写了什么」的通道，
 * 整理错了模型就会答非所问，所以每个分支都钉住。
 */
class ChatOcrTextTest {

    // ------------------------------------------------------------ prepare

    @Test
    fun `空输入返回空串`() {
        assertEquals("", ChatOcrText.prepare(""))
        assertEquals("", ChatOcrText.prepare("   \n  "))
    }

    @Test
    fun `剥掉图片引用`() {
        val md = "题目如图\n![](images/abc.jpg)\n答案 42"
        val out = ChatOcrText.prepare(md)
        assertFalse("不该残留图片引用", out.contains("![" ))
        assertFalse("不该残留 .jpg", out.contains(".jpg"))
        assertTrue(out.contains("答案 42"))
    }

    @Test
    fun `剥掉带 alt 文本的图片引用`() {
        val out = ChatOcrText.prepare("![示意图](images/a.png)\n正文")
        assertFalse(out.contains("示意图"))
        assertTrue(out.contains("正文"))
    }

    @Test
    fun `删掉绝对路径但不动 LaTeX`() {
        val md = "图在 /data/user/0/com.app/files/chat/a1.jpg 这里\n公式 \\frac{1}{2}"
        val out = ChatOcrText.prepare(md)
        assertFalse("不该残留绝对路径", out.contains("/data/user/0"))
        assertTrue("LaTeX 不能被误伤", out.contains("\\frac{1}{2}"))
    }

    @Test
    fun `超长文本被截断`() {
        val long = "字".repeat(ChatOcrText.MAX_IMAGE_TEXT_CHARS + 500)
        val out = ChatOcrText.prepare(long)
        assertTrue("应包含截断提示", out.contains("已截断"))
        assertTrue("长度应受限", out.length < ChatOcrText.MAX_IMAGE_TEXT_CHARS + 100)
    }

    @Test
    fun `未超长的文本原样保留`() {
        val out = ChatOcrText.prepare("普通题目\n选 A")
        assertTrue(out.contains("普通题目"))
        assertTrue(out.contains("选 A"))
        assertFalse(out.contains("已截断"))
    }

    // ------------------------------------------------------------ section

    @Test
    fun `section 带上标题`() {
        val out = ChatOcrText.section("题目内容")
        assertTrue(out.startsWith(ChatOcrText.IMAGE_TEXT_HEADER))
        assertTrue(out.contains("题目内容"))
    }

    @Test
    fun `section 对空文本返回空串`() {
        assertEquals("", ChatOcrText.section(""))
        assertEquals("", ChatOcrText.section("   "))
    }

    @Test
    fun `section 反复调用不叠加标题`() {
        // section 会被多次调用（重试、上下文重建），不能自己套自己
        val once = ChatOcrText.section(ChatOcrText.prepare("x"))
        val twice = ChatOcrText.section(ChatOcrText.prepare("x"))
        assertEquals(once, twice)
    }
}
