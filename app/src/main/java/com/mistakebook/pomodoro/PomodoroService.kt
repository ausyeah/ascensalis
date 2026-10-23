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
import com.mistakebook.MistakeBookApp
import com.mistakebook.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 * ## 通知为什么用系统 chronometer
 *
 * 旧版通知是一段静态文字，用户吐槽「没有具体的时间」。自己每秒
 * `notify()` 刷文字当然行，但那是应用自己在干活；而
 * [NotificationCompat.setUsesChronometer] 把「实时走秒」交给
 * **系统**渲染——锁屏、通知栏、以及 MIUI 的焦点通知（俗称灵动岛）
 * 都会接管这个计时器自己显示，电量与一致性都比应用每秒重发好。
 * 倒计时用 `setChronometerCountDown(true)` + 结束时刻，正计时用
 * 开始时刻，系统自己会算。
 */
class PomodoroService : Service() {

    /** 通知按钮（暂停/继续/结束）的落点：活不重，但必须活得比点击长。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 最近一次启动通知时带的任务名：重建通知（暂停/继续）时沿用。 */
    private var lastTaskName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? MistakeBookApp ?: return START_NOT_STICKY
        val timer = app.container.pomodoroTimer

        when (intent?.action) {
            ACTION_PAUSE -> {
                // 通知里的「暂停」直接操作计时核心单例：界面每秒一轮的
                // 同步循环会把新状态带到 UI，不需要（也不该）反向依赖界面。
                timer.pause()
                refreshNotification()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                timer.resume()
                refreshNotification()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                // 「结束」= 收掉这次计时并按规则落库（与界面上的
                // 「结束并记录」同一个实现，见 PomodoroRecorder）。
                scope.launch {
                    PomodoroRecorder.stopAndRecord(timer, app.container.pomodoroDao)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        lastTaskName = intent?.getStringExtra(EXTRA_TASK).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(lastTaskName))
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 用当前计时状态重建通知（暂停/继续后调用）。 */
    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(lastTaskName))
    }

    private fun buildNotification(title: String): Notification {
        ensureChannel()
        val app = application as MistakeBookApp
        val timer = app.container.pomodoroTimer
        val state = timer.state.value
        val running = state.status == RunStatus.RUNNING
        val elapsed = timer.elapsedMs()
        val remaining = (state.targetMs - elapsed).coerceAtLeast(0L)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.ifBlank { "专注中" })
            // 常驻、静默：它不是一条「通知」，而是用户主动开启的一个持续状态
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        // 运行中：把走秒交给系统 chronometer（锁屏/焦点通知会接管显示）。
        // 暂停时撤掉 chronometer，改用静态文字——否则系统会继续跑秒，
        // 显示的时间比实际暂停的多。
        if (running && state.targetMs > 0L) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + remaining)
                .setContentText("剩余 ${formatClock(remaining)}")
        } else if (running) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setWhen(System.currentTimeMillis() - elapsed)
                .setContentText("已专注 ${formatClock(elapsed)}")
        } else {
            builder.setContentText(
                if (state.targetMs > 0L) "已暂停 · 剩余 ${formatClock(remaining)}"
                else "已暂停 · 已专注 ${formatClock(elapsed)}"
            )
        }

        if (running) {
            builder.addAction(
                0,
                "暂停",
                servicePendingIntent(REQUEST_PAUSE, ACTION_PAUSE)
            )
        } else {
            builder.addAction(
                0,
                "继续",
                servicePendingIntent(REQUEST_RESUME, ACTION_RESUME)
            )
        }
        builder.addAction(0, "结束", servicePendingIntent(REQUEST_STOP, ACTION_STOP))

        return builder.build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PomodoroService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
        const val ACTION_PAUSE = "com.mistakebook.pomodoro.PAUSE"
        const val ACTION_RESUME = "com.mistakebook.pomodoro.RESUME"
        const val EXTRA_TASK = "task"

        private const val REQUEST_PAUSE = 1
        private const val REQUEST_RESUME = 2
        private const val REQUEST_STOP = 3

        /** mm:ss / h:mm:ss。通知与表盘共用一套格式，避免两处显示不一致。 */
        fun formatClock(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%02d:%02d", m, s)
            }
        }

        /**
         * 启动（或更新）常驻通知。
         *
         * Android 12+ 禁止从后台启动前台服务；番茄钟一定是从界面上点开始的，
         * 不存在这个问题。但系统还可能因为「内存紧张」等理由拒绝，
         * 所以这里吞掉异常——**通知起不来不该让计时也跟着崩**。
         */
        fun start(context: Context, task: String) {
            val intent = Intent(context, PomodoroService::class.java).apply {
                putExtra(EXTRA_TASK, task)
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

        /** 界面暂停/继续时调用：重建通知（chronometer 的起止跟着状态变）。 */
        fun refresh(context: Context, task: String) {
            start(context, task)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PomodoroService::class.java)) }
        }
    }
}
