package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 学科：用户可自建，首次启动预置「数学」「通信原理」两个。
 *
 * 只预置这两个是按需求定的：用户主要学数学与通信原理，
 * 预置一长串学科反而让下拉框变成噪音。其余学科靠大模型识别后自动建，
 * 或在编辑页手填。
 */
@Entity(tableName = "subjects")
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val colorArgb: Int = DEFAULT_COLOR
) {
    companion object {
        const val DEFAULT_COLOR = 0xFFB0673A.toInt()

        /** 预置学科：名字 → 卡片色板里的颜色（缺省用默认色）。 */
        val PRESETS: List<Pair<String, Int>> = listOf(
            "数学" to 0xFFB0673A.toInt(),
            "通信原理" to 0xFF4E8A72.toInt()
        )

        /** 兜底名：大模型判断不出学科时用它，用户可在编辑页改。 */
        const val FALLBACK = "其他"
    }
}
