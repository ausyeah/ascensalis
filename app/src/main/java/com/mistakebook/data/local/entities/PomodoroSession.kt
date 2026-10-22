package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次专注会话（明细记录）。
 *
 * ## 为什么要有明细表
 *
 * 只存「某天某任务多少秒」的预聚合表能算时长，但**算不出番茄个数、
 * 也算不出连续天数**——因为「完成」是一个事件，不是一个累加量。
 * 这是不少番茄钟开源实现（如 Tomato）的短板：它按 (date, topic) 累加，
 * 于是永远答不出「我今天完成了几个番茄」「我连着几天在学」。
 *
 * 所以这里每次计时结束就落一条明细：
 * - `day` 冗余存一份本地日期（'yyyy-MM-dd'）：跨天边界的统计全靠它，
 *   且能直接 GROUP BY，不用在 SQL 里对时间戳做日期函数（各 SQLite
 *   版本的时区行为不一致，这是踩过的坑）；
 * - `completed`：这次是否**走完了目标时长**，而不是「计时了几秒」——
 *   番茄数只认 completed，正计时（无目标）也记为完成（用户主动收工）。
 */
@Entity(
    tableName = "pomodoro_sessions",
    foreignKeys = [
        ForeignKey(
            entity = PomodoroTask::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index("day"), Index("startedAt")]
)
data class PomodoroSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** [com.mistakebook.pomodoro.FocusMode] 的名字：POMODORO / TIMER / STOPWATCH。 */
    val mode: String,
    /** 本地日期键 `yyyy-MM-dd`，由写入时刻的 [java.time.LocalDate] 得到。 */
    val day: String,
    val startedAt: Long,
    val endedAt: Long,
    /** 实际专注秒数（暂停时间不计入）。 */
    val durationSec: Long,
    /** 目标秒数；正计时为 0。 */
    val targetSec: Long,
    val completed: Boolean
)
