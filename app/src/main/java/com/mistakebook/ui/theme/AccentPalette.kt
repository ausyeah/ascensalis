package com.mistakebook.ui.theme

import androidx.compose.ui.graphics.Color
import com.mistakebook.data.prefs.AccentTone

/**
 * 主题色 → Material3 色槽映射（v0.0.76）。
 *
 * 每个主题色只给「浅色主色 / 深色主色」两枚真彩色，容器色与容器前景色
 * 按同一色系给出——容器色必须是**不透明**的浅/深色块，带 alpha 的色值
 * 叠在卡片上会透出下层内容，chip 与选中态会显得脏。
 *
 * 对比度约定：浅色模式主色上用白字（琥珀这类中亮度色也够 4.5:1），
 * 深色模式主色上用近黑字（暗底亮色块）。
 */
internal data class AccentColors(
    val light: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val dark: Color,
    val darkContainer: Color,
    val darkOnContainer: Color
)

internal fun AccentTone.palette(): AccentColors = when (this) {
    AccentTone.INDIGO -> AccentColors(
        light = Color(0xFF3B5BDB), lightContainer = Color(0xFFE9EDFB), lightOnContainer = Color(0xFF2F4BC0),
        dark = Color(0xFFE9B95C), darkContainer = Color(0xFF3D2F12), darkOnContainer = Color(0xFFF0DDB4)
    )

    AccentTone.JADE -> AccentColors(
        light = Color(0xFF2B7A5B), lightContainer = Color(0xFFE3F3EC), lightOnContainer = Color(0xFF1E5C44),
        dark = Color(0xFF7FD1B0), darkContainer = Color(0xFF1E3A30), darkOnContainer = Color(0xFFCFF0E2)
    )

    AccentTone.CYAN -> AccentColors(
        light = Color(0xFF0E7490), lightContainer = Color(0xFFE0F2F7), lightOnContainer = Color(0xFF0B5A70),
        dark = Color(0xFF6FC7DE), darkContainer = Color(0xFF14303A), darkOnContainer = Color(0xFFC8EAF4)
    )

    AccentTone.VERMILION -> AccentColors(
        light = Color(0xFFB83A2E), lightContainer = Color(0xFFFBE9E6), lightOnContainer = Color(0xFF8F2A20),
        dark = Color(0xFFE8907F), darkContainer = Color(0xFF3A211C), darkOnContainer = Color(0xFFF6D5CE)
    )

    AccentTone.AMBER -> AccentColors(
        light = Color(0xFF9A6206), lightContainer = Color(0xFFFDF0DC), lightOnContainer = Color(0xFF7A4C04),
        dark = Color(0xFFE9B95C), darkContainer = Color(0xFF3D2F12), darkOnContainer = Color(0xFFF0DDB4)
    )

    AccentTone.VIOLET -> AccentColors(
        light = Color(0xFF7048A8), lightContainer = Color(0xFFF0E9F9), lightOnContainer = Color(0xFF563380),
        dark = Color(0xFFC3A0E8), darkContainer = Color(0xFF2E2340), darkOnContainer = Color(0xFFE4D6F5)
    )

    AccentTone.SLATE -> AccentColors(
        light = Color(0xFF3A4A5C), lightContainer = Color(0xFFE6EAF0), lightOnContainer = Color(0xFF2A3746),
        dark = Color(0xFFA8B6C8), darkContainer = Color(0xFF242B33), darkOnContainer = Color(0xFFD6DDE6)
    )

    AccentTone.SAKURA -> AccentColors(
        light = Color(0xFFC23B7A), lightContainer = Color(0xFFFCE8F1), lightOnContainer = Color(0xFF96295D),
        dark = Color(0xFFF0A0C0), darkContainer = Color(0xFF3A1E2A), darkOnContainer = Color(0xFFFBD5E4)
    )
}

/** 设置页色块预览用：取该主题色在当前明暗下的主色。 */
fun AccentTone.swatchColor(dark: Boolean): Color =
    palette().let { if (dark) it.dark else it.light }

/** 顶层函数形式（设置页跨包引用，免去扩展函数 import）。 */
fun swatchColorFor(tone: AccentTone, dark: Boolean): Color = tone.swatchColor(dark)
