package com.mistakebook.pipeline

import com.mistakebook.math.LatexCompat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 嵌套 `\left(...\left(...\right)...\right)` 走整条显示管线的回归测试。
 *
 * 真机上渲染出「f left(½ left(x+y right)right)」时的第一嫌疑是这条管线
 * 把 `\left` 弄丢——实测它是对的（元凶是裸公式救援劫持了含 `$` 的段落，
 * 见 RescueUndelimitedTest）。留着这条测试把正确行为钉住：
 * 降级后 `\left`/`\right` 全部变成普通定界符，内容一个不丢。
 */
class NestedLeftRightPipelineTest {

    @Test
    fun nestedLeftRightDowngradesToPlainDelimiters() {
        val input = "f\\left(\\frac{1}{2}\\left(x+y\\right)\\right)"
        val repaired = LatexEscapes.repairForDisplay(input, mathOnly = false)
        val cleaned = LatexSanitizer.clean(repaired)
        val downgraded = LatexCompat.downgrade(cleaned)
        assertEquals("f(\\frac{1}{2}(x+y))", downgraded)
    }

    @Test
    fun absoluteValueDelimiterDowngradesToo() {
        val input = "\\left| \\vec{v} \\right|"
        val cleaned = LatexSanitizer.clean(LatexEscapes.repairForDisplay(input, mathOnly = false))
        assertEquals("| \\vec{v} |", LatexCompat.downgrade(cleaned))
    }
}
