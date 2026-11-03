package com.mistakebook.data.prefs

/**
 * 全局字号档位（v0.0.94）。
 *
 * 只存倍数，不存字号——真实字号属于呈现层（见 ui/theme 的
 * [com.mistakebook.ui.theme.appTypography]）。这样 data 层不依赖 Compose，
 * 而「界面字号」和「对话正文字号」共用同一个倍数：
 * 一处调，全 App 一起变，用户不用记「哪个档管哪块」。
 *
 * 为什么不做成连续可拖的滑杆：字号是**离散档位**才有意义的事——
 * 相邻两档差 0.15 倍时肉眼分不出，滑杆只会让人来回拖着想「到底哪个位置对」。
 * 四档也够覆盖「字太小看不清」和「一屏想多看几行」两个方向的真实诉求。
 *
 * 默认 [STANDARD]（1.0 倍）：升级后界面一个字都不动。
 */
enum class FontScale(val scale: Float) {
    SMALL(0.85f),
    STANDARD(1.0f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f);

    companion object {
        val DEFAULT = STANDARD

        /** 反序列化：认不出（旧值/脏值）回默认，不崩。 */
        fun fromKey(key: String?): FontScale =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}
