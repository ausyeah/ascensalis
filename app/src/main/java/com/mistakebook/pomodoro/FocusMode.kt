package com.mistakebook.pomodoro

/**
 * 三种计时模式。
 *
 * 用户要的是「定时 / 倒计时 / 正向计时」，三者差别只在于**有没有终点、
 * 终点怎么定**，所以共用一个计时核心，而不是写三套计时器：
 *
 * - [POMODORO] 番茄：25 分钟倒计时，到点停。番茄数是统计里最核心的口径，
 *   只有这一种模式计入「完成番茄数」。
 * - [TIMER] 定时：自定义时长（默认 45 分钟）倒计时，到点停。
 *   与番茄的差别是时长自定、且不参与番茄计数。
 * - [STOPWATCH] 正计时：没有终点，用户自己按停。适合「我不知道要学多久、
 *   先学着」——这是最长情的用法，不该被逼着先填一个数字。
 */
enum class FocusMode(
    val label: String,
    /** 默认目标时长（分钟）；0 表示无目标。 */
    val defaultMinutes: Int,
    val hasTarget: Boolean
) {
    POMODORO("番茄", 25, hasTarget = true),
    TIMER("定时", 45, hasTarget = true),
    STOPWATCH("正计时", 0, hasTarget = false);

    /** 供统计与进度显示用的默认目标秒数。 */
    fun defaultTargetMs(): Long = if (hasTarget) defaultMinutes * 60_000L else 0L

    companion object {
        fun fromName(name: String): FocusMode =
            entries.firstOrNull { it.name == name } ?: POMODORO

        /** 长按循环切换的顺序：番茄 → 定时 → 正计时 → 番茄。 */
        fun next(current: FocusMode): FocusMode =
            entries[(current.ordinal + 1) % entries.size]
    }
}

/** 计时状态。 */
enum class RunStatus { IDLE, RUNNING, PAUSED }
