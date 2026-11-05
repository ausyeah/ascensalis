package com.mistakebook.pipeline

import com.mistakebook.math.MathWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #33「公示渲染异常」的两条真机回归。
 *
 * 用户截图里两颗红框错误，根因都在我们自己的预处理、而不是 KaTeX：
 *
 * 1. `Expected 'EOF', got '\right'`——[LatexSanitizer.balanceDelimiters] 的
 *    裸 `\\left\s*` 把 `\leftrightarrow` / `\rightarrow` 里的 `\left` / `\right`
 *    也数了进去，频移公式被补上一个多余的 `\right.`，KaTeX 直接报错；
 * 2. `Unexpected character: '\'`——模型把折行折进命令里（`rect\` + 换行 +
 *    `!\left(...`），孤立的反斜杠后面跟着空白，KaTeX 不认；
 *    [MathWrap] 的软切点还可能把 `rect\` 和 `!` 从中间切开，放大成两段坏公式。
 */
class Issue33FormulaRenderTest {

    private val bs: String = "\\"

    @Test
    fun `箭头命令里的 left 不再数成定界符`() {
        // 真机原文（截图用例 1）：频移定理公式
        val freqShift = "g(t)${bs},e^{j2${bs}pi f_0 t} ${bs}leftrightarrow G(f-f_0)"
        assertEquals(freqShift, LatexSanitizer.balanceDelimiters(freqShift))

        // 其余含 left/right 字样的命令同样不许误伤
        listOf(
            "a ${bs}leftrightarrow b",
            "a ${bs}Leftarrow b",
            "a ${bs}rightarrow b",
            "a ${bs}longrightarrow b",
            "a ${bs}Rightarrow b",
            "a ${bs}overrightarrow{v}"
        ).forEach { latex ->
            assertEquals(
                "箭头命令被误数成定界符：$latex",
                latex,
                LatexSanitizer.balanceDelimiters(latex)
            )
        }
    }

    @Test
    fun `真正的孤立 left 仍然会被补 right`() {
        // 回归护栏：修误伤不能把「补 \right.」的救援本身修没了
        val repaired = LatexSanitizer.balanceDelimiters("${bs}left( a + b")
        assertTrue(repaired.endsWith("${bs}right."))
    }

    @Test
    fun `完整的频移公式清洗后不新增 right`() {
        val cleaned = LatexSanitizer.clean(
            "g(t)${bs},e^{j2${bs}pi f_0 t} ${bs}leftrightarrow G(f-f_0)"
        )
        assertFalse(
            "清洗不许给配平的公式追加 \\right.（截图错误 1 的直接来源）",
            cleaned.contains("${bs}right")
        )
    }

    @Test
    fun `孤立的控制反斜杠降级成空格`() {
        // 模型把折行折进命令里：rect\ + 换行 + !\left(...)
        val broken = "rect$bs\n!${bs}left(1${bs}right)"
        val fixed = LatexSanitizer.normalizeControlBackslashBeforeSpace(broken)
        assertTrue(
            "按 LaTeX 语义替换成一个空格，其余字符原样保留",
            fixed == "rect \n!${bs}left(1${bs}right)"
        )
    }

    @Test
    fun `换行符的双反斜杠不受影响`() {
        val rows = "a$bs$bs\nb"
        assertEquals(rows, LatexSanitizer.normalizeControlBackslashBeforeSpace(rows))
    }

    @Test
    fun `清洗后不再有反斜杠加空白`() {
        val broken = "rect$bs\n!${bs}left($bs frac{f}{1000}${bs}right)"
        val cleaned = LatexSanitizer.clean(broken)
        assertFalse(
            "清洗后不许再有「反斜杠+空白」（KaTeX 对它报 Unexpected character）",
            Regex("""(?<!\\)\\(?=\s)""").containsMatchIn(cleaned)
        )
    }

    @Test
    fun `软切点不落在控制符号中间`() {
        // 真机用例 2 的公式骨架：rect\! 与 rect\ 后跟换行的折行写法，
        // 拉长到超过一行预算，逼出软切点
        val latex =
            "${bs}frac{1}{1000}rect${bs}!${bs}left(${bs}frac{f}{1000}${bs}right)*rect${bs}!" +
                "${bs}left(${bs}frac{f}{1000}${bs}right)*${bs}left(${bs}frac{f}{1000}${bs}right)"
        val segments = MathWrap.splitForDisplay(latex, maxChars = 48)

        assertTrue("长公式应当被拆开才会踩到切点", segments.size > 1)
        segments.forEachIndexed { i, seg ->
            assertFalse(
                "第 $i 段不许以孤立反斜杠收尾：$seg",
                seg.endsWith(bs) && !seg.endsWith(bs + bs)
            )
            assertFalse(
                "第 $i 段不许以裸 ! 开头（那是 \\! 被切开的残骸）：$seg",
                seg.startsWith("!")
            )
        }
    }
}
