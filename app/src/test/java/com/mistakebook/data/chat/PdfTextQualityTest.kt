package com.mistakebook.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 抽文本的可用性判定（v0.0.58）。
 *
 * 防的是「乱码被当成成果发给模型」：用户实测气泡写着「已提取 631 字」，
 * 而模型收到的是一串 PDF 原始字节。判定必须是纯函数才能在 JVM 里穷举正反例。
 */
class PdfTextQualityTest {

    @Test
    fun `正常中文题干判为可用`() {
        val text = """
            7.13 某模拟信源输出的信号 x(t) 是平稳随机过程，源的输出信号按
            fs = 250Hz 的抽样率进行抽样，样值 i = 1,2,3,4,5。
            (1) 将量化后的结果进行哈夫曼编码；
        """.trimIndent()
        assertTrue(PdfTextQuality.isUsable(text))
    }

    @Test
    fun `正常英文与公式文本判为可用`() {
        assertTrue(
            PdfTextQuality.isUsable(
                "The quick brown fox jumps over 12 lazy dogs. E = mc^2 and f(x) = x^2 + 1."
            )
        )
    }

    @Test
    fun `PDF 原始字节流判为不可用`() {
        // 用户截图里的真实形态：可打印字符里混着大量控制字节
        val garbage = buildString {
            append("JeMä\x00\x01\x02\x03\x04")
            repeat(20) { append("Obj\u0007\u000b\u000e endobj\u001f") }
        }
        assertFalse(PdfTextQuality.isUsable(garbage))
    }

    @Test
    fun `空白与过短内容判为不可用`() {
        assertFalse(PdfTextQuality.isUsable(""))
        assertFalse(PdfTextQuality.isUsable("   \n\t "))
        assertFalse(PdfTextQuality.isUsable("1"))
    }

    @Test
    fun `控制字符占比略高也判为不可用`() {
        val mostlyBinary = "abc" + "\u0001".repeat(50)
        assertFalse(PdfTextQuality.isUsable(mostlyBinary))
    }
}
