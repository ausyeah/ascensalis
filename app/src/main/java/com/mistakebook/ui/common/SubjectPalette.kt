package com.mistakebook.ui.common

import androidx.compose.ui.graphics.Color

// 预置学科的展示色（v0.0.57：整体换成暖色系，去掉刺眼的蓝/紫）。
// 用户反馈「蓝色紫色太掉档次」——原配色是标准 Material 蓝 #4F7DF3 +
// 紫 #7A5AF8，和暖纸主题放在一起很跳。这里统一压向赭石 / 橄榄 / 砖红 /
// 灰绿这类低饱和暖色，与 Paper 主题同一个气质。
object SubjectPalette {

    private val colors = mapOf(
        "数学" to Color(0xFFB0673A),   // 赭石
        "物理" to Color(0xFF9A6A2E),   // 琥珀褐
        "化学" to Color(0xFF4E8A72),   // 灰绿
        "生物" to Color(0xFF5C8A3C),   // 橄榄
        "语文" to Color(0xFFA94A3F),   // 砖红
        "英语" to Color(0xFFC08A3E),   // 麦黄
        "历史" to Color(0xFF8A6A4F),   // 棕
        "地理" to Color(0xFF3E7F86),   // 灰青
        "政治" to Color(0xFFA24A63),   // 酒红
        "通信原理" to Color(0xFF4E8A72), // 灰绿
        "其他" to Color(0xFF7A7168)    // 暖灰
    )

    fun colorOf(name: String?): Color = colors[name] ?: Color(0xFF8A6A4F)

    fun argbOf(name: String?): Int {
        val color = colorOf(name)
        return (255 shl 24) or
            ((color.red * 255).toInt() shl 16) or
            ((color.green * 255).toInt() shl 8) or
            (color.blue * 255).toInt()
    }
}
