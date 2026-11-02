package com.mistakebook.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * 锌灰色板的设计契约（逆向自 T3 Code 的结构，值在这里钉死）。
 *
 * 高级感的三条结构性承诺，全部可以用纯函数验证：
 * 1. **可读性**：正文 ≥ 7:1、辅助文字与强调色上的字 ≥ 4.5:1（WCAG）；
 * 2. **层次靠明度带不靠阴影**：深色表面阶梯必须单调递进且相邻只差一线；
 * 3. **单强调色**：默认主题的 primary 就是一枚品牌蓝，浅/深同色相不同明度。
 *
 * 违反任何一条的「顺手改个色」都会让全 App 的质感同步塌掉——这正是契约
 * 测试该拦的事。Color 是纯 Kotlin value class，JVM 单测直接可算。
 */
class ZincPaletteContractTest {

    // ---------- WCAG 对比度（标准公式） ----------

    private fun channels(color: Color): Triple<Double, Double, Double> {
        val argb = color.toArgb()
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return Triple(channel((argb shr 16) and 0xFF), channel((argb shr 8) and 0xFF), channel(argb and 0xFF))
    }

    private fun luminance(color: Color): Double {
        val (r, g, b) = channels(color)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    // ---------- 可读性 ----------

    @Test
    fun `正文与底色对比度至少7比1`() {
        assertTrue(contrast(ZincLight.onBackground, ZincLight.background) >= 7.0)
        assertTrue(contrast(ZincDark.onBackground, ZincDark.background) >= 7.0)
    }

    @Test
    fun `辅助文字与次级面对比度至少4点5比1`() {
        assertTrue(contrast(ZincLight.onSurfaceVariant, ZincLight.surfaceVariant) >= 4.5)
        assertTrue(contrast(ZincDark.onSurfaceVariant, ZincDark.surfaceVariant) >= 4.5)
    }

    @Test
    fun `强调色上的文字至少4点5比1`() {
        assertTrue(contrast(ZincLight.onPrimary, ZincLight.primary) >= 4.5)
        assertTrue(contrast(ZincDark.onPrimary, ZincDark.primary) >= 4.5)
    }

    // ---------- 层次结构 ----------

    @Test
    fun `深色表面阶梯从底到浮单调变亮`() {
        val ladder = listOf(
            ZincDark.surfaceContainerLowest,
            ZincDark.surfaceContainerLow,
            ZincDark.surfaceContainer,
            ZincDark.surfaceContainerHigh,
            ZincDark.surfaceContainerHighest
        )
        for (i in 0 until ladder.lastIndex) {
            assertTrue(
                "深色表面阶梯第 $i 级必须比上一级更亮（面浮在底上）",
                luminance(ladder[i]) < luminance(ladder[i + 1])
            )
        }
        // 底（background）是整个阶梯的起点：任何卡面都比底亮
        assertTrue(luminance(ZincDark.background) <= luminance(ZincDark.surfaceContainerLowest))
    }

    @Test
    fun `浅色表面阶梯从底到浮单调变暗`() {
        // M3 浅色惯例：lowest 最亮（白卡），底比白卡暗一线
        val ladder = listOf(
            ZincLight.surfaceContainerLowest,
            ZincLight.surfaceContainerLow,
            ZincLight.surfaceContainer,
            ZincLight.surfaceContainerHigh,
            ZincLight.surfaceContainerHighest
        )
        for (i in 0 until ladder.lastIndex) {
            assertTrue(
                "浅色表面阶梯第 $i 级必须不比上一级更亮",
                luminance(ladder[i]) >= luminance(ladder[i + 1])
            )
        }
    }

    @Test
    fun `描边与卡面只差一线`() {
        // 层次靠 1dp 描边：边框色不能比卡面深太多，否则变成粗黑格子
        assertTrue(contrast(ZincLight.outlineVariant, PaperCardLight) < 2.0)
        assertTrue(contrast(ZincDark.outlineVariant, PaperCardDark) < 2.0)
    }

    // ---------- 单强调色 ----------

    @Test
    fun `默认主题的主色就是品牌蓝`() {
        assertEquals(PaperPrimary, ZincLight.primary)
        assertEquals(PaperPrimaryDarkTheme, ZincDark.primary)
    }

    @Test
    fun `强调色浅深同色相不同明度`() {
        // T3 做法：强调色是品牌，不随明暗变色相——B 通道主导蓝色相
        fun hueDominant(c: Color): Boolean {
            val (r, g, b) = channels(c)
            return b > r && b > g
        }
        assertTrue(hueDominant(ZincLight.primary))
        assertTrue(hueDominant(ZincDark.primary))
        assertTrue(luminance(ZincDark.primary) > luminance(ZincLight.primary))
    }

    // ---------- 语义底色 ----------

    @Test
    fun `错误色与其容器底可区分`() {
        assertTrue(contrast(ZincLight.error, ZincLight.errorContainer) >= 3.0)
        assertTrue(contrast(ZincDark.error, ZincDark.errorContainer) >= 3.0)
    }
}
