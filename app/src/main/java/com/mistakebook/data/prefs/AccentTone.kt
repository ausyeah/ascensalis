package com.mistakebook.data.prefs

/**
 * 自选主题色（v0.0.76）。
 *
 * 只存枚举，不存色值——色值属于呈现层（见 ui/theme/AccentPalette）。
 * 这样 data 层不依赖 Compose，将来换配色方案也不用动存储。
 *
 * 默认 [INDIGO] 与旧版万彩一致：升级后不主动改用户看到的主色。
 */
enum class AccentTone(val label: String) {
    INDIGO("靛蓝"),
    JADE("青竹"),
    CYAN("墨玉"),
    VERMILION("朱砂"),
    AMBER("琥珀"),
    VIOLET("紫藤"),
    SLATE("黛蓝"),
    SAKURA("樱粉");

    companion object {
        val DEFAULT = INDIGO

        /** 反序列化：认不出（旧值/脏值）回默认，不崩。 */
        fun fromKey(key: String?): AccentTone =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}
