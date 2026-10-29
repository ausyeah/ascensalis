package com.mistakebook.wordbook.notify

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
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 单词书每日目标提醒。
 *
 * 与错题复习提醒（[com.mistakebook.review.ReviewWorker]）的区别：
 * 那条是「有到期错题才提醒」，这条是「今天还没达标才提醒」——
 * 已经完成目标的用户不该在晚上 8 点还被推一条通知。
 */
class WordbookReminderWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = MistakeBookApp.from(appContext).container
        val snapshot = container.settingsStore.snapshotNow()
        if (!snapshot.wordbookReminderEnabled) return Result.success()

        val goal = snapshot.wordbookDailyGoal
        val studied = container.wordbookDao
            .observeStudiedTodayCount(startOfDayMillis())
            .first()
        // 已经达标就别打扰了
        if (studied >= goal) return Result.success()

        postNotification(goal = goal, studied = studied)
        return Result.success()
    }

    private fun startOfDayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun postNotification(goal: Int, studied: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 用户拒绝过通知权限：静默跳过
            return
        }
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, MistakeBookApp.CHANNEL_WORDBOOK)
            .setSmallIcon(R.drawable.ic_stat_review)
            .setContentTitle(appContext.getString(R.string.wordbook_reminder_title, goal - studied))
            .setContentText(appContext.getString(R.string.wordbook_reminder_text, goal, studied))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val WORK_NAME = "daily_wordbook_goal_reminder"
    }
}

/**
 * 单词书提醒的调度入口。
 *
 * 时刻**复用错题复习提醒的时间设置**（设置页里的那个时间同时管两条提醒），
 * 不再单独加一个时间选择器——两个提醒都是「到点提醒我学习」，
 * 分开设时间只会让用户多困惑一次。
 */
object WordbookReminderScheduler {

    // policy 默认 CANCEL_AND_REENQUEUE（改提醒时间时重排）；启动路径传 KEEP——
    // 每次启动都取消重排会把首次触发时间无限推后，提醒永远轮不到响
    // （与 ReviewScheduler 同一个坑，v0.0.60 修复）。
    fun schedule(
        context: Context,
        hour: Int = DEFAULT_HOUR,
        minute: Int = DEFAULT_MINUTE,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
    ) {
        val now = java.time.LocalDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMinutes = java.time.Duration.between(now, target).toMinutes().coerceAtLeast(1)
        val request = PeriodicWorkRequestBuilder<WordbookReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WordbookReminderWorker.WORK_NAME,
            policy,
            request
        )
    }

    /** 立刻跑一次（设置页「立即提醒」与手动验证用）。 */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WordbookReminderWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "wordbook_reminder_now",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    const val DEFAULT_HOUR = 20
    const val DEFAULT_MINUTE = 0
}
