package com.mistakebook.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mistakebook.MainActivity
import com.mistakebook.R

/**
 * 番茄钟前台服务。
 *
 * ## 为什么必须有它
 *
 * 用户点开始后大概率会**锁屏或切到别的 App**（专注的本质就是离开手机）。
 * 没有前台服务时，进程一旦被系统回收，计时就断了——而计时中断对一个
 * 计时 App 来说是致命的。
 *
 * ## 为什么是 specialUse 而不是 shortService
 *
 * Android 14 起 `startForeground()` 必须声明服务类型，漏声明直接抛
 * `MissingForegroundServiceTypeException`。番茄钟一跑就是 25 分钟以上，
 * 而 `shortService` 只有约 3 分钟就会被系统停掉，所以只能用
 * `specialUse`（Manifest 里附了 property 说明用途）。
 *
 * ## 为什么不需要 WakeLock
 *
 * 前台服务在息屏期间本身就属于前台进程，不会被休眠策略掐掉；
 * 到点提醒走的是计时核心的绝对时间戳，不依赖定时器回调。
 */
class PomodoroService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 通知里的「结束」只负责撤掉前台状态：计时核心是进程内单例，
                // 服务没必要（也不该）反向去改它——真正的停止由界面发起。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(EXTRA_TASK).orEmpty()
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(title, text))
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PomodoroService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.ifBlank { "专注中" })
            .setContentText(text)
            // 常驻、静默：它不是一条「通知」，而是用户主动开启的一个持续状态
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .addAction(0, "结束", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "番茄钟", NotificationManager.IMPORTANCE_LOW).apply {
                description = "专注计时进行中的常驻提示"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "pomodoro"
        const val NOTIFICATION_ID = 20261
        const val ACTION_STOP = "com.mistakebook.pomodoro.STOP"
        const val EXTRA_TASK = "task"
        const val EXTRA_TEXT = "text"

        /**
         * 启动（或更新）常驻通知。
         *
         * Android 12+ 禁止从后台启动前台服务；番茄钟一定是从界面上点开始的，
         * 不存在这个问题。但系统还可能因为「内存紧张」等理由拒绝，
         * 所以这里吞掉异常——**通知起不来不该让计时也跟着崩**。
         */
        fun start(context: Context, task: String, text: String) {
            val intent = Intent(context, PomodoroService::class.java).apply {
                putExtra(EXTRA_TASK, task)
                putExtra(EXTRA_TEXT, text)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // 后台启动限制 / 系统拒绝：退化为「仅应用内计时」
            }
        }

        fun update(context: Context, task: String, text: String) {
            start(context, task, text)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PomodoroService::class.java)) }
        }
    }
}
