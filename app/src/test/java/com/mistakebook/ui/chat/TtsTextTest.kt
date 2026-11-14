package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读文本清洗（v0.0.154，学 deepseek-ai/chat-demo 的 ReadAloudButton）。
 *
 * TTS 读 Markdown 记号与 LaTeX 是噪声：`#`、`**`、`$公式$`、代码块
 * 全部要清。「给眼睛的记号剥掉、给耳朵的内容留下」。
 */
class TtsTextTest {

    @Test
    fun `标题与强调记号剥掉`() {
        assertEquals(
            "二次函数的性质",
            TtsText.plain("### **二次函数**的性质")
        )
    }

    @Test
    fun `公式整段移除`() {
        val result = TtsText.plain("面积为 \$\$x^{2}\\end\${'$'}\$\$ 平方单位")
        // 公式读出来是乱码——整段移除后句子仍可读
        assertFalse(result.contains("x^"))
    }

    @Test
    fun `行内公式移除文字保留`() {
        val result = TtsText.plain("由 \$V_y = 10 V_x\$ 求加速度")
        assertTrue(result.contains("求加速度"))
        assertFalse(result.contains("V_y"))
    }

    @Test
    fun `围栏代码块整段移除`() {
        val result = TtsText.plain("先看代码：\n```python\nprint(1)\n```\n然后解释")
        assertTrue(result.contains("然后解释"))
        assertFalse(result.contains("print"))
    }

    @Test
    fun `链接只留文字`() {
        assertEquals("详见 官方文档 一节", TtsText.plain("详见 [官方文档](https://x.com) 一节"))
    }

    @Test
    fun `列表与表格线剥掉`() {
        val result = TtsText.plain("- 第一条\n- 第二条\n| a | b |")
        assertTrue(result.contains("第一条"))
        assertFalse(result.contains("|"))
    }

    @Test
    fun `连续空白压成单空格`() {
        assertEquals("一 二", TtsText.plain("一\n\n  二"))
    }

    @Test
    fun `纯中文原样通过`() {
        assertEquals("你好世界", TtsText.plain("你好世界"))
    }
}
