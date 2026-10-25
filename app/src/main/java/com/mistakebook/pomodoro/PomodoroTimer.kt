package com.mistakebook.pomodoro

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 番茄钟计时核心。
 *
 * ## 为什么不用 CountDownTimer / Handler 累加 tick
 *
 * 那个写法有两个致命问题：
 * 1. 息屏或进程被系统回收后，tick 不再回调，计时**悄悄停了**，
 *    回来一看时间比实际少；
 * 2. 每 tick 加 1000ms，误差会累积（掉帧、后台节流都吃得到）。
 *
 * 所以这里只记**开机以来的单调时钟起点**（[SystemClock.elapsedRealtime]），
 * 每次要显示时用 `now - start` 现算。单调时钟不受用户改系统时间影响，
 * 也不受时区影响；息屏期间它照常走。
 *
 * ## 进程被杀怎么办
 *
 * 状态每次变化都落 SharedPreferences，UI 每秒还会顺手补写一次
 * `baseElapsedMs`。下次启动恢复时：
 * - 正常情况：`elapsed = base + (now - startRealtime)`；
 * - **重启过**：`elapsedRealtime` 从 0 重新计数，于是
 *   `now < startRealtime`——这时只用 `base`，并把状态置为**已暂停**，
 *   让用户自己决定是接着算还是放弃（默默把丢失的时间算进专注时长，
 *   统计就假了）。
 */
class PomodoroTimer(context: Context) {

    data class State(
        val status: RunStatus = RunStatus.IDLE,
        val mode: FocusMode = FocusMode.POMODORO,
        val taskId: Long? = null,
        val targetMs: Long = FocusMode.POMODORO.defaultTargetMs(),
        /** 已累计的专注毫秒（不含当前这段）。 */
        val baseElapsedMs: Long = 0L,
        /** 当前这段开始的单调时刻；仅在 RUNNING 时有效。 */
        val startRealtimeMs: Long = 0L,
        /** 本次会话开始的墙上时间，用于落库与跨天判断。 */
        val startedWallMs: Long = 0L,
        /** 上次是被系统重启打断的（恢复后要提示用户）。 */
        val interruptedByReboot: Boolean = false
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("pomodoro_timer", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前已专注的毫秒数（暂停时间不计）。 */
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val s = _state.value
        val running = if (s.status == RunStatus.RUNNING) {
            (now - s.startRealtimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        // 倒计时到点后不再增长：否则用户没点停止，数字会越过 0 一直跑
        val raw = s.baseElapsedMs + running
        return if (s.targetMs > 0L) raw.coerceAtMost(s.targetMs) else raw
    }

    /** 倒计时剩余毫秒；正计时返回 null。 */
    fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long? {
        val s = _state.value
        if (s.targetMs <= 0L) return null
        return (s.targetMs - elapsedMs(now)).coerceAtLeast(0L)
    }

    /** 是否已达目标（倒计时到点）。 */
    fun isFinished(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val s = _state.value
        return s.targetMs > 0L && elapsedMs(now) >= s.targetMs && s.status != RunStatus.IDLE
    }

    // ------------------------------------------------------------ 操作

    fun start(mode: FocusMode, taskId: Long?, targetMs: Long) {
        val now = SystemClock.elapsedRealtime()
        _state.value = State(
            status = RunStatus.RUNNING,
            mode = mode,
            taskId = taskId,
            targetMs = if (mode.hasTarget) targetMs else 0L,
            baseElapsedMs = 0L,
            startRealtimeMs = now,
            startedWallMs = System.currentTimeMillis()
        )
        save()
    }

    fun pause() {
        val s = _state.value
        if (s.status != RunStatus.RUNNING) return
        val now = SystemClock.elapsedRealtime()
        _state.value = s.copy(
            status = RunStatus.PAUSED,
            baseElapsedMs = s.baseElapsedMs + (now - s.startRealtimeMs).coerceAtLeast(0L)
        )
        save()
    }

    fun resume() {
        val s = _state.value
        if (s.status != RunStatus.PAUSED) return
        _state.value = s.copy(
            status = RunStatus.RUNNING,
            startRealtimeMs = SystemClock.elapsedRealtime()
        )
        save()
    }

    /**
     * 重置为待开始状态。
     *
     * **模式必须原样保留**：正计时停下来还停在正计时（目标 0、读数归零）。
     * 早期版本这里直接 `State()`，模式被弹回番茄——用户在正计时页点
     * 「结束并记录」，界面「嗖」地跳回番茄钟页，还以为自己按错了。
     * 目标时长回到该模式的默认值（番茄 = 45 分钟），不继承上一次
     * 拨轮设的自定义值：一次专注结束，表盘就该回到标准起点。
     */
    fun reset() {
        _state.value = idleStateFor(_state.value.mode)
        save()
    }

    /** 只改模式（空闲状态下长按切换用），目标时长跟着模式的默认值走。 */
    fun setMode(mode: FocusMode, targetMs: Long = mode.defaultTargetMs()) {
        val s = _state.value
        if (s.status == RunStatus.RUNNING) return
        _state.value = s.copy(
            mode = mode,
            targetMs = if (mode.hasTarget) targetMs else 0L
        )
        save()
    }

    fun setTargetMs(targetMs: Long) {
        val s = _state.value
        _state.value = s.copy(targetMs = targetMs.coerceAtLeast(60_000L))
        save()
    }

    /** 每秒由 UI 调一次：把已跑的时间固化进 base，进程猝死也不丢太多。 */
    fun checkpoint() {
        if (_state.value.status == RunStatus.RUNNING) save()
    }

    /** 取回本次结果并清空（用于落库）。 */
    fun harvest(): Harvest? {
        val s = _state.value
        if (s.status == RunStatus.IDLE) return null
        val elapsed = elapsedMs()
        val result = Harvest(
            taskId = s.taskId,
            mode = s.mode,
            startedWallMs = s.startedWallMs,
            durationMs = elapsed,
            targetMs = s.targetMs,
            completed = s.targetMs <= 0L || elapsed >= s.targetMs
        )
        reset()
        return result
    }

    data class Harvest(
        val taskId: Long?,
        val mode: FocusMode,
        val startedWallMs: Long,
        val durationMs: Long,
        val targetMs: Long,
        val completed: Boolean
    )

    fun acknowledgeRebootNotice() {
        if (_state.value.interruptedByReboot) {
            _state.value = _state.value.copy(interruptedByReboot = false)
            save()
        }
    }

    // ------------------------------------------------------------ 持久化

    private fun save() {
        val s = _state.value
        prefs.edit {
            putString(KEY_STATUS, s.status.name)
            putString(KEY_MODE, s.mode.name)
            putLong(KEY_TASK, s.taskId ?: -1L)
            putLong(KEY_TARGET, s.targetMs)
            // 存盘时把「已跑的时间」固化进 base：下次恢复直接从这个值继续，
            // 即便进程被杀也只丢最多一秒。
            putLong(KEY_BASE, if (s.status == RunStatus.RUNNING) {
                s.baseElapsedMs +
                    (SystemClock.elapsedRealtime() - s.startRealtimeMs).coerceAtLeast(0L)
            } else {
                s.baseElapsedMs
            })
            putLong(KEY_START_RT, s.startRealtimeMs)
            putLong(KEY_START_WALL, s.startedWallMs)
            putBoolean(KEY_REBOOT, s.interruptedByReboot)
        }
    }

    private fun load(): State {
        val status = runCatching { RunStatus.valueOf(prefs.getString(KEY_STATUS, RunStatus.IDLE.name)!!) }
            .getOrDefault(RunStatus.IDLE)
        if (status == RunStatus.IDLE) return State()

        val mode = runCatching { FocusMode.valueOf(prefs.getString(KEY_MODE, FocusMode.POMODORO.name)!!) }
            .getOrDefault(FocusMode.POMODORO)
        val taskId = prefs.getLong(KEY_TASK, -1L).takeIf { it > 0 }
        val target = prefs.getLong(KEY_TARGET, mode.defaultTargetMs())
        val base = prefs.getLong(KEY_BASE, 0L)
        val startRt = prefs.getLong(KEY_START_RT, 0L)
        val startWall = prefs.getLong(KEY_START_WALL, 0L)

        val now = SystemClock.elapsedRealtime()
        // 重启过：单调时钟归零，起点反而「在未来」
        val rebooted = status == RunStatus.RUNNING && now < startRt
        val effectiveStatus = when {
            rebooted -> RunStatus.PAUSED
            status == RunStatus.RUNNING -> RunStatus.RUNNING
            else -> RunStatus.PAUSED
        }
        return State(
            status = effectiveStatus,
            mode = mode,
            taskId = taskId,
            targetMs = target,
            baseElapsedMs = base,
            startRealtimeMs = if (rebooted) now else startRt,
            startedWallMs = startWall,
            interruptedByReboot = rebooted
        )
    }

    private companion object {
        const val KEY_STATUS = "status"
        const val KEY_MODE = "mode"
        const val KEY_TASK = "taskId"
        const val KEY_TARGET = "targetMs"
        const val KEY_BASE = "baseElapsedMs"
        const val KEY_START_RT = "startRt"
        const val KEY_START_WALL = "startWall"
        const val KEY_REBOOT = "rebooted"
    }
}

/**
 * 归位后的待开始状态：**模式原样保留**，目标回该模式默认值。
 *
 * 抽成纯函数是为了能单测——「正计时停止后不弹回番茄」这条教训
 * 值得一个回归测试钉死（见 TimerResetTest）。
 */
internal fun idleStateFor(mode: FocusMode): PomodoroTimer.State =
    PomodoroTimer.State(mode = mode, targetMs = mode.defaultTargetMs())
