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
    STOPWATCH("正计时", 0, hasTarget = false),

    /**
     * 休息（T2 休息循环）：番茄到点自动进入的缓冲倒计时。
     *
     * **不可手动选中**——只能由到点自动触发。长按循环（[next]）会跳过它，
     * 模式切换 UI 也不展示它，用户永远不该「开始一个休息」。
     * 落库后统计口径把它从时长/连续天数里排除（见 PomodoroDao 各查询
     * 的 `mode != 'BREAK'`），只进「最近专注」明细带「休息」标签可追溯。
     */
    BREAK("休息", 10, hasTarget = true);

    /** 供统计与进度显示用的默认目标秒数。 */
    fun defaultTargetMs(): Long = if (hasTarget) defaultMinutes * 60_000L else 0L

    companion object {
        fun fromName(name: String): FocusMode =
            entries.firstOrNull { it.name == name } ?: POMODORO

        /**
         * 长按循环切换的顺序：番茄 → 正计时 → 番茄。
         *
         * BREAK 被显式跳过：它是到点的自动产物，不该出现在手动循环里。
         */
        fun next(current: FocusMode): FocusMode {
            var candidate = entries[(current.ordinal + 1) % entries.size]
            while (candidate == BREAK) {
                candidate = entries[(candidate.ordinal + 1) % entries.size]
            }
            return candidate
        }
    }
}

/** 计时状态。 */
enum class RunStatus { IDLE, RUNNING, PAUSED }
