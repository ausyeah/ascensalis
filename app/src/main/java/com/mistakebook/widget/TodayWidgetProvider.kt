package com.mistakebook.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mistakebook.MainActivity
import com.mistakebook.MistakeBookApp
import com.mistakebook.R
import kotlinx.coroutines.launch

/**
 * 「今日」桌面小组件（v0.0.51 T9）：2x2 三数据块。
 *
 * - 今日待复习数（Room，questionDao.observeDueCount 同源的今日到期数）
 * - 最近考试倒计时（DataStore 的 examDate，无则显示「暂无考试」）
 * - 今日专注时长（pomodoro_sessions 当日 sum）
 *
 * ## 架构约束
 *
 * 小组件进程**不直接碰数据库细节**：数据聚合全部走 [WidgetDataProvider]
 * （App 内封装），Provider 只拿现成的 [TodayWidgetData] 填 RemoteViews。
 * 刷新两条路：
 * - 系统周期刷新（updatePeriodMillis ≥ 30 分钟）；
 * - 事件驱动：完成复习 / 完成番茄 / 改设置时发
 *   [ACTION_REFRESH] 广播，App 进程内 [refreshAll] 主动更新。
 *
 * 整体点击进 App（复习页优先，经 MainActivity 的 deep link 路由）。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            refreshAll(context)
        }
    }

    companion object {
        /** 事件驱动刷新广播（App 内 sendBroadcast，exported=false）。 */
        const val ACTION_REFRESH = "com.mistakebook.widget.REFRESH"

        /**
         * 拉一次数据并刷新全部桌面实例。
         *
         * 挂在 appScope（App 进程存活期协程域）：小组件回调本身没有协程域，
         * 且 goAsync() 的 10 秒窗口对一次聚合查询绰绰有余——用 appScope
         * 更简单，进程被杀时更新本来也无从谈起。
         */
        fun refreshAll(context: Context) {
            val app = context.applicationContext as? MistakeBookApp ?: return
            app.container.appScope.launch {
                val data = WidgetDataProvider.provide(app.container)
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    android.content.ComponentName(context, TodayWidgetProvider::class.java)
                )
                val views = buildViews(context, data)
                ids.forEach { id -> manager.updateAppWidget(id, views) }
            }
        }

        /** 组装 RemoteViews：三块数据 + 整体点击。无数据时显示占位。 */
        internal fun buildViews(context: Context, data: TodayWidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(
                R.id.widget_review_count,
                if (data.dueCount > 0) data.dueCount.toString() else "—"
            )
            views.setTextViewText(
                R.id.widget_exam_countdown,
                data.examCountdownDays?.let { "${it}天" } ?: context.getString(R.string.widget_no_exam)
            )
            views.setTextViewText(
                R.id.widget_pomodoro,
                data.pomodoroMinutes?.let { "${it}分钟" } ?: "—"
            )
            // 整体点击进 App：复习页优先——复用 MainActivity 既有的
            // EXTRA_FILTER_DUE 语义（启动即定位今日到期复习）。
            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(MainActivity.EXTRA_FILTER_DUE, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }
    }
}
