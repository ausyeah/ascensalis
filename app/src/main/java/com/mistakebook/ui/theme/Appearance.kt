package com.mistakebook.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mistakebook.data.prefs.AppFont
import com.mistakebook.data.prefs.CornerStyle

/**
 * 全局外观的三个旋钮：字号倍数 + 字体 + 圆角倍数。
 *
 * 都是**纯函数**（不碰 Context、不碰 Activity），所以能直接在 JVM 单测里
 * 断言具体数值——Compose 的测量与绘制跑不了普通单测，但「放大 1.15 倍后
 * bodyMedium 是不是 16.1sp」这种问题本来就不需要绘制。
 *
 * ## 为什么在 Typography / Shapes 这一层缩放，而不是逐个 Text 改
 *
 * Material3 组件（TopAppBar / ListItem / Button / 对话框…）默认就从
 * `MaterialTheme.typography` 与 `MaterialTheme.shapes` 取样式。在这两层改，
 * 全 App 一起变；在调用点改，漏一个画面就漏一片，而且每处都要多传一个参数。
 */

/**
 * 字体样式 -> 平台字体。
 *
 * 只用系统自带字体（[FontFamily.Default] / [FontFamily.Serif] /
 * [FontFamily.Monospace]）：不引入字体文件依赖，包体零增长，
 * 各设备渲染也一致。
 */
fun fontFamilyOf(font: AppFont): FontFamily = when (font) {
    AppFont.SYSTEM -> FontFamily.Default
    AppFont.SERIF -> FontFamily.Serif
    AppFont.MONOSPACE -> FontFamily.Monospace
}

/**
 * 按倍数缩放 [ZincTypography] 并整体换字体。
 *
 * 只动 **fontSize 与 lineHeight**，外加 fontFamily：
 * - 行高必须跟着字号一起放大。中文正文行高本来就压在 1.4 倍以上（见
 *   [ZincTypography] 的注释），字号单独放大会把行挤成一片；
 * - letterSpacing **不缩放**。「字号越大字距越紧」是 display 级的固定设计
 *   口径，不是「跟着用户倍数线性变」的量——放大档下再收一档反而过紧。
 *
 * scale 为 1f 且字体为系统默认时直接返回 [ZincTypography]：
 * 绝大多数用户停在默认档，没必要每次重组都造一份 Typography。
 *
 * 十五个槽**一个不漏**：M3 组件没显式指定 style 时会吃任意一槽
 * （displayMedium / headlineLarge 平时没人用，漏掉它们就意味着
 * 放大档下这几处文字偷偷退回默认字号）。
 */
fun appTypography(scale: Float, family: FontFamily): Typography {
    if (scale == 1f && family == FontFamily.Default) return ZincTypography
    val base = ZincTypography
    return base.copy(
        displayLarge = base.displayLarge.resized(scale, family),
        displayMedium = base.displayMedium.resized(scale, family),
        displaySmall = base.displaySmall.resized(scale, family),
        headlineLarge = base.headlineLarge.resized(scale, family),
        headlineMedium = base.headlineMedium.resized(scale, family),
        headlineSmall = base.headlineSmall.resized(scale, family),
        titleLarge = base.titleLarge.resized(scale, family),
        titleMedium = base.titleMedium.resized(scale, family),
        titleSmall = base.titleSmall.resized(scale, family),
        bodyLarge = base.bodyLarge.resized(scale, family),
        bodyMedium = base.bodyMedium.resized(scale, family),
        bodySmall = base.bodySmall.resized(scale, family),
        labelLarge = base.labelLarge.resized(scale, family),
        labelMedium = base.labelMedium.resized(scale, family),
        labelSmall = base.labelSmall.resized(scale, family)
    )
}

/**
 * 按倍数缩放圆角（M3 组件的默认形状全部取自 `MaterialTheme.shapes`）。
 *
 * 基准值是原 `ZincShapes` 的那五个：8 小控件 / 10 / 14 卡片 / 18 / 28
 * 大分组与 sheet。倍数来自 [CornerStyle]——0.5 倍「方正」、1.5 倍「圆润」。
 */
fun zincShapes(corner: CornerStyle): Shapes {
    val s = corner.scale
    return Shapes(
        extraSmall = RoundedCornerShape(8.dp * s),
        small = RoundedCornerShape(10.dp * s),
        medium = RoundedCornerShape(14.dp * s),
        large = RoundedCornerShape(18.dp * s),
        extraLarge = RoundedCornerShape(28.dp * s)
    )
}

/** 单个文字槽：换字体 + 字号/行高按倍数放大，其余（字重、字距、tnum）原样保留。 */
private fun TextStyle.resized(scale: Float, family: FontFamily): TextStyle = copy(
    fontFamily = family,
    fontSize = fontSize.scaledBy(scale),
    lineHeight = lineHeight.scaledBy(scale)
)

/** 等比放大一个字号/行高。显式取 value+type 重组，不依赖运算符重载是否存在。 */
private fun TextUnit.scaledBy(scale: Float): TextUnit =
    TextUnit(value * scale, type)
