package com.mistakebook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * normalizeBreaks 的数学区状态机回归测试（v0.0.61）。
 *
 * 防的是「`$$` 逐字符连翻两次」：显示公式 `$$a \neq b$$` 内部被判成
 * 数学区外，`\neq \ne \nabla \nu \notin` 等命令的 `\n` 被换成真换行，
 * 公式毁成残词。输入里的 `\n` 一律是**字面量**（反斜杠 + n 两个字符），
 * 对应模型 JSON 输出解码后的形态。Kotlin 字符串里的 `$` 全部写作 `\$`。
 */
class NormalizeBreaksTest {

    @Test
    fun `显示公式内部的 neq 不被换行摧毁`() {
        // 旧实现：$$ 连翻两次 → 公式内被判区外 → "a␊eq b"
        assertEquals("\$\$a \\neq b\$\$", JsonExtractor.normalizeBreaks("\$\$a \\neq b\$\$"))
    }

    @Test
    fun `显示公式内 nabla nu notin 全家保留`() {
        assertEquals(
            "\$\$\\nabla f \\nu \\notin S\$\$",
            JsonExtractor.normalizeBreaks("\$\$\\nabla f \\nu \\notin S\$\$")
        )
    }

    @Test
    fun `连续两对显示公式状态正确翻转`() {
        assertEquals(
            "\$\$\\neq 1\$\$文字\$\$\\neq 2\$\$",
            JsonExtractor.normalizeBreaks("\$\$\\neq 1\$\$文字\$\$\\neq 2\$\$")
        )
    }

    @Test
    fun `方括号显示定界符内同样受保护`() {
        assertEquals("\\[a \\neq b\\]", JsonExtractor.normalizeBreaks("\\[a \\neq b\\]"))
    }

    @Test
    fun `正文字面换行仍还原为真换行`() {
        assertEquals("第一行\n第二行", JsonExtractor.normalizeBreaks("第一行\\n第二行"))
    }

    @Test
    fun `行内公式内不动 公式外正常换行`() {
        assertEquals(
            "\$x \\nu y\$\n后文",
            JsonExtractor.normalizeBreaks("\$x \\nu y\$\\n后文")
        )
    }

    @Test
    fun `公式结束后恢复换行处理`() {
        assertEquals(
            "\$\\nu\$0\n第二行",
            JsonExtractor.normalizeBreaks("\$\\nu\$0\\n第二行")
        )
    }
}
