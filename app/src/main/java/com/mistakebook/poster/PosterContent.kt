package com.mistakebook.poster

import kotlinx.serialization.Serializable

/**
 * 海报内容（**无 Android 依赖，可 JVM 单测**）。
 *
 * 抽成通用结构而不是「单词打卡专用」，是因为同一套渲染要服务两个场景：
 * - 单词学习统计页的「打卡海报」；
 * - 番茄钟统计页的「专注打卡」。
 *
 * 两者只是**数据不同**：标题、日期、几行统计、页脚。
 * 渲染、生图、分享全部共用。
 *
 * @Serializable 是给后台任务用的：海报生图走 WorkManager，
 * 内容要能编码进 WorkData 传给 Worker（见 [PosterWorker]）。
 */
@Serializable
data class PosterContent(
    /** 顶部小字日期，如「2026年10月26日」。 */
    val dateText: String,
    /** 顶部大字主标题，如「背单词 Day 12」。 */
    val headline: String,
    /** 三行统计。 */
    val stats: List<PosterStat>,
    /** 底部品牌行。 */
    val footerText: String = "登临 · 错题本"
)

/**
 * 一行统计：标签 + 数值 + 单位。
 *
 * @param emphasis 数值用强调色（绿）。默认用主色靛蓝。
 */
@Serializable
data class PosterStat(
    val label: String,
    val value: String,
    val unit: String = "",
    val emphasis: Boolean = false
)

/**
 * 把海报内容拼成给**生图模型**的提示词（**纯函数，可 JVM 单测**）。
 *
 * ## 两种模式
 *
 * - [mode] = [PosterImageMode.BACKGROUND]：只让模型画**背景**（不含任何文字），
 *   文字由本地 Canvas 叠加上去——中文永远清晰，且数字绝对准确。
 * - [mode] = [PosterImageMode.FULL]：把**全部文字内容**写进提示词，
 *   让生图模型连文字一起画。风格更自由，但生图模型写中文经常出错，
 *   属于「追求效果、能接受瑕疵」的那一档。
 */
object PosterPrompt {

    fun build(content: PosterContent, mode: PosterImageMode): String {
        return when (mode) {
            PosterImageMode.BACKGROUND -> backgroundPrompt(content)
            PosterImageMode.FULL -> fullPrompt(content)
        }
    }

    /** 纯背景：明确要求「不要任何文字」，否则模型会自己加一堆假字。 */
    fun backgroundPrompt(content: PosterContent): String = buildString {
        append("A vertical study check-in poster background, 2:3 aspect ratio. ")
        append("Soft paper texture background in warm cream color, subtle hand-drawn ")
        append("study elements: an open book, a small potted plant, a pencil, a coffee cup, ")
        append("minimal line art in indigo blue and muted green, lots of clean empty space ")
        append("in the center for text overlay, elegant and calm, high quality, ")
        append("absolutely no text, no letters, no numbers, no watermark.")
        // **不要把海报文字塞进提示词**：生图模型看到具体文字就会试着画出来，
        // 画出来全是错字，还占了本该留白的中心区域。
        // 内容只用来选风格（背单词 vs 专注），这里不传。
    }

    /** 整图：把每行文字都写进去。 */
    fun fullPrompt(content: PosterContent): String = buildString {
        append("A vertical study check-in poster, 2:3 aspect ratio, ")
        append("warm cream paper background with hand-drawn study elements ")
        append("(open book, plant, pencil, coffee cup) in indigo blue and muted green, ")
        append("elegant minimal layout. ")
        append("The poster must contain EXACTLY this text, written clearly and correctly:\n")
        append("Top small line: \"").append(content.dateText).append("\"\n")
        append("Main headline: \"").append(content.headline).append("\"\n")
        content.stats.forEach { stat ->
            val line = if (stat.unit.isBlank()) {
                "${stat.label} ${stat.value}"
            } else {
                "${stat.label} ${stat.value}${stat.unit}"
            }
            append("Statistic line: \"").append(line).append("\"\n")
        }
        append("Bottom line: \"").append(content.footerText).append("\"\n")
        append("Do not add any other text.")
    }
}

/** 海报生图的两种模式。 */
enum class PosterImageMode {
    /** 只生成背景图，文字本地叠加（文字清晰、数字准确）。 */
    BACKGROUND,

    /** 生图模型连文字一起画（风格自由，中文可能出错）。 */
    FULL
}
