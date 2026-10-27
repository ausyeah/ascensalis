package com.mistakebook.widget

import com.mistakebook.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** 「今日」小组件的三块数据。null = 无数据（UI 显示占位）。 */
data class TodayWidgetData(
    val dueCount: Int,
    val examCountdownDays: Long?,
    val pomodoroMinutes: Long?
)

/**
 * 小组件数据源（v0.0.51 T9）：把 Room / DataStore 的查询细节封在 App 内，
 * 小组件进程只消费现成的 [TodayWidgetData]。
 */
object WidgetDataProvider {

    /**
     * 聚合今日三数据。任一来源失败都降级为占位（null / 0），
     * 不让小组件因为一次查询异常整体空白。
     */
    suspend fun provide(container: AppContainer): TodayWidgetData {
        val today = LocalDate.now()
        val dueCount: Int = runCatching {
            // observeDueCount 同源的今日到期计数（取一次值即终止的 Flow）
            container.questionRepository.observeDueCount(today).first()
        }.getOrDefault(0)
        val examCountdown: Long? = runCatching {
            container.settingsStore.snapshotNow().examDateEpochDay
                ?.takeIf { it > 0 }
                ?.let { it - today.toEpochDay() }
                ?.takeIf { it >= 0 }
        }.getOrNull()
        val pomodoroMinutes: Long? = runCatching {
            val sec = container.pomodoroDao.totalSecOfDay(today.toString())
            if (sec > 0) sec / 60 else null
        }.getOrNull()
        return TodayWidgetData(dueCount, examCountdown, pomodoroMinutes)
    }
}
