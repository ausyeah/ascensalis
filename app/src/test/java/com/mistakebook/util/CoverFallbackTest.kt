package com.mistakebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 封面兜底单测。
 *
 * 保护的是一条真实反馈：纯文本 PDF 识别出来的错题在列表里没有封面。
 * 根因是那条路线的 photoPath 是 .md 文件——不是图片，解码必然失败。
 */
class CoverFallbackTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `图片后缀且文件存在才算像图片`() {
        val png = temp.newFile("a.png")
        val jpg = temp.newFile("b.JPG") // 大小写不敏感
        assertTrue(CoverFallback.isLikelyImage(png.absolutePath))
        assertTrue(CoverFallback.isLikelyImage(jpg.absolutePath))
    }

    @Test
    fun `PDF 文本路线的 md 文件不算图片`() {
        // 这正是「PDF 识别出来的错题没封面」的根因
        val md = temp.newFile("pdf_abc_p1.md")
        assertFalse(CoverFallback.isLikelyImage(md.absolutePath))
    }

    @Test
    fun `文件不存在或路径为空都不算图片`() {
        assertFalse(CoverFallback.isLikelyImage("/no/such/file.png"))
        assertFalse(CoverFallback.isLikelyImage(""))
        assertFalse(CoverFallback.isLikelyImage("   "))
    }

    @Test
    fun `content Uri 直接放行`() {
        assertTrue(CoverFallback.isLikelyImage("content://media/external/images/123"))
    }

    @Test
    fun `封面文字压平空白`() {
        assertEquals("已知 a>0 求", CoverFallback.label("已知   a>0\n  求"))
    }

    @Test
    fun `封面文字去掉 Markdown 记号`() {
        // 题干开头常带 ** 或 #，不去掉会得到一片星号
        assertEquals("求极限", CoverFallback.label("**求极限**"))
        assertEquals("选择题", CoverFallback.label("# 选择题"))
    }

    @Test
    fun `封面文字最多 8 个字符`() {
        assertEquals("12345678", CoverFallback.label("1234567890"))
        assertEquals("短", CoverFallback.label("短"))
    }

    @Test
    fun `空题干返回空串`() {
        assertEquals("", CoverFallback.label(""))
        assertEquals("", CoverFallback.label("***"))
    }
}
