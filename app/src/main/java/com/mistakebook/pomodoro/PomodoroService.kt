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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * `MissingForegroundServiceTypeException`。番茄钟一跑就是 45 分钟以上，
 * 而 `shortService` 只有约 3 分钟就会被系统停掉，所以只能用
 * `specialUse`（Manifest 里附了 property 说明用途）。
 *
 * ## 通知为什么自己每秒刷文字（不用系统 chronometer）
 *
 * 旧版用 [NotificationCompat.setUsesChronometer] + `setChronometerCountDown(true)`，
 * 把走秒交给系统渲染。实测真机（用户反馈）：**倒计时通知一直显示 0**。
 *
 * 根因：chronometer 的 countdown 标志在部分系统/SystemUI 上不生效，
 * 系统退化成正计时渲染 `now - when`，而 when 是**未来时刻**，
 * 差值为负被 clamp 成 0——现象就是"通知栏一直 00:00"，且完全看不出是哪坏了。
 *
 * 自己每秒 `notify()` 刷一行文字当然不如系统渲染省电，但：
 * 1. 显示完全可控，"剩余 12:34" 是这行文字本身，不依赖任何系统换算；
 * 2. 锁屏、通知栏、焦点通知里都是这段文字，行为一致；
 * 3. 每秒一次 `notify()` 的开销可以忽略（`setOnlyAlertOnce` + `setSilent` 已设，
 *    不会反复响铃震动）。
 * 倒计时/正计时/暂停三种形态都在 [buildNotification] 里按状态生成文字，
 * 暂停时时间冻结，tick 循环随之停掉，不空转。
 */
class PomodoroService : Service() {

    /** 通知按钮（暂停/继续/结束）的落点：活不重，但必须活得比点击长。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 最近一次启动通知时带的任务名：重建通知（暂停/继续）时沿用。 */
    private var lastTaskName: String = ""

    /**
     * 每秒刷新通知文字的循环。运行中才跑；暂停时间冻结、结束即停。
     *
     * 用 [refreshNotification] 重建整条通知而不是只改文字：
     * 倒计时到点要切换成"已完成"、暂停要撤掉计时态，
     * 重建时这些分支天然会走到，不用另养一套状态。
     */
    private var tickJob: Job? = null

    /**
     * 到点提醒只发一次的闸。
     *
     * `isFinished()` 在到点后恒为 true，不在 tick 里记状态的话每秒都会
     * 重复响铃。首次启动与每次 RESUME / 新 start 时重置——RESUME 用
     * `isFinished()` 而非 false 初始化：暂停-继续发生在到点之后时
     * 不该再响一遍。
     */
    private var finishAlerted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? MistakeBookApp ?: return START_NOT_STICKY
        val timer = app.container.pomodoroTimer

        when (intent?.action) {
            ACTION_PAUSE -> {
                // 通知里的「暂停」直接操作计时核心单例：界面每秒一轮的
                // 同步循环会把新状态带到 UI，不需要（也不该）反向依赖界面。
                timer.pause()
                stopTicking()
                refreshNotification()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                timer.resume()
                finishAlerted = timer.isFinished()
                startTicking()
                refreshNotification()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                // 「结束」= 收掉这次计时并按规则落库（与界面上的
                // 「结束并记录」同一个实现，见 PomodoroRecorder）。
                // 到点已自动落库的场合 timer 已是 IDLE，这里自然空转，不双记。
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
        finishAlerted = timer.isFinished()
        startTicking()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicking()
        scope.cancel()
        super.onDestroy()
    }

    /** 启动每秒刷新；已在跑就不重复起（重复 startForeground 会走到这里）。 */
    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
                refreshNotification()
                checkFinished()
            }
        }
    }

    /**
     * 到点检测（T2）：`isFinished()` 由 false 翻 true 的那一拍触发收尾。
     * 翻转后立刻停 tick（到点后没有秒级信息要刷，空转费电），
     * 后续动作在 [onTimerFinished] 里按设置分支处理。
     */
    private suspend fun checkFinished() {
        if (finishAlerted) return
        val timer = (application as MistakeBookApp).container.pomodoroTimer
        if (!timer.isFinished()) return
        finishAlerted = true
        stopTicking()
        onTimerFinished(timer)
    }

    /** 到点收尾：休息到点直接收摊；番茄到点按「自动落库 / 休息循环」设置分流。 */
    private suspend fun onTimerFinished(timer: PomodoroTimer) {
        val app = application as MistakeBookApp
        val container = app.container
        val finishedState = timer.state.value
        val isBreak = finishedState.mode == FocusMode.BREAK

        if (isBreak) {
            // 休息到点的完整收尾：提醒 → BREAK 会话落库（统计口径已排除它，
            // 落库只为「最近专注」可追溯）→ 常驻通知随服务消失，只留提醒。
            // 不停在「休息结束」的常驻态等用户来点结束——那会把到点无提醒、
            // 通知空转的老问题原样复制到休息上。也不自动开始下一个番茄。
            postDoneNotification(
                title = "☕ 休息结束，回来继续吧",
                text = "休息了 ${FocusMode.BREAK.defaultMinutes} 分钟，想继续就回 App 开一轮新的专注"
            )
            PomodoroRecorder.stopAndRecord(timer, container.pomodoroDao)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 番茄到点：响铃 + 震动提醒
        postDoneNotification(title = "🍅 番茄完成！", text = "本次专注已达到目标时长")

        val settings = container.settingsStore.snapshotNow()
        if (settings.pomodoroAutoRecord) {
            // 自动落库：此时 elapsed 已被钳在 target，时长数据是准的。
            PomodoroRecorder.stopAndRecord(timer, container.pomodoroDao)
            if (settings.pomodoroBreakLoop) {
                // 休息循环：原任务自动进入休息倒计时，tick 恢复
                timer.start(
                    FocusMode.BREAK,
                    finishedState.taskId,
                    settings.pomodoroBreakMinutes * 60_000L
                )
                finishAlerted = false
                startTicking()
                refreshNotification()
            } else {
                // 已自动记录，没有常驻态需要维持：收掉前台服务，只留刚才那条提醒
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            // 手动路径：常驻通知切「已完成 · 点结束记录」，单键「结束」，
            // 等用户自己确认落库
            refreshNotification()
        }
    }

    /** 发一条非常驻的到点提醒（独立于常驻通知，带默认提示音与震动）。 */
    private fun postDoneNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureDoneChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(VIBRATION_PATTERN)
            .build()
        manager.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
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
        val finished = timer.isFinished()
        val isBreak = state.mode == FocusMode.BREAK

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
            .setContentTitle(title.ifBlank { if (isBreak) "休息中" else "专注中" })
            // 常驻、静默：它不是一条「通知」，而是用户主动开启的一个持续状态
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        when {
            // 到点未确认（自动记录关闭时）：单键等用户来收
            finished -> builder.setContentText("已完成 · 点结束记录")

            // 休息倒计时（T2 休息循环）
            isBreak && running -> builder.setContentText("☕ 休息中 剩余 ${formatClock(remaining)}")
            isBreak -> builder.setContentText("☕ 休息已暂停 · 剩余 ${formatClock(remaining)}")

            // 运行中：每秒重刷的静态文字。见类文档——系统 chronometer 的
            // countdown 在部分系统上失效会一直显示 0，不能再用。
            // 暂停时时间冻结，tick 循环停掉，不会"暂停了还在走秒"。
            running && state.targetMs > 0L -> builder.setContentText("剩余 ${formatClock(remaining)}")
            running -> builder.setContentText("已专注 ${formatClock(elapsed)}")
            else -> builder.setContentText(
                if (state.targetMs > 0L) "已暂停 · 剩余 ${formatClock(remaining)}"
                else "已暂停 · 已专注 ${formatClock(elapsed)}"
            )
        }

        when {
            finished -> {
                // 到点后没有「暂停」可言，单键「结束」
                builder.addAction(0, "结束", servicePendingIntent(REQUEST_STOP, ACTION_STOP))
            }
            running -> {
                builder.addAction(
                    0,
                    "暂停",
                    servicePendingIntent(REQUEST_PAUSE, ACTION_PAUSE)
                )
                builder.addAction(0, "结束", servicePendingIntent(REQUEST_STOP, ACTION_STOP))
            }
            else -> {
                builder.addAction(
                    0,
                    "继续",
                    servicePendingIntent(REQUEST_RESUME, ACTION_RESUME)
                )
                builder.addAction(0, "结束", servicePendingIntent(REQUEST_STOP, ACTION_STOP))
            }
        }

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

    /**
     * 到点提醒渠道（T2）：IMPORTANCE_DEFAULT 带默认提示音——
     * 常驻渠道是 IMPORTANCE_LOW 静默的，到点提醒必须能穿透专注状态被听见。
     */
    private fun ensureDoneChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(DONE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL_ID, "番茄钟提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "番茄或休息到点时的提醒"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "pomodoro"
        const val DONE_CHANNEL_ID = "pomodoro_done"
        const val NOTIFICATION_ID = 20261

        /** 到点提醒的独立通知 id：与常驻通知并存，互不覆盖。 */
        const val DONE_NOTIFICATION_ID = 20262

        /** 震动节奏：停 0ms · 震 400ms · 停 200ms · 震 400ms。 */
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)

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
