package com.mistakebook.pomodoro

/**
 * 两种计时模式。
 *
 * ## 为什么从三种砍到两种
 *
 * 旧版有「番茄 / 定时 / 正计时」三种，而「定时」和「番茄」在界面上
 * 完全是同一个东西——都是倒计时，只是默认时长不同。用户原话：
 * 「倒计时和番茄钟重复了吧」。重复的模式按钮除了让人多按一次，
 * 没有任何信息量。
 *
 * 现在：番茄钟 = 倒计时（目标时长用轮盘自定，默认 45 分钟），
 * 正计时 = 没有终点。定时需求由「自定时长的番茄钟」覆盖——
 * 想要别的时长就把轮盘拨过去，比多一个模式按钮直观得多。
 *
 * ## 历史数据兼容
 *
 * 旧版本落库的会话里可能有 mode = 'TIMER' 的记录：[fromName] 对
 * 无法识别的名字一律回退 [POMODORO]，统计口径（番茄数只认 POMODORO）
 * 不受影响——定时记录本来就不该计入番茄数，回退后依然不计入。
 */
enum class FocusMode(
    val label: String,
    /** 默认目标时长（分钟）；0 表示无目标。 */
    val defaultMinutes: Int,
    val hasTarget: Boolean
) {
    POMODORO("番茄", 45, hasTarget = true),
    STOPWATCH("正计时", 0, hasTarget = false);

    /** 供统计与进度显示用的默认目标秒数。 */
    fun defaultTargetMs(): Long = if (hasTarget) defaultMinutes * 60_000L else 0L

    companion object {
        fun fromName(name: String): FocusMode =
            entries.firstOrNull { it.name == name } ?: POMODORO

        /** 长按循环切换的顺序：番茄 → 正计时 → 番茄。 */
        fun next(current: FocusMode): FocusMode =
            entries[(current.ordinal + 1) % entries.size]
    }
}

/** 计时状态。 */
enum class RunStatus { IDLE, RUNNING, PAUSED }
