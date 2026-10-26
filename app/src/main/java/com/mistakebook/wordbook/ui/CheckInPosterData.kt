package com.mistakebook.wordbook.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打卡海报的数据（**无 Android 依赖，可 JVM 单测**）。
 *
 * 与绘制（CheckInPoster.kt）分文件是刻意的：绘制文件里有
 * `Color.parseColor` 之类的顶层常量，JVM 单测一加载那个类就会
 * NoClassDefFoundError——而这里要测的是「海报上的数字对不对」
 * （日期格式、正确率口径），跟画没关系。
 */
data class CheckInPosterData(
    val dateText: String,
    val streakDays: Int,
    val studiedToday: Int,
    /** 今日正确率百分比（整数）；今天没作答时为 null。 */
    val accuracyPercent: Int?,
    val dailyGoal: Int,
    val masteredCount: Int
) {
    /** 正确率为空时用「—」占位：**不谎报 0%**（没做题不等于全错）。 */
    fun accuracyText(): String = accuracyPercent?.let { "$it%" } ?: "—"
}

/**
 * 从 UI 状态装配海报数据。
 *
 * 指标口径一律沿用统计页已有的：今日正确率取近 7 天里最后一天
 * （今天）的那条，与「近 7 天正确率」卡片是同一个数——
 * 海报和界面上看到的不一致是最难解释的那类 bug。
 */
fun buildPosterData(
    state: WordbookUiState,
    now: Long = System.currentTimeMillis()
): CheckInPosterData {
    val today = state.weeklyDayStats.lastOrNull()
    return CheckInPosterData(
        dateText = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(now)),
        streakDays = state.streakDays,
        studiedToday = state.studiedTodayCount,
        accuracyPercent = today?.accuracy?.let { (it * 100).toInt() },
        dailyGoal = state.dailyGoal,
        masteredCount = state.masteredCount
    )
}

/**
 * 装配**通用**海报内容（供本地渲染与生图两种模式共用）。
 *
 * 口径与 [buildPosterData] 完全一致——两处不能各算各的，
 * 不然「界面上的数」和「海报上的数」会对不上。
 */
fun buildPosterContent(
    state: WordbookUiState,
    now: Long = System.currentTimeMillis()
): com.mistakebook.poster.PosterContent {
    val data = buildPosterData(state, now)
    return com.mistakebook.poster.PosterContent(
        dateText = data.dateText,
        headline = "背单词 Day ${data.streakDays}",
        stats = listOf(
            com.mistakebook.poster.PosterStat("学过", "${data.studiedToday}", "词"),
            com.mistakebook.poster.PosterStat("正确率", data.accuracyText(), emphasis = true),
            com.mistakebook.poster.PosterStat("目标", "${data.dailyGoal}", "词")
        ),
        footerText = "登临 · 错题本"
    )
}
