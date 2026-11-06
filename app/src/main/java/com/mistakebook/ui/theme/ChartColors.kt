package com.mistakebook.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 图表多序列色板（v0.0.128，issue #48）。
 *
 * 为什么单独开一张表：`colorScheme` 的 primary / secondary / tertiary 只有
 * 三档，而番茄钟统计的学科饼图、任务排行需要 8 个**互相可区分**的序列色。
 * 旧做法是在 `PomodoroStatsTab` 里写死八个土色（赭石/灰绿/麦黄…），
 * 用户反馈「颜色太丑，换鲜亮的颜色」——土色系在深色主题下更像蒙了灰。
 *
 * 这张表放在主题包（与 [categoryGradientOf] 同属豁免区）：
 * 「界面层不写裸色值」的契约针对界面组件，成体系的调色板归主题。
 *
 * 选色原则：
 * - 色相拉开（红/橙/黄/绿/青/蓝/紫/粉），相邻扇区不撞色；
 * - 明度中高、彩度高，深色背景上不发闷；
 * - 不使用纯黑/纯白，保证任何主题下都有对比度。
 */
private val ChartColors = listOf(
    Color(0xFFFF6B6B), // 珊瑚红
    Color(0xFF4DABF7), // 天蓝
    Color(0xFFFFC24B), // 明黄
    Color(0xFF51CF66), // 草绿
    Color(0xFFA78BFA), // 薰衣草紫
    Color(0xFFFF922B), // 橘
    Color(0xFF22D3EE), // 青
    Color(0xFFF06595)  // 玫粉
)

/** 第 [index] 个序列色（循环取用）。 */
fun chartColor(index: Int): Color = ChartColors[index % ChartColors.size]