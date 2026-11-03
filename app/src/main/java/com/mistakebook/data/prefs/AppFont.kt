package com.mistakebook.data.prefs

/**
 * 全局字体样式（v0.0.94）：无衬线 / 衬线 / 等宽。
 *
 * 只存枚举，不存 FontFamily——字体是平台资源，属于呈现层
 * （见 ui/theme 的 [com.mistakebook.ui.theme.fontFamilyOf]）。
 *
 * ## 为什么只有这三个，且不打包字体文件
 *
 * 仓库硬性规则是不引入任何新依赖，而字体文件（.ttf/.otf）既是依赖
 * 也要跟着 APK 变大。这三个都是系统自带字体：`FontFamily.Default /
 * Serif / Monospace`，换设备渲染完全一致，包体零增长。
 *
 * 三档各自对应一类真实场景：
 * - [SYSTEM]：默认，界面与阅读都合适；
 * - [SERIF]：长答案（解析、总结）读起来更像书，长时间看不容易累；
 * - [MONOSPACE]：看代码块、公式、逐字对比时等宽才好对齐。
 */
enum class AppFont {
    SYSTEM,
    SERIF,
    MONOSPACE;

    companion object {
        val DEFAULT = SYSTEM

        /** 反序列化：认不出（旧值/脏值）回默认，不崩。 */
        fun fromKey(key: String?): AppFont =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}
