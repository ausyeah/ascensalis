package com.mistakebook.print

import com.mistakebook.data.prefs.AppFont
import com.mistakebook.data.prefs.FontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打印的字号与字体（v0.0.99）。
 *
 * 用户要「在打印页也加入字体大小和字体的设置」。这里守三件事：
 *
 * 1. **字号乘的是同一个倍数**：PDF 里七八个字号常量、HTML 里七八个 px 值，
 *    漏掉一个就会在「大」档下明显偏小。HTML 走 CSS 变量 `--fs`，
 *    PDF 走同一个 scale 乘上去——两边都必须整体缩放。
 * 2. **字体族映射到系统真有的字体**：不打包字体文件，也不在 PDF 里嵌字体
 *    （嵌一份中文衬线体就是好几 MB）。
 * 3. **打印的字号与屏幕的字号是两件事**：手上看着舒服的字号，
 *    和纸上给孩子练的字号不该绑在一起。
 */
class PrintFontTest {

    @Test
    fun `字体族映射到系统自带的三族`() {
        assertEquals("sans-serif", pdfTypefaceName(AppFont.SYSTEM))
        assertEquals("serif", pdfTypefaceName(AppFont.SERIF))
        assertEquals("monospace", pdfTypefaceName(AppFont.MONOSPACE))
    }

    @Test
    fun `HTML 字体栈每一档都以通用族兜底`() {
        listOf(AppFont.SYSTEM, AppFont.SERIF, AppFont.MONOSPACE).forEach { font ->
            val stack = HtmlExporter.htmlFontStack(font)
            assertTrue("$font 的字体栈要以通用族兜底（打印机找不到具体字体时退到它）",
                stack.endsWith("sans-serif") || stack.endsWith("serif") || stack.endsWith("monospace"))
            assertTrue("$font 的字体栈不能为空", stack.isNotBlank())
        }
    }

    @Test
    fun `字号档的倍数与全局那套一致`() {
        // 打印与屏幕共用同一套档位词汇（小/标准/大/特大），
        // 只是各存各的值——所以倍数必须一致，否则同一个「特大」在两处含义不同。
        assertEquals(1.0f, FontScale.STANDARD.scale, 0.0001f)
        assertTrue(FontScale.SMALL.scale < FontScale.STANDARD.scale)
        assertTrue(FontScale.LARGE.scale > FontScale.STANDARD.scale)
        assertTrue(FontScale.EXTRA_LARGE.scale > FontScale.LARGE.scale)
    }

    @Test
    fun `HTML 样式表里所有字号都走同一个变量`() {
        val css = HtmlExporter.css(1.3f, AppFont.SERIF)
        // 只要有一处写死了 px 而没走 var(--fs)，那一处就不会跟着档位变
        val fixed = Regex("font-size:\\s*(?!calc)[\\d.]+px").findAll(css).count()
        assertEquals("字号必须全部走 calc(... * var(--fs))，不能有写死的 px", 0, fixed)
        assertTrue("样式表要声明 --fs", css.contains("--fs:"))
        assertTrue("字体族要真的替换进去", css.contains("serif"))
        assertFalse("占位符不能漏在样式表里", css.contains("__FONT_STACK__"))
    }

    @Test
    fun `HTML 样式表的倍数与字体都跟着参数走`() {
        val small = HtmlExporter.css(0.85f, AppFont.SYSTEM)
        val large = HtmlExporter.css(1.3f, AppFont.SYSTEM)
        assertTrue("大档的倍数要写进 --fs", large.contains("--fs: 1.3"))
        assertTrue("小档的倍数要写进 --fs", small.contains("--fs: 0.85"))
    }

    private fun assertFalse(message: String, condition: Boolean) =
        org.junit.Assert.assertFalse(message, condition)
}
