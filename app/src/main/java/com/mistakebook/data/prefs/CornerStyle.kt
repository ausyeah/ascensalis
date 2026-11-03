package com.mistakebook.data.prefs

/**
 * 全局圆角档位（v0.0.94）：方正 / 标准 / 圆润。
 *
 * 只存倍数，圆角 dp 属于呈现层（见 ui/theme 的
 * [com.mistakebook.ui.theme.zincShapes]）。想法来自 shadcn/T3 主题定制器
 * 的 radius——它是那里唯一一个「改一处、整个界面气质就变了」的旋钮，
 * 正合适本仓库「一个设置项只对应一个集中生效点」的口径：
 * Material3 组件的默认形状全部来自 `MaterialTheme.shapes`。
 *
 * 为什么不做成连续可拖的滑杆：与 [FontScale] 同理，0.5 倍和 0.55 倍
 * 肉眼分不出，滑杆只会让人反复拖。三档对应三种清晰的气质。
 *
 * 默认 [STANDARD]（1.0 倍）：与升级前的界面完全一致。
 */
enum class CornerStyle(val scale: Float) {
    SHARP(0.5f),
    STANDARD(1.0f),
    ROUNDED(1.5f);

    companion object {
        val DEFAULT = STANDARD

        /** 反序列化：认不出（旧值/脏值）回默认，不崩。 */
        fun fromKey(key: String?): CornerStyle =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}
