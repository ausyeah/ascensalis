package com.mistakebook.review

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mistakebook.MainActivity
import com.mistakebook.MistakeBookApp
import com.mistakebook.R
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// 每日复习提醒：到点查复习队列，有到期题目就发通知，点击进列表（带待复习筛选）。
class ReviewWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = MistakeBookApp.from(appContext).container
        // limit 从 99 提到 100：数量 ≥ 100 时通知标题只显示「99+」，
        // 精确的大数字只会制造焦虑（T1 改动 5）。
        val due = container.questionRepository.dueQuestions(LocalDate.now(), limit = 100)
        if (due.isEmpty()) return Result.success()
        postNotification(due.size)
        return Result.success()
    }

    private fun postNotification(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 用户拒绝过通知权限：静默跳过
            return
        }
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_FILTER_DUE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (count >= 100) {
            appContext.getString(R.string.review_notification_title_99)
        } else {
            appContext.getString(R.string.review_notification_title, count)
        }
        val notification = NotificationCompat.Builder(appContext, MistakeBookApp.CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_stat_review)
            .setContentTitle(title)
            .setContentText(appContext.getString(R.string.review_notification_channel))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "daily_review_reminder"
    }
}

object ReviewScheduler {

    // App 启动时登记每日周期任务：首次延迟到用户设定的时刻，之后每 24 小时一次。
    fun schedule(context: Context, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE) {
        val now = java.time.LocalDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMinutes = java.time.Duration.between(now, target).toMinutes().coerceAtLeast(1)
        val request = PeriodicWorkRequestBuilder<ReviewWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ReviewWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )
    }

    // 立刻跑一次（设置页「立即提醒」与验收 7 手动触发用）。
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReviewWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "review_reminder_now",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
    const val DEFAULT_HOUR = 20
    const val DEFAULT_MINUTE = 0
}
