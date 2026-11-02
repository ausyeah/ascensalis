package com.mistakebook.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================================
// 设计系统色彩 —— 中性锌灰基调（Zinc），结构逆向自 T3 Code 移动端
// （pingdotgg/t3code apps/mobile 实测色值），品牌强调蓝保留。
//
// 三层表面 = 底（canvas）/ 卡（surface）/ 变体面（variant），
// 层次全部靠 1dp 描边与 1 级明度差，不靠阴影。
// 常量名沿用旧「暖纸」体系：外部 5 个文件按名引用，换值不换名。
// ==========================================================

// 浅色（中性锌灰，不偏色）
val PaperBgLight = Color(0xFFFCFCFC)         // 底：画布 = 工具栏 = chrome
val PaperCardLight = Color(0xFFFFFFFF)       // 卡：唯一比底更亮的面
val PaperBorderLight = Color(0xFFE4E4E7)     // 描边：层次全靠它
val PaperTextPrimaryLight = Color(0xFF27272A)// 正文
val PaperTextSecondaryLight = Color(0xFF52525B)
val PaperTextMutedLight = Color(0xFF71717B)  // 辅助文字（4.5:1 on FCFCFC）
val PaperVariantLight = Color(0xFFF4F4F5)    // 次级面（chip/控件底）

// 强调：单一蓝，浅/深同色相不同明度（T3 做法）
val PaperPrimary = Color(0xFF1B4ED8)
val PaperPrimaryDark = Color(0xFF1E40AF)
val PaperPrimaryTint = Color(0xFFE7EDFC)     // 强调色容器（淡蓝底）
val PaperPrimaryLine = Color(0xFFC6D4F7)

// 四级掌握度与反馈状态色（功能色，非风格色；值保持产品语义连续）
val PaperLv0 = Color(0xFFC7CCD9)             // Lv0 未学/生词
val PaperLv1 = Color(0xFFFFB84D)             // Lv1 模糊/重现 (暖金)
val PaperLv2 = Color(0xFF4DABF7)             // Lv2 认识 (晴空蓝)
val PaperLv3 = Color(0xFF51CF66)             // Lv3 精通/掌握 (竹林绿)

val PaperOk = Color(0xFF087F5B)              // 答对墨绿
val PaperOkBg = Color(0xFFF0F9F4)            // 答对底
val PaperBad = Color(0xFFFB2C36)             // 错误红（T3 error）
val PaperBadBg = Color(0xFFFCEBEC)           // 错误底（T3 errorSurface）
val PaperWarn = Color(0xFFFE9A00)            // 警示（T3 warning）
val PaperWarnBg = Color(0xFFFCF4E8)          // 警示底（T3 warningSurface）

// 深色（近黑三层 + 单一蓝）
//
// ## 为什么暗色强调色从琥珀改成蓝（推翻 0.0.8x 的决策）
//
// 旧决策担忧「高饱和蓝紫与近黑背景的亮度反差产生光晕」，矛头指向的是
// 旧版 #748FFC 这种高亮度高饱和的蓝紫。T3 Code 的暗色强调 #346BF1
// 亮度与饱和都低一档，近黑底上实测无光晕问题（其移动端全量用户验证）。
// 同色相跨明暗是 T3 的做法：强调色是「品牌」，不该随明暗变色相；
// 旧版「暗色换琥珀」让同一用户白天见蓝、夜里见金，反而不成体系。
val PaperBgDark = Color(0xFF0A0A0A)          // 底：近黑
val PaperCardDark = Color(0xFF111111)        // 卡
val PaperBorderDark = Color(0xFF191919)      // 描边：只比卡暗一点
val PaperTextPrimaryDark = Color(0xFFF5F5F5)
val PaperTextSecondaryDark = Color(0xFFD4D4D8)
val PaperTextMutedDark = Color(0xFF818181)
val PaperVariantDark = Color(0xFF141414)     // 次级面
val PaperPrimaryDarkTheme = Color(0xFF346BF1)// 暗色强调蓝（T3 dark accent）
val PaperPrimaryTintDark = Color(0xFF16213D) // 强调色容器（深蓝底）
val PaperPrimaryLineDark = Color(0xFF2A3B66)
val PaperOkDark = Color(0xFF74C476)
val PaperOkBgDark = Color(0xFF12241A)
val PaperBadDark = Color(0xFFFB414A)
val PaperBadBgDark = Color(0xFF301214)
val PaperWarnDark = Color(0xFFFE9A00)
val PaperWarnBgDark = Color(0xFF312108)

// 兼容既有错题本模块的色值别名（映射到当前设计系统）
val BrandBlue = PaperPrimary
val BrandBlueDark = PaperPrimaryDark
val BrandBlueLight = PaperPrimaryTint
val Ink = PaperTextPrimaryLight
val InkSecondary = PaperTextSecondaryLight
val SurfaceLight = PaperBgLight
val SurfaceCard = PaperCardLight
val Danger = PaperBad
val WarningAmber = PaperLv1
val SuccessGreen = PaperOk
val DividerGray = PaperBorderLight
val DeleteReveal = Color(0xFFD32F2F)
