package com.mistakebook.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================================
// 纸质化设计系统色彩（源自《单词书》设计规范）
// ==========================================================

// 浅色纸质模式（Warm Paper）
val PaperBgLight = Color(0xFFF6F4EE)         // 暖米灰纸张底色
val PaperCardLight = Color(0xFFFFFEFB)       // 暖白纸卡片
val PaperBorderLight = Color(0xFFE7E3D8)     // 纸张米灰线
val PaperTextPrimaryLight = Color(0xFF26242C)// 墨黑正文
val PaperTextSecondaryLight = Color(0xFF5C5862)
val PaperTextMutedLight = Color(0xFF918C7F)  // 纸墨浅灰辅助文字

// 经典书卷主品牌色
val PaperPrimary = Color(0xFF3B5BDB)         // 沉稳靛蓝
val PaperPrimaryDark = Color(0xFF2F4BC0)
val PaperPrimaryTint = Color(0xFFE9EDFB)     // 淡靛蓝底
val PaperPrimaryLine = Color(0xFFC9D3F4)

// 四级掌握度与反馈状态色彩
val PaperLv0 = Color(0xFFC7CCD9)             // Lv0 未学/生词
val PaperLv1 = Color(0xFFFFB84D)             // Lv1 模糊/重现 (暖金)
val PaperLv2 = Color(0xFF4DABF7)             // Lv2 认识 (晴空蓝)
val PaperLv3 = Color(0xFF51CF66)             // Lv3 精通/掌握 (竹林绿)

val PaperOk = Color(0xFF087F5B)              // 答对墨绿
val PaperOkBg = Color(0xFFF2FBF5)            // 答对暖绿底
val PaperBad = Color(0xFFC92A2A)             // 答错红
val PaperBadBg = Color(0xFFFFF4F2)           // 答错暖红底

// 深色模式（中性石墨 + 单一暖琥珀）
//
// ## 为什么不再是蓝紫系
//
// 旧版深色的底是偏紫的 #19181D、强调色是饱和亮靛蓝 #748FFC。
// 在 OLED 上，高饱和蓝紫与近黑背景的亮度反差过大，长时间直视
// 大号文字与卡片边缘会产生光晕与视觉振动；而这个 App 绝大多数
// 使用场景正是夜间。
//
// 现在换成 M3 深色规范里的写法：surface 取 tone 10 附近的**中性石墨**
// （带一点点暖，避免死灰发青），强调色只在 tone 80 附近取**一个**
// 暖琥珀，其余一律走中性灰阶——全屏最多一个强调色，不铺渐变、
// 不用第二组彩色。
val PaperBgDark = Color(0xFF121212)          // 近黑石墨底
val PaperCardDark = Color(0xFF1C1C1C)        // 石墨卡片（tone 12）
val PaperBorderDark = Color(0xFF3A3733)      // 微暖分隔线，不用冷灰
val PaperTextPrimaryDark = Color(0xFFEDEAE5) // 暖白正文
val PaperTextSecondaryDark = Color(0xFFC4BFB6)
val PaperTextMutedDark = Color(0xFF8A857D)   // 中性暖灰辅助文字
val PaperPrimaryDarkTheme = Color(0xFFE9B95C)// 唯一强调色：暖琥珀（tone 80）
val PaperPrimaryTintDark = Color(0xFF3D2F12) // 琥珀容器（tone 20）
val PaperOkDark = Color(0xFF74C476)          // 柔和竹绿
val PaperOkBgDark = Color(0xFF1E2E20)
val PaperBadDark = Color(0xFFE8907F)         // 柔暖陶土红，不用荧光红
val PaperBadBgDark = Color(0xFF3A211C)

// 兼容既有错题本模块的色值别名（映射到全新纸质化设计）
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

