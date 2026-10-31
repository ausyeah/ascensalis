package com.mistakebook.pomodoro

import com.mistakebook.poster.PosterContent
import com.mistakebook.poster.PosterStat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 番茄钟「今日打卡」海报内容（**纯逻辑，可 JVM 单测**）。
 *
 * 与单词打卡海报共用同一套渲染与生图流水线（见 `com.mistakebook.poster`），
 * 只是数据口径不同：这里讲的是**专注**，不是**背单词**。
 *
 * 口径说明：
 * - 今日专注时长：**固定的今日口径**（[PomodoroUiState.todaySec]，
 *   v0.0.67 修复）——此前取 `totalSec`（统计页所选区间），用户在统计页
 *   切到「全部」再生成海报，上面就写着「今日专注 300h」；
 * - 完成番茄：只认 `mode = POMODORO` 且完成的那几次（与统计页同口径）；
 * - 连续天数：连续有专注记录的天数。
 */
fun buildPomodoroPosterContent(
    state: PomodoroUiState,
    now: Long = System.currentTimeMillis(),
    // 画布渲染没有 Composable 作用域：小标题与品牌行由调用方从资源解析后传入。
    statsHeader: String,
    footerText: String
): PosterContent {
    val todaySec = state.todaySec
    val hours = todaySec / 3600
    val minutes = (todaySec % 3600) / 60
    val durationText = when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "0m"
    }
    return PosterContent(
        dateText = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(now)),
        headline = "专注 Day ${state.streakDays}",
        stats = listOf(
            PosterStat("今日专注", durationText, emphasis = true),
            PosterStat("完成番茄", "${state.todayPomodoros}", "个"),
            PosterStat("专注次数", "${state.sessionCount}", "次")
        ),
        statsHeader = statsHeader,
        footerText = footerText
    )
}
