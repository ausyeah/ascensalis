package com.mistakebook.ui.theme

import androidx.compose.ui.graphics.Color
import com.mistakebook.domain.BadgeCategory

/**
 * 徽章分类配色（v0.0.57 暖色系；v0.0.125 扩到 11 类）。
 *
 * 程序化兜底徽章（缺美术素材时的圆形渐变 + 首字）是唯一**成体系**使用
 * 裸色值的地方：11 类 × 明暗两档 = 22 个颜色，走 colorScheme 的 8 个槽位
 * 根本装不下，也轮不到换肤。放 theme 包（豁免区），
 * 「界面层不写裸色值」契约只管界面组件。
 */
fun categoryGradientOf(category: BadgeCategory): Pair<Color, Color> = when (category) {
    BadgeCategory.QUESTIONS -> Color(0xFFB0673A) to Color(0xFF8A4E2A)
    BadgeCategory.MASTERY -> Color(0xFF5C8A3C) to Color(0xFF3E6B2A)
    BadgeCategory.REVIEWS -> Color(0xFF3E7F86) to Color(0xFF2C5F66)
    BadgeCategory.STREAK -> Color(0xFFC08A3E) to Color(0xFF96661E)
    BadgeCategory.DAILY -> Color(0xFFA24A63) to Color(0xFF7A3248)
    BadgeCategory.KNOWLEDGE -> Color(0xFF8A6A4F) to Color(0xFF66492F)
    BadgeCategory.WORDS -> Color(0xFF7C8A4E) to Color(0xFF5A6836)
    BadgeCategory.POMODORO -> Color(0xFFB04A3A) to Color(0xFF8A3628)
    BadgeCategory.CHAT -> Color(0xFF4E6B8A) to Color(0xFF364F66)
    BadgeCategory.LIBRARY -> Color(0xFF8A5C3C) to Color(0xFF684228)
    BadgeCategory.META -> Color(0xFF8A7A3E) to Color(0xFF665A1E)
}
