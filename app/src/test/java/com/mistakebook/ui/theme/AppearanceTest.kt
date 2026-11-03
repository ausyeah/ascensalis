package com.mistakebook.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import com.mistakebook.data.prefs.AppFont
import com.mistakebook.data.prefs.CornerStyle
import com.mistakebook.data.prefs.FontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局外观旋钮（字号 / 字体 / 圆角）的纯函数测试（v0.0.94）。
 *
 * Compose 的测量与绘制跑不了普通 JVM 单测，但「放大 1.15 倍后
 * bodyMedium 是不是 16.1sp」「圆角 0.5 倍后 medium 是不是 7dp」
 * 这种问题本来就不需要绘制——缩放函数被刻意写成不碰 Context 的纯函数，
 * 就是为了能在这里钉住具体数值。
 *
 * 这里最容易犯的错是**漏槽**：Typography 有 15 个文字槽，漏掉一个
 * （比如没人用的 displayMedium）就意味着放大档下那一处文字偷偷退回
 * 默认字号。所以第一个用例就把 15 个槽全列出来逐个对。
 */
class AppearanceTest {

    // ===== 字号 =====

    @Test
    fun `默认档原样返回基准排版`() {
        val scaled = appTypography(1f, FontFamily.Default)

        assertSame(
            "默认档没必要造一份新 Typography，直接复用基准",
            ZincTypography,
            scaled
        )
    }

    @Test
    fun `十五个文字槽一个不漏地放大`() {
        val scale = 1.15f
        val scaled = appTypography(scale, FontFamily.Default)

        allSlots(scaled).forEach { (name, style) ->
            val base = baseSlots()[name]!!
            assertEquals(
                "$name 的字号没跟着放大——漏了这个槽，放大档下它会偷偷退回默认字号",
                base.fontSize.value * scale,
                style.fontSize.value,
                0.001f
            )
            assertEquals(
                "$name 的行高没跟着放大——中文行高压在 1.4 倍以上，只放字号会把行挤成一片",
                base.lineHeight.value * scale,
                style.lineHeight.value,
                0.001f
            )
            assertEquals("$name 的单位该还是 sp", TextUnitType.Sp, style.fontSize.type)
        }
    }

    @Test
    fun `缩小档同样等比`() {
        val style = appTypography(0.85f, FontFamily.Default).bodyMedium

        // 14sp * 0.85 = 11.9sp；22sp * 0.85 = 18.7sp
        assertEquals(11.9f, style.fontSize.value, 0.001f)
        assertEquals(18.7f, style.lineHeight.value, 0.001f)
    }

    @Test
    fun `特大档同样等比`() {
        val style = appTypography(1.3f, FontFamily.Default).bodyLarge

        // 16sp * 1.3 = 20.8sp；26sp * 1.3 = 33.8sp
        assertEquals(20.8f, style.fontSize.value, 0.001f)
        assertEquals(33.8f, style.lineHeight.value, 0.001f)
    }

    @Test
    fun `行高与字号同比例放大`() {
        val base = ZincTypography.bodyMedium
        val scaled = appTypography(1.3f, FontFamily.Default).bodyMedium

        // 中文正文可读性全靠行高/字号这个比值，缩放后必须原样保持
        assertEquals(
            base.lineHeight.value / base.fontSize.value,
            scaled.lineHeight.value / scaled.fontSize.value,
            0.001f
        )
    }

    @Test
    fun `换字体不改变号`() {
        val scaled = appTypography(1f, FontFamily.Serif)

        assertEquals(
            ZincTypography.bodyMedium.fontSize.value,
            scaled.bodyMedium.fontSize.value,
            0.001f
        )
        assertEquals(FontFamily.Serif, scaled.bodyMedium.fontFamily)
        assertEquals(FontFamily.Serif, scaled.labelSmall.fontFamily)
    }

    @Test
    fun `字重字距与等宽数字特性原样保留`() {
        val scaled = appTypography(1.3f, FontFamily.Monospace)

        // 这三项是设计口径，不是「跟着用户倍数线性变」的量
        assertEquals(ZincTypography.displayLarge.fontFeatureSettings, scaled.displayLarge.fontFeatureSettings)
        assertEquals(ZincTypography.displayLarge.letterSpacing, scaled.displayLarge.letterSpacing)
        assertEquals(ZincTypography.titleMedium.fontWeight, scaled.titleMedium.fontWeight)
        assertEquals(ZincTypography.labelMedium.fontWeight, scaled.labelMedium.fontWeight)
    }

    // ===== 字体 =====

    @Test
    fun `三种字体样式映射到系统自带字体`() {
        assertEquals(FontFamily.Default, fontFamilyOf(AppFont.SYSTEM))
        assertEquals(FontFamily.Serif, fontFamilyOf(AppFont.SERIF))
        assertEquals(FontFamily.Monospace, fontFamilyOf(AppFont.MONOSPACE))
    }

    // ===== 圆角 =====

    @Test
    fun `标准档圆角与升级前完全一致`() {
        assertEquals(8f, cornerRadiusPx(zincShapes(CornerStyle.STANDARD).extraSmall))
        assertEquals(10f, cornerRadiusPx(zincShapes(CornerStyle.STANDARD).small))
        assertEquals(14f, cornerRadiusPx(zincShapes(CornerStyle.STANDARD).medium))
        assertEquals(18f, cornerRadiusPx(zincShapes(CornerStyle.STANDARD).large))
        assertEquals(28f, cornerRadiusPx(zincShapes(CornerStyle.STANDARD).extraLarge))
    }

    @Test
    fun `方正档是标准的一半`() {
        assertEquals(4f, cornerRadiusPx(zincShapes(CornerStyle.SHARP).extraSmall))
        assertEquals(7f, cornerRadiusPx(zincShapes(CornerStyle.SHARP).medium))
        assertEquals(14f, cornerRadiusPx(zincShapes(CornerStyle.SHARP).extraLarge))
    }

    @Test
    fun `圆润档是标准的一倍半`() {
        assertEquals(12f, cornerRadiusPx(zincShapes(CornerStyle.ROUNDED).extraSmall))
        assertEquals(21f, cornerRadiusPx(zincShapes(CornerStyle.ROUNDED).medium))
        assertEquals(42f, cornerRadiusPx(zincShapes(CornerStyle.ROUNDED).extraLarge))
    }

    // ===== 档位枚举 =====

    @Test
    fun `档位默认值与反序列化容错`() {
        assertEquals(FontScale.STANDARD, FontScale.DEFAULT)
        assertEquals(AppFont.SYSTEM, AppFont.DEFAULT)
        assertEquals(CornerStyle.STANDARD, CornerStyle.DEFAULT)

        // 认不出（旧值 / 脏值 / null）一律回默认，不让设置页崩
        assertEquals(FontScale.DEFAULT, FontScale.fromKey("nonsense"))
        assertEquals(FontScale.DEFAULT, FontScale.fromKey(null))
        assertEquals(FontScale.LARGE, FontScale.fromKey("large"))
        assertEquals(AppFont.DEFAULT, AppFont.fromKey(""))
        assertEquals(AppFont.MONOSPACE, AppFont.fromKey("Monospace"))
        assertEquals(CornerStyle.DEFAULT, CornerStyle.fromKey("nope"))
        assertEquals(CornerStyle.ROUNDED, CornerStyle.fromKey("ROUNDED"))
    }

    @Test
    fun `每个档位都有互不相同的倍数`() {
        // 两档倍数相同就等于设置页里有两个长得一样的选项
        assertEquals(FontScale.entries.size, FontScale.entries.map { it.scale }.distinct().size)
        assertEquals(CornerStyle.entries.size, CornerStyle.entries.map { it.scale }.distinct().size)
        assertTrue(FontScale.entries.all { it.scale > 0f })
        assertTrue(CornerStyle.entries.all { it.scale > 0f })
    }

    // ===== 工具 =====

    /** 15 个文字槽的「槽名 -> 缩放后样式」表。写全就是为了抓漏槽。 */
    private fun allSlots(t: Typography): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to t.displayLarge,
        "displayMedium" to t.displayMedium,
        "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge,
        "headlineMedium" to t.headlineMedium,
        "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge,
        "titleMedium" to t.titleMedium,
        "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge,
        "bodyMedium" to t.bodyMedium,
        "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge,
        "labelMedium" to t.labelMedium,
        "labelSmall" to t.labelSmall
    )

    private fun baseSlots(): Map<String, TextStyle> = allSlots(ZincTypography).toMap()

    /** 密度为 1 时，圆角的 px 值就是 dp 值——用来看清 shapes 里到底存了多少。 */
    private fun cornerRadiusPx(shape: androidx.compose.ui.graphics.Shape): Float =
        (shape as CornerBasedShape).topStart.toPx(Size.Zero, UnitDensity)

    private val UnitDensity = object : Density {
        override val density: Float = 1f
        override val fontScale: Float = 1f
    }
}
